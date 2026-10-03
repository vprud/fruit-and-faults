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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.fruitandfaults.course.infra.LearnerJourneyFixture;
import org.fruitandfaults.git.application.GitInitializationException;
import org.fruitandfaults.git.application.GitStatus;
import org.fruitandfaults.validation.application.ProcessResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProcessGitStatusTest {
  @TempDir private Path temporary;
  private Path root;
  private final ProcessGitRepository git = new ProcessGitRepository();

  @BeforeEach
  void initializeSelectedRepository() throws IOException {
    root = Files.createDirectory(temporary.toRealPath().resolve("Игра with spaces"));
    git.initialize(root);
  }

  @Test
  void boundedRunnerTimeoutAndInterruptionRemainExplicitGitFailures() {
    for (boolean interrupt : new boolean[] {false, true}) {
      ProcessGitRepository guarded =
          new ProcessGitRepository(
              Duration.ofSeconds(1),
              4096,
              command -> new CompletedProcess(root.resolve(".git") + "\n" + root + "\ntrue\n", 0),
              request ->
                  interrupt
                      ? new ProcessResult.Interrupted(
                          ProcessResult.Output.empty(), ProcessResult.Cleanup.COMPLETE)
                      : new ProcessResult.TimedOut(
                          ProcessResult.Output.empty(), ProcessResult.Cleanup.COMPLETE));
      try {
        GitInitializationException failed =
            assertThrows(GitInitializationException.class, () -> guarded.status(root));
        assertEquals(
            interrupt
                ? GitInitializationException.Reason.INTERRUPTED
                : GitInitializationException.Reason.TIMEOUT,
            failed.reason());
        assertEquals(interrupt, Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  void trackedSubmoduleDirtCannotBeHiddenByInspection() throws IOException {
    Files.writeString(root.resolve("file.txt"), "first");
    Path nested = Files.createDirectory(root.resolve("nested-repository"));
    git.initialize(nested);
    Files.writeString(nested.resolve("file.txt"), "inside");
    LearnerJourneyFixture.runGit(nested, "add", "file.txt");
    LearnerJourneyFixture.runGit(
        nested,
        "-c",
        "user.name=Learner",
        "-c",
        "user.email=learner@example.invalid",
        "commit",
        "-qm",
        "test: nested fixture");
    LearnerJourneyFixture.runGit(root, "add", "nested-repository");
    commit();
    assertThrows(IOException.class, () -> git.status(root));
    Files.writeString(nested.resolve("file.txt"), "changed");
    assertThrows(IOException.class, () -> git.status(root));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void configuredContentFiltersAreRejectedBeforeGitCanExecuteThem(boolean inSubmodule)
      throws IOException {
    Path repository = root;
    Files.writeString(root.resolve("file.txt"), "first\n");
    if (inSubmodule) {
      repository = Files.createDirectory(root.resolve("nested-repository"));
      git.initialize(repository);
      Files.writeString(repository.resolve("file.txt"), "inside\n");
      LearnerJourneyFixture.runGit(repository, "add", "file.txt");
      LearnerJourneyFixture.runGit(
          repository,
          "-c",
          "user.name=Learner",
          "-c",
          "user.email=learner@example.invalid",
          "commit",
          "-qm",
          "test: nested fixture");
      LearnerJourneyFixture.runGit(root, "add", "nested-repository");
    }
    commit();
    Path marker = temporary.resolve("FILTER-WAS-EXECUTED");
    boolean windows = System.getProperty("os.name").startsWith("Windows");
    Path script = temporary.resolve(windows ? "record-filter.cmd" : "record-filter.sh");
    Files.writeString(
        script,
        windows
            ? "@echo off\r\n>\"" + marker + "\" echo executed\r\nmore\r\n"
            : "#!/bin/sh\nprintf executed > '" + marker + "'\ncat\n");
    if (!windows) {
      script.toFile().setExecutable(true);
    }
    Files.writeString(repository.resolve(".gitattributes"), "*.txt filter=evil\n");
    LearnerJourneyFixture.runGit(repository, "add", ".gitattributes");
    Files.delete(repository.resolve(".gitattributes"));
    Files.writeString(
        repository.resolve(".git/config"),
        "\n[filter \"evil\"] clean = \"" + script.toString().replace("\\", "\\\\") + "\"\n",
        java.nio.file.StandardOpenOption.APPEND);
    Files.writeString(repository.resolve("file.txt"), "changed\n");

    assertThrows(IOException.class, () -> git.status(root));
    assertFalse(Files.exists(marker));
  }

  @Test
  void nestedGitdirFileIsRejectedBeforeAnyReadOnlyGitLaunch() throws IOException {
    Path nested = Files.createDirectory(root.resolve("nested"));
    Files.writeString(nested.resolve(".git"), "gitdir: " + temporary.resolve("outside") + "\n");
    ProcessGitRepository guarded =
        new ProcessGitRepository(
            Duration.ofSeconds(1),
            4096,
            command -> {
              throw new AssertionError("Nested Git metadata reached initialization launcher");
            },
            request -> {
              throw new AssertionError("Nested Git metadata reached read-only Git launch");
            });

    assertThrows(IOException.class, () -> guarded.status(root));
  }

  @Test
  void preexistingInterruptionPreventsAnyGitLaunchAndRetainsTypedCancellation() {
    ProcessGitRepository guarded =
        new ProcessGitRepository(
            Duration.ofSeconds(1),
            4096,
            command -> {
              throw new AssertionError("Cancelled status launched Git");
            });
    Thread.currentThread().interrupt();
    try {
      GitInitializationException failed =
          assertThrows(GitInitializationException.class, () -> guarded.status(root));
      assertEquals(GitInitializationException.Reason.INTERRUPTED, failed.reason());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void distinguishesUnbornHeadFirstCommitTrackedAndUntrackedChanges() throws IOException {
    GitStatus unborn = git.status(root);
    assertTrue(unborn.headRevision().isEmpty());
    assertEquals(0, unborn.trackedChanges());
    Files.writeString(root.resolve("file.txt"), "first\r\n");
    assertEquals(1, git.status(root).untrackedChanges());
    commit();
    GitStatus committed = git.status(root);
    assertTrue(committed.headRevision().isPresent());
    assertEquals(0, committed.trackedChanges());
    assertEquals(0, committed.untrackedChanges());
    Files.writeString(root.resolve("file.txt"), "second\n");
    assertEquals(1, git.status(root).trackedChanges());
    commit();
    assertFalse(committed.headRevision().equals(git.status(root).headRevision()));
  }

  @Test
  void reportsLocalPublicationAdviceWithoutContactingOrChangingRemote() throws IOException {
    Files.writeString(root.resolve("file.txt"), "first");
    commit();
    assertFalse(git.status(root).originPresent());
    String remote = "https://user:SECRET@invalid.invalid/never-contact.git";
    LearnerJourneyFixture.runGit(root, "remote", "add", "origin", remote);
    GitStatus noUpstream = git.status(root);
    assertTrue(noUpstream.originPresent());
    assertFalse(noUpstream.upstreamPresent());
    assertFalse(noUpstream.toString().contains("SECRET"));
    String branch = LearnerJourneyFixture.runGit(root, "symbolic-ref", "--short", "HEAD").strip();
    LearnerJourneyFixture.runGit(root, "config", "branch." + branch + ".remote", "origin");
    LearnerJourneyFixture.runGit(root, "config", "branch." + branch + ".merge", "refs/heads/main");
    LearnerJourneyFixture.runGit(
        root, "update-ref", "refs/remotes/origin/main", noUpstream.headRevision().orElseThrow());
    byte[] configuration = Files.readAllBytes(root.resolve(".git/config"));
    String revision = noUpstream.headRevision().orElseThrow();
    assertTrue(git.status(root).upstreamPresent());
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        configuration, Files.readAllBytes(root.resolve(".git/config")));
    assertEquals(revision, git.status(root).headRevision().orElseThrow());
  }

  @Test
  void requiresExactSafeRepositoryBeforeStatusProcessLaunch() throws IOException {
    Path nested = Files.createDirectory(root.resolve("nested"));
    assertThrows(IOException.class, () -> git.status(nested));
    Files.writeString(root.resolve(".git/config"), "[include]\npath = /foreign/config\n");
    ProcessGitRepository guarded =
        new ProcessGitRepository(
            Duration.ofSeconds(1),
            4096,
            command -> {
              throw new AssertionError("unsafe repository reached Git");
            });
    assertThrows(IOException.class, () -> guarded.status(root));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        " M ../escape\0",
        "?? /private/SECRET\0",
        "?? file\nSECRET\0",
        "XX file\0",
        " M file",
        " M file\0"
      })
  void rejectsMalformedOrTruncatedPorcelainWithoutDisclosingPaths(String porcelain) {
    int limit = 4096;
    List<List<String>> commands = new ArrayList<>();
    ProcessGitRepository guarded =
        scriptedRepository(
            Duration.ofSeconds(1),
            limit,
            command -> {
              commands.add(command);
              if (command.contains("--absolute-git-dir")) {
                return new CompletedProcess(root.resolve(".git") + "\n" + root + "\ntrue\n", 0);
              }
              if (command.contains("status")) {
                return new CompletedProcess(
                    porcelain.equals(" M file\0") ? porcelain.repeat(1000) : porcelain, 0);
              }
              return new CompletedProcess("a".repeat(40) + "\n", 0);
            });
    IOException failed = assertThrows(IOException.class, () -> guarded.status(root));
    assertFalse(java.util.Objects.requireNonNull(failed.getMessage()).contains("SECRET"));
    assertTrue(
        commands.stream()
            .noneMatch(command -> command.contains("fetch") || command.contains("push")));
  }

  @Test
  void acceptsPorcelainRenameAndCrLfFactsWithoutChangingRepository() throws IOException {
    ProcessGitRepository guarded =
        scriptedRepository(
            Duration.ofSeconds(1),
            4096,
            command -> {
              if (command.contains("--absolute-git-dir")) {
                return new CompletedProcess(
                    root.resolve(".git") + "\r\n" + root + "\r\ntrue\r\n", 0);
              }
              if (command.contains("status")) {
                return new CompletedProcess("R  renamed file.txt\0old file.txt\0?? новый.txt\0", 0);
              }
              if (command.contains("get-url")) {
                return new CompletedProcess("", 2);
              }
              if (command.contains("--symbolic-full-name")) {
                return new CompletedProcess("", 128);
              }
              return new CompletedProcess("a".repeat(40) + "\r\n", 0);
            });
    GitStatus status = guarded.status(root);
    assertEquals(1, status.trackedChanges());
    assertEquals(1, status.untrackedChanges());
    assertFalse(status.originPresent());
    assertFalse(status.upstreamPresent());
  }

  private void commit() throws IOException {
    LearnerJourneyFixture.runGit(root, "add", "--", "file.txt");
    LearnerJourneyFixture.runGit(
        root,
        "-c",
        "user.name=Learner",
        "-c",
        "user.email=learner@example.invalid",
        "-c",
        "core.hooksPath=",
        "commit",
        "--quiet",
        "-m",
        "test: record local work");
  }

  private ProcessGitRepository scriptedRepository(
      Duration timeout, int limit, ProcessGitRepository.ProcessLauncher launcher) {
    return new ProcessGitRepository(
        timeout,
        limit,
        launcher,
        request -> {
          assertEquals(root, request.workingDirectory());
          assertEquals(timeout, request.timeout());
          assertEquals(limit, request.maxCapturedBytes());
          try {
            Process process = launcher.start(request.arguments());
            try (InputStream input = process.getInputStream()) {
              byte[] bytes = input.readNBytes(limit + 1);
              String text =
                  new String(
                      bytes,
                      0,
                      Math.min(limit, bytes.length),
                      java.nio.charset.StandardCharsets.UTF_8);
              return new ProcessResult.Exited(
                  process.exitValue(),
                  new ProcessResult.Output(text, "", bytes.length > limit, false));
            }
          } catch (IOException failed) {
            return new ProcessResult.Failed(
                ProcessResult.FailureReason.UNAVAILABLE,
                "fixture unavailable",
                ProcessResult.Output.empty());
          }
        });
  }

  private static final class CompletedProcess extends Process {
    private final InputStream input;
    private final int exit;

    private CompletedProcess(String text, int exit) {
      input = new ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      this.exit = exit;
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
    public boolean waitFor(long timeout, TimeUnit unit) {
      return true;
    }

    @Override
    public int exitValue() {
      return exit;
    }

    @Override
    public void destroy() {}

    @Override
    public boolean isAlive() {
      return false;
    }
  }
}
