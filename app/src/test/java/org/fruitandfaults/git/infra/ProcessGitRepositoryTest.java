package org.fruitandfaults.git.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.fruitandfaults.course.infra.LearnerJourneyFixture;
import org.fruitandfaults.git.application.GitInitializationException;
import org.fruitandfaults.validation.infra.BoundedProcessRunner;
import org.fruitandfaults.validation.infra.ProcessFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProcessGitRepositoryTest {
  @TempDir private Path temporary;
  private Path root;

  @BeforeEach
  void selectRealRoot() throws IOException {
    root = Files.createDirectory(temporary.toRealPath().resolve("Игра with spaces; $value"));
  }

  @Test
  void initializesRealRepositoryWithoutStagingCommittingOrRemoteMutation() throws IOException {
    Files.writeString(root.resolve("learner.txt"), "keep");
    new ProcessGitRepository().initialize(root);
    new ProcessGitRepository().requireInitialized(root);
    assertTrue(Files.isDirectory(root.resolve(".git")));
    assertTrue(LearnerJourneyFixture.runGit(root, "ls-files").isBlank());
    assertTrue(LearnerJourneyFixture.runGit(root, "remote").isBlank());
    assertFalse(Files.exists(root.resolve(".git/refs/heads/main")));
    assertTrue(LearnerJourneyFixture.runGit(root, "log", "--all", "--oneline").isBlank());
    assertEquals("keep", Files.readString(root.resolve("learner.txt")));
  }

  @Test
  void passesTargetAsOneArgumentAndBoundsCapturedOutput() throws IOException {
    FakeProcess process = new FakeProcess("x".repeat(10_000), 23);
    ProcessGitRepository git =
        new ProcessGitRepository(
            Duration.ofSeconds(1),
            128,
            command -> {
              assertEquals(
                  List.of("git", "-C", root.toString(), "init", "--quiet", "--template="), command);
              return process;
            });
    GitInitializationException failure =
        assertThrows(GitInitializationException.class, () -> git.initialize(root));
    assertEquals(GitInitializationException.Reason.EXIT_FAILURE, failure.reason());
    assertEquals(23, failure.exitCode().orElseThrow());
    assertEquals(128, failure.diagnostics().length());
    assertTrue(process.closed);
  }

  @Test
  void timeoutTerminatesOwnedProcessAndReportsExplicitOutcome() {
    FakeProcess process = new FakeProcess("", 0);
    process.timesOut = true;
    ProcessGitRepository git =
        new ProcessGitRepository(Duration.ofMillis(1), 128, command -> process);
    GitInitializationException failure =
        assertThrows(GitInitializationException.class, () -> git.initialize(root));
    assertEquals(GitInitializationException.Reason.TIMEOUT, failure.reason());
    assertTrue(process.destroyed);
    assertFalse(process.isAlive());
    assertTrue(process.closed);
  }

  @Test
  void interruptedWaitPreservesInterruptAndTerminatesProcess() {
    FakeProcess process = new FakeProcess("", 0);
    process.interrupts = true;
    ProcessGitRepository git =
        new ProcessGitRepository(Duration.ofSeconds(1), 128, command -> process);
    try {
      GitInitializationException failure =
          assertThrows(GitInitializationException.class, () -> git.initialize(root));
      assertEquals(GitInitializationException.Reason.INTERRUPTED, failure.reason());
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(process.destroyed);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void launchFailureDoesNotExposeEnvironmentOrLocalPathInMessage() {
    ProcessGitRepository git =
        new ProcessGitRepository(
            Duration.ofSeconds(1),
            128,
            command -> {
              throw new IOException("SECRET=token " + root);
            });
    GitInitializationException failure =
        assertThrows(GitInitializationException.class, () -> git.initialize(root));
    assertEquals(GitInitializationException.Reason.UNAVAILABLE, failure.reason());
    String message = java.util.Objects.requireNonNull(failure.getMessage());
    assertFalse(message.contains("SECRET"));
    assertFalse(message.contains(root.toString()));
  }

  @Test
  void refusesSymlinkedGitDirectoryBeforeLaunchingAnything() throws IOException {
    Path outside = Files.createDirectory(temporary.toRealPath().resolve("outside"));
    Files.createSymbolicLink(root.resolve(".git"), outside);
    ProcessGitRepository git =
        new ProcessGitRepository(
            Duration.ofSeconds(1),
            128,
            command -> {
              throw new AssertionError("Unsafe Git path reached process launch");
            });
    assertThrows(IOException.class, () -> git.initialize(root));
    try (var entries = Files.list(outside)) {
      assertEquals(0L, entries.count());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"config-symlink", "config-include", "object-alternates"})
  void rejectsRepositoryPathsThatCouldReadOutsideBeforeLaunchingGit(String invalid)
      throws IOException {
    new ProcessGitRepository().initialize(root);
    Path outside = temporary.toRealPath().resolve("outside-config");
    Files.writeString(outside, "[core]\n\tbare = false\n");
    switch (invalid) {
      case "config-symlink" -> {
        Files.move(root.resolve(".git/config"), temporary.toRealPath().resolve("retained-config"));
        Files.createSymbolicLink(root.resolve(".git/config"), outside);
      }
      case "config-include" ->
          Files.writeString(
              root.resolve(".git/config"),
              "\n[include]\n\tpath = \"" + outside + "\"\n",
              StandardOpenOption.APPEND);
      case "object-alternates" ->
          Files.writeString(root.resolve(".git/objects/info/alternates"), outside.toString());
      default -> throw new IllegalArgumentException("Unknown unsafe Git fixture");
    }
    byte[] before = Files.readAllBytes(outside);
    ProcessGitRepository git =
        new ProcessGitRepository(
            Duration.ofSeconds(1),
            4096,
            command -> {
              throw new AssertionError("Unsafe repository reached Git process launch");
            });
    assertThrows(IOException.class, () -> git.requireInitialized(root));
    org.junit.jupiter.api.Assertions.assertArrayEquals(before, Files.readAllBytes(outside));
  }

  @Test
  void repositoryProbePassesEachReadOnlyArgumentSeparately() throws IOException {
    new ProcessGitRepository().initialize(root);
    ProcessGitRepository git =
        new ProcessGitRepository(
            Duration.ofSeconds(1),
            4096,
            command -> new FakeProcess("", 0),
            request -> {
              assertEquals(
                  List.of(
                      "git",
                      "-C",
                      root.toString(),
                      "rev-parse",
                      "--absolute-git-dir",
                      "--show-toplevel",
                      "--is-inside-work-tree"),
                  request.arguments());
              return new org.fruitandfaults.validation.application.ProcessResult.Exited(
                  0,
                  new org.fruitandfaults.validation.application.ProcessResult.Output(
                      root.resolve(".git") + "\n" + root + "\ntrue\n", "", false, false));
            });
    git.requireInitialized(root);
  }

  @Test
  void firstReadOnlyProbeTimeoutTerminatesItsDescendant() throws Exception {
    new ProcessGitRepository().initialize(root);
    Path pidFile = root.resolve("child.pid");
    ProcessGitRepository git = repositoryUsingFixture(pidFile, Duration.ofMillis(250));

    IOException failed = assertThrows(IOException.class, () -> git.requireInitialized(root));

    assertEquals(
        GitInitializationException.Reason.TIMEOUT,
        ((GitInitializationException) java.util.Objects.requireNonNull(failed.getCause()))
            .reason());
    assertFalse(
        ProcessHandle.of(Long.parseLong(Files.readString(pidFile)))
            .map(ProcessHandle::isAlive)
            .orElse(false));
  }

  @Test
  void interruptedFirstReadOnlyProbeTerminatesItsDescendantAndRestoresInterrupt() throws Exception {
    new ProcessGitRepository().initialize(root);
    Path pidFile = root.resolve("child.pid");
    ProcessGitRepository git = repositoryUsingFixture(pidFile, Duration.ofSeconds(10));
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicBoolean interrupted = new AtomicBoolean();
    Thread caller =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    git.requireInitialized(root);
                  } catch (Throwable observed) {
                    failure.set(observed);
                    interrupted.set(Thread.currentThread().isInterrupted());
                  }
                });
    awaitFile(pidFile);
    ProcessHandle child = ProcessHandle.of(Long.parseLong(Files.readString(pidFile))).orElseThrow();

    caller.interrupt();
    caller.join(TimeUnit.SECONDS.toMillis(5));

    assertFalse(caller.isAlive());
    assertTrue(failure.get() instanceof IOException);
    assertEquals(
        GitInitializationException.Reason.INTERRUPTED,
        ((GitInitializationException)
                java.util.Objects.requireNonNull(
                    java.util.Objects.requireNonNull(failure.get()).getCause()))
            .reason());
    assertTrue(interrupted.get());
    assertFalse(child.isAlive());
  }

  private ProcessGitRepository repositoryUsingFixture(Path pidFile, Duration timeout) {
    BoundedProcessRunner runner =
        new BoundedProcessRunner(builder -> builder.command(fixtureCommand(pidFile)));
    return new ProcessGitRepository(timeout, 4096, command -> new FakeProcess("", 0), runner);
  }

  private static List<String> fixtureCommand(Path pidFile) {
    return List.of(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp",
        System.getProperty("java.class.path"),
        ProcessFixture.class.getName(),
        "child",
        pidFile.toString());
  }

  private static void awaitFile(Path file) throws IOException, InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (Files.notExists(file) && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    if (Files.notExists(file)) {
      throw new IOException("Process fixture did not publish its child PID");
    }
  }

  @Test
  void acceptsEquivalentCasingAliasWhenFilesystemSupportsIt() throws IOException {
    root = Files.createDirectory(temporary.toRealPath().resolve("CaseWorkspace with spaces"));
    Path alias = root.resolveSibling("caseworkspace with spaces");
    assumeTrue(
        Files.isDirectory(alias) && Files.isSameFile(root, alias),
        "This filesystem does not support equivalent casing aliases.");
    ProcessGitRepository git = new ProcessGitRepository();
    git.initialize(root);
    git.requireInitialized(alias);
    assertTrue(Files.isSameFile(root.resolve(".git"), alias.resolve(".git")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"config", "config.worktree"})
  void rejectsUtf8BomConfigurationBeforeLaunchingGit(String configuration) throws IOException {
    new ProcessGitRepository().initialize(root);
    Path outside = temporary.toRealPath().resolve("outside-config");
    Files.writeString(outside, "[core]\n\tbare = false\n");
    Path target = root.resolve(".git").resolve(configuration);
    String content = "\ufeff[include]\n\tpath = \"" + outside + "\"\n";
    Files.writeString(target, content);
    ProcessGitRepository git =
        new ProcessGitRepository(
            Duration.ofSeconds(1),
            4096,
            command -> {
              throw new AssertionError("BOM configuration reached Git process launch");
            });
    assertThrows(IOException.class, () -> git.requireInitialized(root));
    assertEquals(content, Files.readString(target));
    assertEquals("[core]\n\tbare = false\n", Files.readString(outside));
  }

  private static final class FakeProcess extends Process {
    private final InputStream input;
    private final int exit;
    private boolean timesOut;
    private boolean interrupts;
    private boolean destroyed;
    private boolean closed;
    private boolean completed;

    private FakeProcess(String output, int exit) {
      this.exit = exit;
      input =
          new ByteArrayInputStream(output.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
              closed = true;
              super.close();
            }
          };
    }

    @Override
    public OutputStream getOutputStream() {
      return new ByteArrayOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return input;
    }

    @Override
    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() {
      return exit;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
      if (interrupts) {
        interrupts = false;
        throw new InterruptedException("Injected cancellation");
      }
      completed = !timesOut || destroyed;
      return completed;
    }

    @Override
    public int exitValue() {
      return exit;
    }

    @Override
    public void destroy() {
      destroyed = true;
    }

    @Override
    public Process destroyForcibly() {
      destroyed = true;
      return this;
    }

    @Override
    public boolean isAlive() {
      return !completed && !destroyed;
    }
  }
}
