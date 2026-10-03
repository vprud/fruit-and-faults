package org.fruitandfaults.validation.infra;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

import org.fruitandfaults.validation.application.ProcessRequest;
import org.fruitandfaults.validation.application.ProcessResult;
import org.fruitandfaults.validation.application.ProcessRunner;

/**
 * Runs argument lists directly, continuously drains both pipes, and reserves half the aggregate
 * retained-byte budget for each stream so stderr cannot be crowded out. Every call owns two drain
 * threads and at most 1024 observed descendants. Cancellation and every other exit have a shared
 * two-second cleanup deadline. Descendant discovery is best effort: Java has no portable process
 * group/job object, so descendants that detach before observation cannot be tracked.
 */
public final class BoundedProcessRunner implements ProcessRunner {
  private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(25);
  private static final long CLEANUP_NANOS = TimeUnit.SECONDS.toNanos(2);
  private static final int MAX_DESCENDANTS = 1024;
  private final ProcessLauncher launcher;
  private final ProcessWaiter waiter;
  private final LongSupplier nanoTime;

  /** Uses the Java process API without adding a shell or modifying the caller's environment. */
  public BoundedProcessRunner() {
    this(ProcessBuilder::start, (process, nanos) -> process.waitFor(nanos, TimeUnit.NANOSECONDS));
  }

  BoundedProcessRunner(ProcessLauncher launcher, ProcessWaiter waiter) {
    this(launcher, waiter, System::nanoTime);
  }

  BoundedProcessRunner(ProcessLauncher launcher, ProcessWaiter waiter, LongSupplier nanoTime) {
    this.launcher = Objects.requireNonNull(launcher);
    this.waiter = Objects.requireNonNull(waiter);
    this.nanoTime = Objects.requireNonNull(nanoTime);
  }

  @Override
  public ProcessResult run(ProcessRequest request) {
    Objects.requireNonNull(request);
    if (Thread.currentThread().isInterrupted()) {
      return new ProcessResult.Interrupted(
          ProcessResult.Output.empty(), ProcessResult.Cleanup.COMPLETE);
    }
    Path directory;
    try {
      directory = verifiedDirectory(request.workingDirectory());
    } catch (IOException | SecurityException invalid) {
      return failed(
          ProcessResult.FailureReason.INVALID_WORKING_DIRECTORY, ProcessResult.Output.empty());
    }
    Process process;
    long deadline = nanoTime.getAsLong() + request.timeout().toNanos();
    try {
      process =
          launcher.start(new ProcessBuilder(request.arguments()).directory(directory.toFile()));
    } catch (IOException | SecurityException unavailable) {
      if (Thread.currentThread().isInterrupted()) {
        return new ProcessResult.Interrupted(
            ProcessResult.Output.empty(), ProcessResult.Cleanup.COMPLETE);
      }
      return failed(ProcessResult.FailureReason.UNAVAILABLE, ProcessResult.Output.empty());
    }
    Session session = new Session(process, request.maxCapturedBytes());
    State state = State.EXITED;
    int exitCode = 0;
    try {
      session.startReaders();
      process.getOutputStream().close();
      while (true) {
        session.observeDescendants();
        long remaining = deadline - nanoTime.getAsLong();
        if (remaining <= 0) {
          state = State.TIMED_OUT;
          break;
        }
        if (waiter.await(process, Math.min(POLL_NANOS, remaining))) {
          exitCode = process.exitValue();
          break;
        }
        // An injected/platform waiter may report timeout earlier than the requested slice.
        if (!process.isAlive()) {
          exitCode = process.exitValue();
          break;
        }
      }
    } catch (InterruptedException cancelled) {
      session.interrupted = true;
      state = State.INTERRUPTED;
    } catch (IOException failed) {
      state = State.OUTPUT_FAILED;
    } finally {
      session.finish(Math.max(0, deadline - nanoTime.getAsLong()), state == State.EXITED);
    }
    ProcessResult.Output output = session.output();
    if (session.interrupted) {
      Thread.currentThread().interrupt();
      return new ProcessResult.Interrupted(output, session.cleanup());
    }
    return switch (state) {
      case TIMED_OUT -> new ProcessResult.TimedOut(output, session.cleanup());
      case INTERRUPTED -> new ProcessResult.Interrupted(output, session.cleanup());
      case OUTPUT_FAILED -> failed(ProcessResult.FailureReason.OUTPUT_FAILURE, output);
      case EXITED -> {
        if (session.cleanup() != ProcessResult.Cleanup.COMPLETE) {
          yield failed(ProcessResult.FailureReason.CLEANUP_FAILURE, output);
        }
        if (session.outputFailed) {
          yield failed(ProcessResult.FailureReason.OUTPUT_FAILURE, output);
        }
        if (session.outputTimedOut) {
          yield new ProcessResult.TimedOut(output, session.cleanup());
        }
        yield new ProcessResult.Exited(exitCode, output);
      }
    };
  }

  private static Path verifiedDirectory(Path directory) throws IOException {
    Path current = Objects.requireNonNull(directory.getRoot());
    for (Path segment : directory) {
      current = current.resolve(segment);
      if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) {
        throw new IOException("Process directory must have real directory ancestors.");
      }
    }
    return directory.toRealPath();
  }

  private static ProcessResult.Failed failed(
      ProcessResult.FailureReason reason, ProcessResult.Output output) {
    String diagnostic =
        switch (reason) {
          case UNAVAILABLE ->
              "The executable could not be started; verify it exists and can be run.";
          case INVALID_WORKING_DIRECTORY ->
              "Select an existing real workspace directory without symlink ancestors.";
          case OUTPUT_FAILURE ->
              "Process output could not be read reliably; retry validation and inspect the local toolchain.";
          case CLEANUP_FAILURE ->
              "Process cleanup could not be verified within its deadline; inspect remaining local processes before retrying.";
        };
    return new ProcessResult.Failed(reason, diagnostic, output);
  }

  private enum State {
    EXITED,
    TIMED_OUT,
    INTERRUPTED,
    OUTPUT_FAILED
  }

  private static final class Session {
    private final Process process;
    private final Tail stdout;
    private final Tail stderr;
    private final ThreadPoolExecutor drains;
    private final List<Future<?>> readers = new ArrayList<>();
    private final Map<Long, ProcessHandle> descendants = new LinkedHashMap<>();
    private boolean interrupted;
    private boolean unsupported;
    private boolean incomplete;
    private boolean outputFailed;
    private boolean outputTimedOut;

    Session(Process process, int limit) {
      this.process = process;
      stdout = new Tail(limit / 2);
      stderr = new Tail(limit - limit / 2);
      drains =
          new ThreadPoolExecutor(
              2,
              2,
              0,
              TimeUnit.NANOSECONDS,
              new ArrayBlockingQueue<>(2),
              runnable -> {
                Thread thread = new Thread(runnable, "faf-process-drain");
                thread.setDaemon(true);
                return thread;
              });
    }

    void startReaders() {
      readers.add(
          drains.submit(
              () -> {
                collect(process.getInputStream(), stdout);
                return null;
              }));
      readers.add(
          drains.submit(
              () -> {
                collect(process.getErrorStream(), stderr);
                return null;
              }));
    }

    void observeDescendants() {
      try (var found = process.descendants()) {
        for (ProcessHandle handle : found.limit(MAX_DESCENDANTS + 1L).toList()) {
          if (!descendants.containsKey(handle.pid())) {
            if (descendants.size() == MAX_DESCENDANTS) {
              incomplete = true;
              handle.destroyForcibly();
              continue;
            }
            descendants.put(handle.pid(), handle);
          }
        }
      } catch (UnsupportedOperationException | SecurityException unavailable) {
        unsupported = true;
      }
    }

    void finish(long remainingExecutionNanos, boolean normalExit) {
      interrupted |= Thread.interrupted();
      long now = System.nanoTime();
      long executionDeadline = now + remainingExecutionNanos;
      long cleanupDeadline = now + CLEANUP_NANOS;
      observeDescendants();
      terminateDescendants(cleanupDeadline);
      // Reinspect before stopping the parent to own children created during termination.
      observeDescendants();
      terminateDescendants(cleanupDeadline);
      if (process.isAlive()) {
        process.destroyForcibly();
      }
      awaitParent(cleanupDeadline);
      long outputDeadline =
          normalExit ? Math.min(executionDeadline, cleanupDeadline) : cleanupDeadline;
      for (Future<?> reader : readers) {
        try {
          reader.get(Math.max(1, outputDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException cancelled) {
          interrupted = true;
        } catch (ExecutionException failed) {
          outputFailed = true;
        } catch (TimeoutException timeout) {
          outputTimedOut = true;
        }
      }
      readers.forEach(reader -> reader.cancel(true));
      drains.shutdownNow();
      try {
        if (!drains.awaitTermination(
            Math.max(1, cleanupDeadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
          incomplete = true;
        }
      } catch (InterruptedException cancelled) {
        interrupted = true;
        incomplete |= !drains.isTerminated();
      }
      incomplete |=
          process.isAlive() || descendants.values().stream().anyMatch(ProcessHandle::isAlive);
    }

    private void terminateDescendants(long deadline) {
      try {
        descendants.values().stream()
            .filter(ProcessHandle::isAlive)
            .forEach(ProcessHandle::destroyForcibly);
        CompletableFuture<?>[] exits =
            descendants.values().stream()
                .filter(ProcessHandle::isAlive)
                .map(ProcessHandle::onExit)
                .toArray(CompletableFuture<?>[]::new);
        CompletableFuture.allOf(exits)
            .get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      } catch (InterruptedException cancelled) {
        interrupted = true;
      } catch (ExecutionException | TimeoutException failed) {
        incomplete = true;
      } catch (UnsupportedOperationException | SecurityException unavailable) {
        unsupported = true;
      }
    }

    private void awaitParent(long deadline) {
      try {
        if (!process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
          incomplete = true;
        }
      } catch (InterruptedException cancelled) {
        interrupted = true;
        process.destroyForcibly();
        incomplete |= process.isAlive();
      }
    }

    ProcessResult.Output output() {
      return new ProcessResult.Output(
          stdout.text(), stderr.text(), stdout.truncated(), stderr.truncated());
    }

    ProcessResult.Cleanup cleanup() {
      return incomplete
          ? ProcessResult.Cleanup.INCOMPLETE
          : unsupported ? ProcessResult.Cleanup.UNSUPPORTED : ProcessResult.Cleanup.COMPLETE;
    }
  }

  private static void collect(InputStream input, Tail tail) throws IOException {
    try (input) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        tail.append(buffer, count);
      }
    }
  }

  private static final class Tail {
    private final byte[] bytes;
    private int position;
    private int size;
    private boolean truncated;

    Tail(int capacity) {
      bytes = new byte[capacity];
    }

    synchronized void append(byte[] incoming, int count) {
      truncated |= count > bytes.length - size;
      if (bytes.length == 0) {
        return;
      }
      int start = Math.max(0, count - bytes.length);
      for (int index = start; index < count; index++) {
        bytes[position] = incoming[index];
        position = (position + 1) % bytes.length;
      }
      size = Math.min(bytes.length, size + count);
    }

    synchronized String text() {
      byte[] ordered = new byte[size];
      int start = size == bytes.length ? position : 0;
      for (int index = 0; index < size; index++) {
        ordered[index] = bytes[(start + index) % bytes.length];
      }
      try {
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.IGNORE)
            .onUnmappableCharacter(CodingErrorAction.IGNORE)
            .decode(ByteBuffer.wrap(ordered))
            .toString();
      } catch (CharacterCodingException impossible) {
        throw new IllegalStateException(
            "UTF-8 decoder is configured to discard malformed bytes.", impossible);
      }
    }

    synchronized boolean truncated() {
      return truncated;
    }
  }

  @FunctionalInterface
  interface ProcessLauncher {
    Process start(ProcessBuilder builder) throws IOException;
  }

  @FunctionalInterface
  interface ProcessWaiter {
    boolean await(Process process, long nanos) throws InterruptedException, IOException;
  }
}
