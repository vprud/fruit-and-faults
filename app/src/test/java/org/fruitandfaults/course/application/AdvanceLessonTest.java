package org.fruitandfaults.course.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.fruitandfaults.git.application.GitInitializationException;
import org.fruitandfaults.git.application.GitLessonGate;
import org.fruitandfaults.git.application.GitRepository;
import org.fruitandfaults.git.application.GitStatus;
import org.fruitandfaults.lesson.ReflectionAnswer;
import org.fruitandfaults.progress.application.ProgressRepository;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.fruitandfaults.validation.application.CheckRequest;
import org.fruitandfaults.validation.domain.CheckOutcome;
import org.fruitandfaults.validation.domain.Diagnostic;
import org.fruitandfaults.validation.domain.FailureCategory;
import org.fruitandfaults.workspace.application.DiscloseLesson;
import org.fruitandfaults.workspace.domain.ManagedFile;
import org.fruitandfaults.workspace.domain.ManagedFiles;
import org.fruitandfaults.workspace.domain.WorkspacePath;
import org.fruitandfaults.workspace.infra.JacksonTransitionJournalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdvanceLessonTest {
  @TempDir private Path temporary;
  private final List<String> events = new ArrayList<>();

  @Test
  void checksBeforeEvaluatingASuppliedAnswerAndDoesNotInspectGitOnFailure() throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    byte[] before = bytes(fixture);
    CheckOutcome failed =
        new CheckOutcome.Failed(
            FailureCategory.COMPILATION_ERROR,
            List.of(
                new Diagnostic(
                    "Compiled classes", "Compilation failed", "Fix the compiler error")));
    var result =
        assertInstanceOf(
            AdvanceResult.CheckFailed.class,
            advance(fixture, failed, ready())
                .execute(request(fixture, "compile-before-tests", true)));
    assertEquals(FailureCategory.COMPILATION_ERROR, result.outcome().category());
    assertEquals(List.of("check:first-run"), events);
    assertArrayEquals(before, bytes(fixture));
  }

  @Test
  void missingWrongAndUnknownAnswersLeaveProgressUnchangedWithoutGitInspection()
      throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    byte[] before = bytes(fixture);
    AdvanceLesson useCase = advance(fixture, passed(), ready());
    var question =
        assertInstanceOf(
            AdvanceResult.NeedsAnswer.class,
            useCase.execute(new AdvanceRequest(fixture.root(), Optional.empty(), true)));
    assertEquals("first-run", question.lessonId().value());
    assertInstanceOf(
        AdvanceResult.Incorrect.class,
        useCase.execute(
            request(
                fixture,
                fixture.course().lessons().getFirst().question().options().stream()
                    .filter(option -> !option.id().equals("compile-before-tests"))
                    .findFirst()
                    .orElseThrow()
                    .id(),
                true)));
    assertInstanceOf(
        AdvanceResult.Incorrect.class, useCase.execute(request(fixture, "SECRET\u001b[31m", true)));
    assertEquals(List.of("check:first-run", "check:first-run"), events);
    assertArrayEquals(before, bytes(fixture));
  }

  @Test
  void missingAnswerReturnsQuestionBeforeCheckingOrInspectingGit() throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    byte[] before = bytes(fixture);
    var question =
        assertInstanceOf(
            AdvanceResult.NeedsAnswer.class,
            advance(fixture, passed(), ready())
                .execute(new AdvanceRequest(fixture.root(), Optional.empty(), true)));
    assertEquals("first-run", question.lessonId().value());
    assertTrue(events.isEmpty());
    assertArrayEquals(before, bytes(fixture));
  }

  @ParameterizedTest
  @ValueSource(strings = {"unborn", "tracked", "untracked", "unchanged"})
  void gitBlocksWithoutPersistingCorrectAnswerOrDisclosingAnyFile(String blocked)
      throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    String answer = "compile-before-tests";
    if (blocked.equals("unchanged")) {
      fixture
          .progress()
          .save(
              fixture.root(),
              CourseProgress.opening(fixture.course(), null)
                  .advance(
                      fixture.course().lessonOrder().getFirst(),
                      "compile-before-tests",
                      "a".repeat(40))
                  .progress());
      fixture
          .manifests()
          .save(
              fixture.root(),
              new ManagedFiles(
                  fixture.course().lessons().subList(0, 2).stream()
                      .flatMap(
                          lesson ->
                              lesson.assets().stream()
                                  .map(
                                      asset ->
                                          new ManagedFile(
                                              WorkspacePath.parse(asset.relativePath()),
                                              asset.id(),
                                              asset.sha256(),
                                              lesson.id(),
                                              asset.policy())))
                      .toList()));
      answer = fixture.course().lessons().get(1).question().correctOptionId();
    }
    byte[] before = bytes(fixture);
    GitStatus status =
        new GitStatus(
            blocked.equals("unborn") ? Optional.empty() : Optional.of("a".repeat(40)),
            blocked.equals("tracked") ? 1 : 0,
            blocked.equals("untracked") ? 1 : 0,
            false,
            false);
    var result =
        assertInstanceOf(
            AdvanceResult.GitBlocked.class,
            advance(fixture, passed(), status).execute(request(fixture, answer, true)));
    assertEquals(
        blocked.equals("tracked") || blocked.equals("untracked")
            ? GitLessonGate.Decision.DIRTY_WORKTREE
            : GitLessonGate.Decision.MISSING_COMMIT,
        result.decision());
    assertEquals(
        List.of(
            blocked.equals("unchanged") ? "check:coordinate-direction" : "check:first-run", "git"),
        events);
    assertArrayEquals(before, bytes(fixture));
    assertTrue(
        Files.notExists(
            fixture.root().resolve("src/main/java/org/fruitandfaults/game/Coordinate.java")));
  }

  @Test
  void previewNamesExactlyNextFilesAndConfirmationPrecedesDisclosure() throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    byte[] before = bytes(fixture);
    var result =
        assertInstanceOf(
            AdvanceResult.PreviewRequired.class,
            advance(fixture, passed(), ready())
                .execute(request(fixture, "compile-before-tests", false)));
    assertEquals("coordinate-direction", result.lessonId().value());
    assertEquals(
        List.of(
            "src/main/java/org/fruitandfaults/game/Direction.java",
            "src/main/java/org/fruitandfaults/game/Coordinate.java",
            "src/test/java/org/fruitandfaults/game/CoordinateTest.java"),
        result.plan().filesToCreate().stream().map(file -> file.path().value()).toList());
    assertEquals(
        Optional.of("a".repeat(40)), result.intendedProgress().activeLessonOpenedAtRevision());
    assertTrue(result.advice().stream().anyMatch(advice -> advice.contains("origin")));
    assertArrayEquals(before, bytes(fixture));
    assertTrue(Files.notExists(fixture.root().resolve(".fruit-and-faults/transition.json")));
  }

  @Test
  void confirmsDisclosesAndCommitsAnswerTogetherWithCurrentHeadOpeningRevision()
      throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    var result =
        assertInstanceOf(
            AdvanceResult.Advanced.class,
            advance(fixture, passed(), ready())
                .execute(request(fixture, "compile-before-tests", true)));
    assertEquals("coordinate-direction", result.progress().activeLessonId().orElseThrow().value());
    assertEquals(
        Optional.of("compile-before-tests"),
        result.progress().lessons().getFirst().completedOptionId());
    assertEquals(Optional.of("a".repeat(40)), result.progress().activeLessonOpenedAtRevision());
    assertEquals(Optional.of(result.progress()), fixture.progress().load(fixture.root()));
    assertTrue(
        Files.exists(
            fixture.root().resolve("src/main/java/org/fruitandfaults/game/Coordinate.java")));
    assertTrue(Files.notExists(fixture.root().resolve(".fruit-and-faults/transition.json")));
  }

  @Test
  void targetConflictStopsBeforeJournalOrAnswerPersistence() throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    Path target = fixture.root().resolve("src/main/java/org/fruitandfaults/game/Coordinate.java");
    Files.writeString(target, "learner-owned bytes");
    byte[] before = bytes(fixture);
    var result =
        assertInstanceOf(
            AdvanceResult.Conflict.class,
            advance(fixture, passed(), ready())
                .execute(request(fixture, "compile-before-tests", true)));
    assertFalse(result.paths().isEmpty());
    assertEquals("learner-owned bytes", Files.readString(target));
    assertArrayEquals(before, bytes(fixture));
    assertTrue(Files.notExists(fixture.root().resolve(".fruit-and-faults/transition.json")));
  }

  @Test
  void firstLessonRequiresHeadRatherThanACommitAfterAnOptionalOpeningRevision() throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    fixture
        .progress()
        .save(fixture.root(), CourseProgress.opening(fixture.course(), "a".repeat(40)));
    assertInstanceOf(
        AdvanceResult.Advanced.class,
        advance(fixture, passed(), ready())
            .execute(request(fixture, "compile-before-tests", true)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"TIMEOUT", "INTERRUPTED", "UNAVAILABLE", "EXIT_FAILURE"})
  void gitStatusFailureHasSafeTypedOutcomeAndNoWrite(String reason) throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    var journals = new JacksonTransitionJournalRepository(fixture.course());
    GitRepository git =
        new GitRepository() {
          @Override
          public void initialize(Path root) {
            throw new AssertionError();
          }

          @Override
          public void requireInitialized(Path root) {}

          @Override
          public GitStatus status(Path root) throws IOException {
            throw new GitInitializationException(
                GitInitializationException.Reason.valueOf(reason), OptionalInt.empty(), "SECRET");
          }
        };
    byte[] before = bytes(fixture);
    var useCase =
        new AdvanceLesson(
            fixture.catalog(),
            fixture.progress(),
            fixture.manifests(),
            journals,
            request -> passed(),
            git,
            new DiscloseLesson(
                fixture.catalog(),
                fixture.files(),
                fixture.manifests(),
                fixture.progress(),
                journals));
    var failed =
        assertInstanceOf(
            AdvanceResult.Unavailable.class,
            useCase.execute(request(fixture, "compile-before-tests", true)));
    assertEquals(
        reason.equals("TIMEOUT")
            ? FailureCategory.TIMEOUT
            : reason.equals("INTERRUPTED")
                ? FailureCategory.INTERRUPTED
                : FailureCategory.WORKSPACE_CONFLICT,
        failed.category());
    assertFalse(failed.toString().contains("SECRET"));
    assertArrayEquals(before, bytes(fixture));
  }

  @Test
  void finalLessonRequiresConfirmationAndAtomicallyRecordsTerminalAnswerWithMetadataCommitAdvice()
      throws IOException {
    var fixture = finalLesson();
    byte[] before = bytes(fixture);
    var active = fixture.course().lessons().getLast();
    var useCase = advance(fixture, passed(), ready());
    var preview =
        assertInstanceOf(
            AdvanceResult.PreviewRequired.class,
            useCase.execute(request(fixture, active.question().correctOptionId(), false)));
    assertTrue(preview.plan().filesToCreate().isEmpty());
    assertTrue(preview.intendedProgress().activeLessonId().isEmpty());
    assertArrayEquals(before, bytes(fixture));
    var complete =
        assertInstanceOf(
            AdvanceResult.CourseComplete.class,
            useCase.execute(request(fixture, active.question().correctOptionId(), true)));
    assertTrue(complete.progress().activeLessonId().isEmpty());
    assertTrue(complete.progress().activeLessonOpenedAtRevision().isEmpty());
    assertTrue(
        complete.advice().stream().anyMatch(value -> value.contains("final metadata commit")));
    assertEquals(Optional.of(complete.progress()), fixture.progress().load(fixture.root()));
    byte[] committed = bytes(fixture);
    assertInstanceOf(
        AdvanceResult.CourseComplete.class,
        useCase.execute(new AdvanceRequest(fixture.root(), Optional.empty(), true)));
    assertArrayEquals(committed, bytes(fixture));
  }

  @Test
  void finalPersistenceFailureKeepsAnswerAndProgressUnchanged() throws IOException {
    var fixture = finalLesson();
    byte[] before = bytes(fixture);
    ProgressRepository failing =
        new ProgressRepository() {
          @Override
          public Optional<CourseProgress> load(Path root) throws IOException {
            return fixture.progress().load(root);
          }

          @Override
          public void save(Path root, CourseProgress state) throws IOException {
            throw new IOException("SECRET");
          }
        };
    var journals = new JacksonTransitionJournalRepository(fixture.course());
    var useCase =
        new AdvanceLesson(
            fixture.catalog(),
            failing,
            fixture.manifests(),
            journals,
            request -> passed(),
            new GitRepository() {
              @Override
              public void initialize(Path root) {
                throw new AssertionError();
              }

              @Override
              public void requireInitialized(Path root) {}

              @Override
              public GitStatus status(Path root) {
                return ready();
              }
            },
            new DiscloseLesson(
                fixture.catalog(), fixture.files(), fixture.manifests(), failing, journals));
    assertInstanceOf(
        AdvanceResult.Unavailable.class,
        useCase.execute(
            request(
                fixture, fixture.course().lessons().getLast().question().correctOptionId(), true)));
    assertArrayEquals(before, bytes(fixture));
  }

  @Test
  void realDirtyRepositoryDoesNotPersistAcceptedAnswer() throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    org.fruitandfaults.course.infra.LearnerJourneyFixture.runGit(fixture.root(), "add", ".");
    org.fruitandfaults.course.infra.LearnerJourneyFixture.runGit(
        fixture.root(),
        "-c",
        "user.name=Learner",
        "-c",
        "user.email=learner@example.invalid",
        "commit",
        "-qm",
        "fix: first lesson");
    Files.writeString(
        fixture.root().resolve("src/main/java/org/fruitandfaults/game/Starter.java"),
        "learner change after commit");
    byte[] before = bytes(fixture);
    var journals = new JacksonTransitionJournalRepository(fixture.course());
    var useCase =
        new AdvanceLesson(
            fixture.catalog(),
            fixture.progress(),
            fixture.manifests(),
            journals,
            request -> passed(),
            fixture.git(),
            new DiscloseLesson(
                fixture.catalog(),
                fixture.files(),
                fixture.manifests(),
                fixture.progress(),
                journals));
    assertEquals(
        GitLessonGate.Decision.DIRTY_WORKTREE,
        assertInstanceOf(
                AdvanceResult.GitBlocked.class,
                useCase.execute(request(fixture, "compile-before-tests", true)))
            .decision());
    assertArrayEquals(before, bytes(fixture));
  }

  @ParameterizedTest
  @ValueSource(strings = {"head", "progress", "manifest"})
  void concurrentStateChangeBeforeDisclosureIsPreservedInsteadOfOverwritten(String changed)
      throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    var journals = new JacksonTransitionJournalRepository(fixture.course());
    byte[] before = bytes(fixture);
    java.util.concurrent.atomic.AtomicInteger statuses =
        new java.util.concurrent.atomic.AtomicInteger();
    GitRepository git =
        new GitRepository() {
          @Override
          public void initialize(Path root) {
            throw new AssertionError();
          }

          @Override
          public void requireInitialized(Path root) {}

          @Override
          public GitStatus status(Path root) throws IOException {
            if (statuses.incrementAndGet() == 2) {
              if (changed.equals("progress"))
                fixture
                    .progress()
                    .save(
                        root,
                        CourseProgress.opening(fixture.course(), null)
                            .revealHint(fixture.course().lessonOrder().getFirst()));
              if (changed.equals("manifest")) fixture.manifests().save(root, ManagedFiles.empty());
              if (changed.equals("head"))
                return new GitStatus(Optional.of("c".repeat(40)), 0, 0, false, false);
            }
            return ready();
          }
        };
    var useCase =
        new AdvanceLesson(
            fixture.catalog(),
            fixture.progress(),
            fixture.manifests(),
            journals,
            request -> passed(),
            git,
            new DiscloseLesson(
                fixture.catalog(),
                fixture.files(),
                fixture.manifests(),
                fixture.progress(),
                journals));
    assertInstanceOf(
        AdvanceResult.Conflict.class,
        useCase.execute(request(fixture, "compile-before-tests", true)));
    assertTrue(journals.load(fixture.root()).isEmpty());
    if (!changed.equals("progress")) assertArrayEquals(before, bytes(fixture));
    else
      assertEquals(
          1,
          fixture.progress().load(fixture.root()).orElseThrow().lessons().getFirst().hintLevel());
    assertTrue(
        Files.notExists(
            fixture.root().resolve("src/main/java/org/fruitandfaults/game/Coordinate.java")));
  }

  @Test
  void preexistingCancellationStopsBeforeValidationAndKeepsInterrupt() throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    byte[] before = bytes(fixture);
    Thread.currentThread().interrupt();
    try {
      var failed =
          assertInstanceOf(
              AdvanceResult.Unavailable.class,
              advance(fixture, passed(), ready())
                  .execute(request(fixture, "compile-before-tests", true)));
      assertEquals(FailureCategory.INTERRUPTED, failed.category());
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(events.isEmpty());
    } finally {
      Thread.interrupted();
    }
    assertArrayEquals(before, bytes(fixture));
  }

  @Test
  void finalMetadataCommitDoesNotOverwriteHintChangedDuringLastGitInspection() throws IOException {
    var fixture = finalLesson();
    var journals = new JacksonTransitionJournalRepository(fixture.course());
    var statuses = new java.util.concurrent.atomic.AtomicInteger();
    GitRepository git =
        new GitRepository() {
          @Override
          public void initialize(Path root) {
            throw new AssertionError();
          }

          @Override
          public void requireInitialized(Path root) {}

          @Override
          public GitStatus status(Path root) throws IOException {
            if (statuses.incrementAndGet() == 2) {
              var current = fixture.progress().load(root).orElseThrow();
              fixture
                  .progress()
                  .save(root, current.revealHint(current.activeLessonId().orElseThrow()));
            }
            return ready();
          }
        };
    var useCase =
        new AdvanceLesson(
            fixture.catalog(),
            fixture.progress(),
            fixture.manifests(),
            journals,
            request -> passed(),
            git,
            new DiscloseLesson(
                fixture.catalog(),
                fixture.files(),
                fixture.manifests(),
                fixture.progress(),
                journals));
    assertInstanceOf(
        AdvanceResult.Conflict.class,
        useCase.execute(
            request(
                fixture, fixture.course().lessons().getLast().question().correctOptionId(), true)));
    var current = fixture.progress().load(fixture.root()).orElseThrow();
    assertEquals(1, current.lessons().getLast().hintLevel());
    assertTrue(current.lessons().getLast().completedOptionId().isEmpty());
  }

  private CourseApplicationFixture finalLesson() throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    CourseProgress state = CourseProgress.opening(fixture.course(), null);
    for (var lesson : fixture.course().lessons().subList(0, 3))
      state =
          state
              .advance(lesson.id(), lesson.question().correctOptionId(), "b".repeat(40))
              .progress();
    fixture.progress().save(fixture.root(), state);
    fixture
        .manifests()
        .save(
            fixture.root(),
            new ManagedFiles(
                fixture.course().lessons().stream()
                    .flatMap(
                        lesson ->
                            lesson.assets().stream()
                                .map(
                                    asset ->
                                        new ManagedFile(
                                            WorkspacePath.parse(asset.relativePath()),
                                            asset.id(),
                                            asset.sha256(),
                                            lesson.id(),
                                            asset.policy())))
                    .toList()));
    return fixture;
  }

  private AdvanceLesson advance(
      CourseApplicationFixture fixture, CheckOutcome check, GitStatus status) {
    var journals = new JacksonTransitionJournalRepository(fixture.course());
    GitRepository git =
        new GitRepository() {
          @Override
          public void initialize(Path root) {
            throw new AssertionError();
          }

          @Override
          public void requireInitialized(Path root) {}

          @Override
          public GitStatus status(Path root) {
            events.add("git");
            return status;
          }
        };
    return new AdvanceLesson(
        fixture.catalog(),
        fixture.progress(),
        fixture.manifests(),
        journals,
        (CheckRequest request) -> {
          events.add("check:" + request.activeLessonId().value());
          return check;
        },
        git,
        new DiscloseLesson(
            fixture.catalog(), fixture.files(), fixture.manifests(), fixture.progress(), journals));
  }

  private static AdvanceRequest request(
      CourseApplicationFixture fixture, String answer, boolean yes) {
    return new AdvanceRequest(fixture.root(), Optional.of(new ReflectionAnswer(answer)), yes);
  }

  private static byte[] bytes(CourseApplicationFixture fixture) throws IOException {
    return Files.readAllBytes(fixture.root().resolve(".fruit-and-faults/progress.json"));
  }

  private static GitStatus ready() {
    return new GitStatus(Optional.of("a".repeat(40)), 0, 0, false, false);
  }

  private static CheckOutcome passed() {
    return new CheckOutcome.Passed(List.of());
  }
}
