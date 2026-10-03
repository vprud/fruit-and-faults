package org.fruitandfaults.git.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import org.fruitandfaults.course.infra.LearnerJourneyFixture;
import org.fruitandfaults.git.application.GitInitializationException;
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
    FakeProcess process = new FakeProcess(root.resolve(".git") + "\n" + root + "\ntrue\n", 0);
    ProcessGitRepository git =
        new ProcessGitRepository(
            Duration.ofSeconds(1),
            4096,
            command -> {
              assertEquals(
                  List.of(
                      "git",
                      "-C",
                      root.toString(),
                      "rev-parse",
                      "--absolute-git-dir",
                      "--show-toplevel",
                      "--is-inside-work-tree"),
                  command);
              return process;
            });
    git.requireInitialized(root);
    assertTrue(process.closed);
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
