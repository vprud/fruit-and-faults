package org.fruitandfaults.git.infra;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.fruitandfaults.git.application.GitInitializationException;
import org.fruitandfaults.git.application.GitRepository;

/** Owns one bounded Git init process and its bounded output drain, preserving cancellation. */
public final class ProcessGitRepository implements GitRepository {
  private final Duration timeout;
  private final int outputLimit;
  private final ProcessLauncher launcher;

  /** Uses a ten-second deadline, 64 KiB output cap, and the installed local Git executable. */
  public ProcessGitRepository() {
    this(Duration.ofSeconds(10), 65_536, ProcessGitRepository::launch);
  }

  ProcessGitRepository(Duration timeout, int outputLimit, ProcessLauncher launcher) {
    if (timeout.isZero()
        || timeout.isNegative()
        || timeout.compareTo(Duration.ofMinutes(1)) > 0
        || outputLimit < 1
        || outputLimit > 1_048_576) {
      throw new IllegalArgumentException(
          "Expected a bounded positive Git deadline and output limit.");
    }
    this.timeout = timeout;
    this.outputLimit = outputLimit;
    this.launcher = Objects.requireNonNull(launcher);
  }

  @Override
  public void initialize(Path root) throws IOException {
    Path normalized = root.toAbsolutePath().normalize();
    requireSafeDestination(normalized);
    Process process;
    try {
      process =
          launcher.start(
              List.of("git", "-C", normalized.toString(), "init", "--quiet", "--template="));
    } catch (IOException launchFailed) {
      throw failure(GitInitializationException.Reason.UNAVAILABLE);
    }
    ExecutorService drain =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "fruit-and-faults-git-output");
              thread.setDaemon(true);
              return thread;
            });
    Future<String> captured = drain.submit(() -> readBounded(process.getInputStream()));
    try {
      process.getOutputStream().close();
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        throw failure(GitInitializationException.Reason.TIMEOUT);
      }
      String output = captured.get(1, TimeUnit.SECONDS);
      if (process.exitValue() != 0) {
        throw new GitInitializationException(
            GitInitializationException.Reason.EXIT_FAILURE,
            OptionalInt.of(process.exitValue()),
            output);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw failure(GitInitializationException.Reason.INTERRUPTED);
    } catch (ExecutionException | TimeoutException failed) {
      throw failure(GitInitializationException.Reason.UNAVAILABLE);
    } finally {
      terminate(process);
      captured.cancel(true);
      drain.shutdownNow();
      process.getInputStream().close();
      process.getErrorStream().close();
    }
  }

  private String readBounded(InputStream input) throws IOException {
    try (input;
        ByteArrayOutputStream retained = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        int remaining = outputLimit - retained.size();
        if (remaining > 0) {
          retained.write(buffer, 0, Math.min(count, remaining));
        }
      }
      return retained.toString(StandardCharsets.UTF_8);
    }
  }

  private static Process launch(List<String> command) throws IOException {
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    // Git location/config environment variables must not redirect writes outside the chosen root.
    builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    return builder.start();
  }

  private static void requireSafeDestination(Path root) throws IOException {
    Path current = Objects.requireNonNull(root.getRoot());
    for (Path segment : root) {
      current = current.resolve(segment);
      if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) {
        throw new IOException("Git init requires real directories without symlink ancestors.");
      }
    }
    if (Files.exists(root.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException(
          "Git directory already exists; preserve it and resume the initialized workspace.");
    }
  }

  private static void terminate(Process process) {
    if (!process.isAlive()) {
      return;
    }
    process.destroy();
    try {
      if (!process.waitFor(1, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor(1, TimeUnit.SECONDS);
      }
    } catch (InterruptedException interrupted) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    }
  }

  private static GitInitializationException failure(GitInitializationException.Reason reason) {
    return new GitInitializationException(reason, OptionalInt.empty(), "");
  }

  @FunctionalInterface
  interface ProcessLauncher {
    Process start(List<String> command) throws IOException;
  }
}
