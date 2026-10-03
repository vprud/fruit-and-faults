package org.fruitandfaults.validation.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.fruitandfaults.validation.application.ProcessRequest;
import org.fruitandfaults.validation.application.ProcessResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoundedProcessRunnerTest {
  @TempDir private Path temporary;
  private Path root;

  @BeforeEach
  void selectRealDirectory() throws IOException {
    root = Files.createDirectory(temporary.toRealPath().resolve("Игра with spaces; $value"));
  }

  @Test
  void preservesArgumentsAndWorkingDirectoryWithoutShellExpansion() {
    ProcessResult.Exited result =
        assertInstanceOf(
            ProcessResult.Exited.class,
            new BoundedProcessRunner()
                .run(request("echo", "Привет world; $(touch unwanted) & %PATH%")));

    assertEquals(0, result.exitCode());
    assertEquals(root + "\nПривет world; $(touch unwanted) & %PATH%\n", result.output().stdout());
    assertEquals("stderr message\n", result.output().stderr());
    assertFalse(result.output().stdoutTruncated());
    assertFalse(Files.exists(root.resolve("unwanted")));
  }

  @Test
  void retainsNonZeroExitAndSeparateErrorOutput() {
    ProcessResult.Exited result =
        assertInstanceOf(
            ProcessResult.Exited.class, new BoundedProcessRunner().run(request("failure")));
    assertEquals(23, result.exitCode());
    assertEquals("useful stdout\n", result.output().stdout());
    assertEquals("useful stderr\n", result.output().stderr());
  }

  @Test
  void copiesBoundedStandardInputAndClosesThePipeAfterDelivery() {
    byte[] bytes = "one-time secret".getBytes(StandardCharsets.UTF_8);
    ProcessRequest request =
        new ProcessRequest(command("stdin"), root, Duration.ofSeconds(10), 4096, bytes);
    bytes[0] = 'X';
    request.standardInput()[0] = 'Y';
    ProcessResult.Exited result =
        assertInstanceOf(ProcessResult.Exited.class, new BoundedProcessRunner().run(request));
    assertEquals(0, result.exitCode());
    assertEquals("one-time secret", result.output().stdout());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProcessRequest(
                command("stdin"), root, Duration.ofSeconds(10), 4096, new byte[65_537]));
  }

  @Test
  void blockedInputDeliverySharesTheProcessDeadlineAndLeavesNoIoThreads() throws Exception {
    AtomicLong clock = new AtomicLong();
    AtomicReference<Process> child = new AtomicReference<>();
    try (WatchService ready = root.getFileSystem().newWatchService()) {
      root.register(ready, StandardWatchEventKinds.ENTRY_CREATE);
      BoundedProcessRunner runner =
          new BoundedProcessRunner(
              builder -> {
                Process process = builder.start();
                child.set(process);
                return process;
              },
              (process, nanos) -> {
                assertTrue(ready.poll(5, TimeUnit.SECONDS) != null);
                assertTrue(Files.exists(root.resolve("input-ready")));
                clock.set(TimeUnit.SECONDS.toNanos(10));
                return false;
              },
              clock::get);
      ProcessResult.TimedOut result =
          assertInstanceOf(
              ProcessResult.TimedOut.class,
              runner.run(
                  new ProcessRequest(
                      command("ignore-input"),
                      root,
                      Duration.ofSeconds(10),
                      4096,
                      new byte[65_536])));
      assertEquals(ProcessResult.Cleanup.COMPLETE, result.cleanup());
      assertFalse(Objects.requireNonNull(child.get()).isAlive());
      assertFalse(
          Thread.getAllStackTraces().keySet().stream()
              .anyMatch(thread -> thread.isAlive() && thread.getName().startsWith("faf-process-")));
    }
  }

  @Test
  void drainsBothFullPipesAndRetainsTheirUsefulTailsWithinAggregateBudget() {
    ProcessRequest request =
        new ProcessRequest(command("flood"), root, Duration.ofSeconds(10), 257);
    ProcessResult.Exited result =
        assertInstanceOf(ProcessResult.Exited.class, new BoundedProcessRunner().run(request));
    assertEquals(0, result.exitCode());
    assertTrue(result.output().stdout().endsWith("stdout useful tail\n"));
    assertTrue(result.output().stderr().endsWith("stderr useful tail\n"));
    assertTrue(result.output().stdoutTruncated());
    assertTrue(result.output().stderrTruncated());
    assertTrue(capturedBytes(result.output()) <= 257);
  }

  @Test
  void retainsCompleteUtf8CharactersWhenTailStartsInsideMultibyteText() {
    ProcessRequest request =
        new ProcessRequest(command("unicode"), root, Duration.ofSeconds(10), 15);
    ProcessResult.Exited result =
        assertInstanceOf(ProcessResult.Exited.class, new BoundedProcessRunner().run(request));
    assertTrue(result.output().stdout().endsWith("я\n"));
    assertFalse(result.output().stdout().contains("\ufffd"));
    assertTrue(capturedBytes(result.output()) <= 15);
    assertTrue(result.output().stdoutTruncated());
  }

  @Test
  void timeoutTerminatesParentAndOwnedChildWithNoSleepingFixtures() throws IOException {
    try (WatchService ready = root.getFileSystem().newWatchService()) {
      root.register(ready, StandardWatchEventKinds.ENTRY_CREATE);
      Path pidFile = root.resolve("child.pid");
      AtomicReference<Process> parent = new AtomicReference<>();
      AtomicReference<ProcessHandle> child = new AtomicReference<>();
      AtomicLong clock = new AtomicLong();
      BoundedProcessRunner runner =
          new BoundedProcessRunner(
              builder -> {
                Process process = builder.start();
                parent.set(process);
                return process;
              },
              (process, nanos) -> {
                child.set(awaitChild(ready, pidFile));
                clock.set(Duration.ofSeconds(10).toNanos());
                return false;
              },
              clock::get);
      ProcessResult.TimedOut result =
          assertInstanceOf(
              ProcessResult.TimedOut.class, runner.run(request("child", pidFile.toString())));

      assertEquals(ProcessResult.Cleanup.COMPLETE, result.cleanup());
      assertFalse(Objects.requireNonNull(parent.get()).isAlive());
      assertFalse(Objects.requireNonNull(child.get()).isAlive());
    }
  }

  @Test
  void interruptionTerminatesTreeAndRestoresCallerInterruptFlag() throws IOException {
    try (WatchService ready = root.getFileSystem().newWatchService()) {
      root.register(ready, StandardWatchEventKinds.ENTRY_CREATE);
      Path pidFile = root.resolve("child.pid");
      AtomicReference<Process> parent = new AtomicReference<>();
      AtomicReference<ProcessHandle> child = new AtomicReference<>();
      BoundedProcessRunner runner =
          new BoundedProcessRunner(
              builder -> {
                Process process = builder.start();
                parent.set(process);
                return process;
              },
              (process, nanos) -> {
                child.set(awaitChild(ready, pidFile));
                throw new InterruptedException("Fixture cancellation");
              });
      try {
        ProcessResult.Interrupted result =
            assertInstanceOf(
                ProcessResult.Interrupted.class, runner.run(request("child", pidFile.toString())));
        assertEquals(ProcessResult.Cleanup.COMPLETE, result.cleanup());
        assertTrue(Thread.currentThread().isInterrupted());
        assertFalse(Objects.requireNonNull(parent.get()).isAlive());
        assertFalse(Objects.requireNonNull(child.get()).isAlive());
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  void preexistingInterruptionDoesNotLaunchAProcess() {
    BoundedProcessRunner runner =
        new BoundedProcessRunner(
            builder -> {
              throw new AssertionError("Cancelled request was launched");
            },
            (process, nanos) -> process.waitFor(nanos, TimeUnit.NANOSECONDS));
    Thread.currentThread().interrupt();
    try {
      assertInstanceOf(ProcessResult.Interrupted.class, runner.run(request("echo", "unused")));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void realDeadlineReturnsTypedTimeout() {
    ProcessRequest request = new ProcessRequest(command("hold"), root, Duration.ofMillis(100), 256);
    assertInstanceOf(ProcessResult.TimedOut.class, new BoundedProcessRunner().run(request));
  }

  @Test
  void missingExecutableReturnsTypedUnavailableWithoutLeakingLaunchException() {
    ProcessRequest request =
        new ProcessRequest(
            List.of(root.resolve("missing-executable").toString()),
            root,
            Duration.ofSeconds(1),
            256);
    ProcessResult.Failed failure =
        assertInstanceOf(ProcessResult.Failed.class, new BoundedProcessRunner().run(request));
    assertEquals(ProcessResult.FailureReason.UNAVAILABLE, failure.reason());
    assertFalse(failure.diagnostic().contains(root.toString()));
  }

  @Test
  void launchFailureDuringCancellationReturnsInterruptedAndRestoresFlag() {
    BoundedProcessRunner runner =
        new BoundedProcessRunner(
            builder -> {
              Thread.currentThread().interrupt();
              throw new IOException("Cancelled launch SECRET_TOKEN=untrusted");
            },
            (process, nanos) -> process.waitFor(nanos, TimeUnit.NANOSECONDS));
    try {
      assertInstanceOf(ProcessResult.Interrupted.class, runner.run(request("failure")));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void enumerationProviderFailureStillTerminatesParentAndDrains(boolean cancellation) {
    AtomicReference<Process> parent = new AtomicReference<>();
    BoundedProcessRunner runner =
        new BoundedProcessRunner(
            builder -> {
              Process process = builder.start();
              parent.set(process);
              return new EnumerationFailureProcess(process, cancellation);
            },
            (process, nanos) -> process.waitFor(nanos, TimeUnit.NANOSECONDS));
    try {
      ProcessResult result = runner.run(request("hold"));
      if (cancellation) {
        ProcessResult.Interrupted interrupted =
            assertInstanceOf(ProcessResult.Interrupted.class, result);
        assertEquals(ProcessResult.Cleanup.INCOMPLETE, interrupted.cleanup());
        assertTrue(Thread.currentThread().isInterrupted());
      } else {
        ProcessResult.Failed failed = assertInstanceOf(ProcessResult.Failed.class, result);
        assertEquals(ProcessResult.FailureReason.CLEANUP_FAILURE, failed.reason());
        assertFalse(failed.diagnostic().contains("SECRET_TOKEN"));
      }
      assertFalse(Objects.requireNonNull(parent.get()).isAlive());
      assertFalse(
          Thread.getAllStackTraces().keySet().stream()
              .anyMatch(thread -> thread.isAlive() && thread.getName().startsWith("faf-process-")));
    } finally {
      Thread.interrupted();
      Process process = parent.get();
      if (process != null) {
        process.destroyForcibly();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"echo", "failure", "flood"})
  void emitsLfForSuccessErrorExitAndTruncatedTailsWithWindowsJvmSeparator(String mode) {
    List<String> arguments = new ArrayList<>(command(mode, "literal"));
    arguments.add(1, "-Dline.separator=\r\n");
    ProcessRequest request =
        new ProcessRequest(
            arguments, root, Duration.ofSeconds(10), mode.equals("flood") ? 257 : 4096);
    ProcessResult.Exited result =
        assertInstanceOf(ProcessResult.Exited.class, new BoundedProcessRunner().run(request));
    assertEquals(mode.equals("failure") ? 23 : 0, result.exitCode());
    switch (mode) {
      case "echo" -> {
        assertEquals(root + "\nliteral\n", result.output().stdout());
        assertEquals("stderr message\n", result.output().stderr());
      }
      case "failure" -> {
        assertEquals("useful stdout\n", result.output().stdout());
        assertEquals("useful stderr\n", result.output().stderr());
      }
      case "flood" -> {
        assertTrue(result.output().stdout().endsWith("stdout useful tail\n"));
        assertTrue(result.output().stderr().endsWith("stderr useful tail\n"));
        assertTrue(result.output().stdoutTruncated());
        assertTrue(result.output().stderrTruncated());
      }
      default -> throw new AssertionError("Unexpected fixture mode");
    }
    assertFalse(result.output().stdout().contains("\r"));
    assertFalse(result.output().stderr().contains("\r"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "file", "symlink", "symlink-ancestor"})
  void rejectsUnsafeWorkingDirectoryBeforeLaunching(String kind) throws IOException {
    Path outside = Files.createDirectory(temporary.toRealPath().resolve("outside"));
    Path unsafe = root.resolve("unsafe");
    switch (kind) {
      case "file" -> Files.writeString(unsafe, "keep");
      case "symlink" -> Files.createSymbolicLink(unsafe, outside);
      case "symlink-ancestor" -> {
        Files.createSymbolicLink(unsafe, outside);
        Files.createDirectory(outside.resolve("nested"));
        unsafe = unsafe.resolve("nested");
      }
      default -> {}
    }
    BoundedProcessRunner runner =
        new BoundedProcessRunner(
            builder -> {
              throw new AssertionError("Unsafe directory reached launch");
            },
            (process, nanos) -> process.waitFor(nanos, TimeUnit.NANOSECONDS));
    ProcessRequest request =
        new ProcessRequest(command("echo", "unused"), unsafe, Duration.ofSeconds(1), 256);
    ProcessResult.Failed failure =
        assertInstanceOf(ProcessResult.Failed.class, runner.run(request));
    assertEquals(ProcessResult.FailureReason.INVALID_WORKING_DIRECTORY, failure.reason());
    assertTrue(Files.isDirectory(outside));
  }

  @Test
  void normalizesWorkingDirectoryAndCopiesArgumentsBeforeExecution() {
    List<String> arguments = new ArrayList<>(command("echo", "original"));
    ProcessRequest request =
        new ProcessRequest(arguments, root.resolve("unused/.."), Duration.ofSeconds(10), 4096);
    arguments.set(arguments.size() - 1, "changed");
    ProcessResult.Exited result =
        assertInstanceOf(ProcessResult.Exited.class, new BoundedProcessRunner().run(request));
    assertEquals(root + "\noriginal\n", result.output().stdout());
  }

  @Test
  void rejectsUnboundedOrInvalidRequests() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProcessRequest(List.of(), root, Duration.ofSeconds(1), 256));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProcessRequest(List.of("java", "bad\u0000arg"), root, Duration.ofSeconds(1), 256));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProcessRequest(command("echo"), root, Duration.ZERO, 256));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProcessRequest(command("echo"), root, Duration.ofSeconds(1), 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProcessRequest(command("echo"), root, Duration.ofDays(2), 256));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProcessRequest(command("echo"), root, Duration.ofSeconds(1), Integer.MAX_VALUE));
  }

  @Test
  void repeatedRunsDoNotLeaveOwnedDrainThreads() {
    BoundedProcessRunner runner = new BoundedProcessRunner();
    for (int index = 0; index < 3; index++) {
      assertInstanceOf(ProcessResult.Exited.class, runner.run(request("failure")));
    }
    assertFalse(
        Thread.getAllStackTraces().keySet().stream()
            .anyMatch(thread -> thread.isAlive() && thread.getName().startsWith("faf-process-")));
  }

  private ProcessRequest request(String... arguments) {
    return new ProcessRequest(command(arguments), root, Duration.ofSeconds(10), 4096);
  }

  private static List<String> command(String... arguments) {
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-cp");
    command.add(
        Path.of(
                URI.create(
                    ProcessFixture.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toExternalForm()))
            .toString());
    command.add(ProcessFixture.class.getName());
    command.addAll(List.of(arguments));
    return command;
  }

  private static ProcessHandle awaitChild(WatchService ready, Path pidFile)
      throws IOException, InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!Files.exists(pidFile)) {
      var notification =
          ready.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      if (notification == null || System.nanoTime() >= deadline) {
        throw new IOException("Process fixture did not publish its child PID");
      }
      notification.pollEvents();
      notification.reset();
    }
    return ProcessHandle.of(Long.parseLong(Files.readString(pidFile))).orElseThrow();
  }

  private static int capturedBytes(ProcessResult.Output output) {
    return output.stdout().getBytes(StandardCharsets.UTF_8).length
        + output.stderr().getBytes(StandardCharsets.UTF_8).length;
  }

  private static final class EnumerationFailureProcess extends Process {
    private final Process delegate;
    private final boolean cancellation;

    EnumerationFailureProcess(Process delegate, boolean cancellation) {
      this.delegate = delegate;
      this.cancellation = cancellation;
    }

    @Override
    public Stream<ProcessHandle> descendants() {
      if (cancellation) {
        Thread.currentThread().interrupt();
      }
      throw new IllegalStateException("sysctl failed SECRET_TOKEN=fake");
    }

    @Override
    public InputStream getInputStream() {
      return delegate.getInputStream();
    }

    @Override
    public InputStream getErrorStream() {
      return delegate.getErrorStream();
    }

    @Override
    public OutputStream getOutputStream() {
      return delegate.getOutputStream();
    }

    @Override
    public int waitFor() throws InterruptedException {
      return delegate.waitFor();
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
      return delegate.waitFor(timeout, unit);
    }

    @Override
    public int exitValue() {
      return delegate.exitValue();
    }

    @Override
    public boolean isAlive() {
      return delegate.isAlive();
    }

    @Override
    public void destroy() {
      delegate.destroy();
    }

    @Override
    public Process destroyForcibly() {
      delegate.destroyForcibly();
      return this;
    }
  }
}
