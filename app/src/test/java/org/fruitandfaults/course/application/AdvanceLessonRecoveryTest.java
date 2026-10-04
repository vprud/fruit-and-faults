package org.fruitandfaults.course.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.fruitandfaults.course.domain.Course;
import org.fruitandfaults.git.application.GitRepository;
import org.fruitandfaults.git.application.GitStatus;
import org.fruitandfaults.lesson.ReflectionAnswer;
import org.fruitandfaults.progress.application.ProgressRepository;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.fruitandfaults.progress.infra.AtomicProgressRepository;
import org.fruitandfaults.progress.infra.JacksonProgressCodec;
import org.fruitandfaults.validation.domain.CheckOutcome;
import org.fruitandfaults.workspace.application.DiscloseLesson;
import org.fruitandfaults.workspace.application.ManagedFilesRepository;
import org.fruitandfaults.workspace.application.TransitionJournalRepository;
import org.fruitandfaults.workspace.application.WorkspaceFiles;
import org.fruitandfaults.workspace.domain.DisclosurePlan;
import org.fruitandfaults.workspace.domain.ManagedFile;
import org.fruitandfaults.workspace.domain.ManagedFiles;
import org.fruitandfaults.workspace.domain.TransitionJournal;
import org.fruitandfaults.workspace.domain.WorkspacePath;
import org.fruitandfaults.workspace.infra.JacksonTransitionJournalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdvanceLessonRecoveryTest {
  @TempDir private Path temporary;

  @ParameterizedTest
  @ValueSource(strings = {"manifest", "progress"})
  void ordinaryPendingJournalNeedsItsSourceAnswerBeforeChecksGitOrMutation(String crash)
      throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    var journals = new JacksonTransitionJournalRepository(fixture.course());
    assertInstanceOf(
        AdvanceResult.Unavailable.class,
        useCase(fixture, crashing(fixture, journals, crash), journals, true)
            .execute(answer(fixture, "compile-before-tests")));
    byte[] before = bytes(fixture);
    Path journal = fixture.root().resolve(".fruit-and-faults/transition.json");
    Path manifest = fixture.root().resolve(".fruit-and-faults/managed-files.json");
    byte[] pending = Files.readAllBytes(journal);
    byte[] managed = Files.readAllBytes(manifest);
    var checks = new AtomicInteger();
    var inspections = new AtomicInteger();
    var useCase =
        new AdvanceLesson(
            fixture.catalog(),
            fixture.progress(),
            fixture.manifests(),
            journals,
            request -> {
              checks.incrementAndGet();
              return new CheckOutcome.Passed(List.of());
            },
            git("a".repeat(40), 3, () -> inspections.incrementAndGet()),
            new DiscloseLesson(
                fixture.catalog(),
                fixture.files(),
                fixture.manifests(),
                fixture.progress(),
                journals));
    var question =
        assertInstanceOf(
            AdvanceResult.NeedsAnswer.class,
            useCase.execute(new AdvanceRequest(fixture.root(), Optional.empty(), true)));
    assertEquals("first-run", question.lessonId().value());
    assertEquals(0, checks.get());
    assertEquals(0, inspections.get());
    assertArrayEquals(before, bytes(fixture));
    assertArrayEquals(pending, Files.readAllBytes(journal));
    assertArrayEquals(managed, Files.readAllBytes(manifest));
  }

  @ParameterizedTest
  @ValueSource(strings = {"journal", "asset-1", "asset-3", "manifest", "progress", "remove"})
  void repeatedNextRecoversEveryDurableCrashPointWithoutRecheckingDirtyNewLesson(String crash)
      throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    var journals = new JacksonTransitionJournalRepository(fixture.course());
    byte[] before = bytes(fixture);
    var failed =
        useCase(fixture, crashing(fixture, journals, crash), journals, true)
            .execute(answer(fixture, "compile-before-tests"));
    assertInstanceOf(AdvanceResult.Unavailable.class, failed);
    if (!crash.equals("progress") && !crash.equals("remove"))
      assertArrayEquals(before, bytes(fixture));
    assertTrue(journals.load(fixture.root()).isPresent());
    DiscloseLesson disclosure =
        new DiscloseLesson(
            fixture.catalog(), fixture.files(), fixture.manifests(), fixture.progress(), journals);
    var result =
        assertInstanceOf(
            AdvanceResult.Recovered.class,
            useCase(fixture, disclosure, journals, false)
                .execute(answer(fixture, "compile-before-tests")));
    assertEquals("coordinate-direction", result.progress().activeLessonId().orElseThrow().value());
    assertEquals(
        Optional.of("compile-before-tests"),
        result.progress().lessons().getFirst().completedOptionId());
    assertTrue(journals.load(fixture.root()).isEmpty());
    byte[] committed = bytes(fixture);
    assertInstanceOf(
        AdvanceResult.NeedsAnswer.class,
        useCase(fixture, disclosure, journals, true)
            .execute(new AdvanceRequest(fixture.root(), Optional.empty(), true)));
    assertArrayEquals(committed, bytes(fixture));
    assertEquals(13, fixture.manifests().load(fixture.root()).orElseThrow().files().size());
  }

  @ParameterizedTest
  @ValueSource(strings = {"different-answer", "edited-asset", "changed-head", "no-confirmation"})
  void pendingTransitionRejectsMismatchAndPreservesPartialState(String mismatch)
      throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    var journals = new JacksonTransitionJournalRepository(fixture.course());
    useCase(fixture, crashing(fixture, journals, "asset-1"), journals, true)
        .execute(answer(fixture, "compile-before-tests"));
    Path asset = fixture.root().resolve("src/main/java/org/fruitandfaults/game/Direction.java");
    if (mismatch.equals("edited-asset")) Files.writeString(asset, "learner edit");
    byte[] oldProgress = bytes(fixture);
    byte[] oldJournal =
        Files.readAllBytes(fixture.root().resolve(".fruit-and-faults/transition.json"));
    byte[] oldAsset = Files.readAllBytes(asset);
    var disclosure =
        new DiscloseLesson(
            fixture.catalog(), fixture.files(), fixture.manifests(), fixture.progress(), journals);
    var useCase =
        new AdvanceLesson(
            fixture.catalog(),
            fixture.progress(),
            fixture.manifests(),
            journals,
            request -> {
              throw new AssertionError("Recovery must not check partial new work");
            },
            git(mismatch.equals("changed-head") ? "b".repeat(40) : "a".repeat(40), 3),
            disclosure);
    AdvanceRequest request =
        new AdvanceRequest(
            fixture.root(),
            mismatch.equals("different-answer")
                ? Optional.of(new ReflectionAnswer("unknown"))
                : Optional.of(new ReflectionAnswer("compile-before-tests")),
            !mismatch.equals("no-confirmation"));
    if (mismatch.equals("no-confirmation"))
      assertInstanceOf(AdvanceResult.PreviewRequired.class, useCase.execute(request));
    else assertInstanceOf(AdvanceResult.Conflict.class, useCase.execute(request));
    assertArrayEquals(oldProgress, bytes(fixture));
    assertArrayEquals(
        oldJournal,
        Files.readAllBytes(fixture.root().resolve(".fruit-and-faults/transition.json")));
    assertArrayEquals(oldAsset, Files.readAllBytes(asset));
    assertTrue(
        Files.notExists(
            fixture.root().resolve("src/main/java/org/fruitandfaults/game/Coordinate.java")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"none", "journal", "asset-1", "manifest", "progress"})
  void
      trustedAppendContinuesCompletedOldRouteWithoutRewritingLearnerFilesOrRevalidatingCompletedWork(
          String crash) throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    Course older =
        new Course(
            fixture.course().id(),
            1,
            fixture.course().title(),
            fixture.course().lessonOrder().subList(0, 1),
            fixture.course().lessons().subList(0, 1));
    Course newer =
        new Course(
            fixture.course().id(),
            2,
            fixture.course().title(),
            fixture.course().lessonOrder(),
            fixture.course().lessons());
    CourseProgress terminal =
        CourseProgress.opening(older, null)
            .advance(older.lessonOrder().getFirst(), "compile-before-tests", null)
            .progress();
    Files.delete(fixture.root().resolve(".fruit-and-faults/progress.json"));
    new AtomicProgressRepository(new JacksonProgressCodec(older)).save(fixture.root(), terminal);
    var codec = new JacksonProgressCodec(newer, List.of(older));
    var progress = new AtomicProgressRepository(codec);
    var journals = new JacksonTransitionJournalRepository(codec);
    var disclosure =
        new DiscloseLesson(
            fixture.catalog(), fixture.files(), fixture.manifests(), progress, journals);
    Path starter = fixture.root().resolve("src/main/java/org/fruitandfaults/game/Starter.java");
    Files.writeString(starter, "preserved learner implementation");
    byte[] before = Files.readAllBytes(starter);
    var status =
        assertInstanceOf(
            CourseStatus.Ready.class,
            new ShowStatus(
                    () -> newer,
                    progress,
                    fixture.manifests(),
                    fixture.files(),
                    git("a".repeat(40), 0))
                .execute(fixture.root()));
    assertTrue(status.continuationAvailable());
    var useCase =
        new AdvanceLesson(
            () -> newer,
            progress,
            fixture.manifests(),
            journals,
            request -> {
              throw new AssertionError("Completed contracts must not be revalidated");
            },
            git("a".repeat(40), 0),
            disclosure);
    byte[] saved = bytes(fixture);
    assertInstanceOf(
        AdvanceResult.PreviewRequired.class,
        useCase.execute(new AdvanceRequest(fixture.root(), Optional.empty(), false)));
    assertArrayEquals(saved, bytes(fixture));
    CourseProgress advanced;
    if (crash.equals("none")) {
      advanced =
          assertInstanceOf(
                  AdvanceResult.Advanced.class,
                  useCase.execute(new AdvanceRequest(fixture.root(), Optional.empty(), true)))
              .progress();
    } else {
      var crashing =
          new AdvanceLesson(
              () -> newer,
              progress,
              fixture.manifests(),
              journals,
              request -> {
                throw new AssertionError();
              },
              git("a".repeat(40), 0),
              crashing(fixture, journals, crash, progress));
      assertInstanceOf(
          AdvanceResult.Unavailable.class,
          crashing.execute(new AdvanceRequest(fixture.root(), Optional.empty(), true)));
      if (!crash.equals("progress")) assertArrayEquals(saved, bytes(fixture));
      advanced =
          assertInstanceOf(
                  AdvanceResult.Recovered.class,
                  useCase.execute(new AdvanceRequest(fixture.root(), Optional.empty(), true)))
              .progress();
    }
    assertEquals(2, advanced.course().contentVersion());
    assertEquals("coordinate-direction", advanced.activeLessonId().orElseThrow().value());
    assertEquals(Optional.of("a".repeat(40)), advanced.activeLessonOpenedAtRevision());
    assertArrayEquals(before, Files.readAllBytes(starter));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void editedJournalAndManifestCannotOmitPreviouslyDisclosedOwnership(boolean confirmed)
      throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    var journals = new JacksonTransitionJournalRepository(fixture.course());
    useCase(fixture, crashing(fixture, journals, "journal"), journals, true)
        .execute(answer(fixture, "compile-before-tests"));
    Path journalPath = fixture.root().resolve(".fruit-and-faults/transition.json");
    var mapper = new ObjectMapper();
    var document = mapper.readTree(Files.readAllBytes(journalPath));
    ((ObjectNode) Objects.requireNonNull(document.get("expectedManaged"))).putArray("files");
    Files.write(journalPath, mapper.writeValueAsBytes(document));
    fixture.manifests().save(fixture.root(), ManagedFiles.empty());
    byte[] oldJournal = Files.readAllBytes(journalPath);
    byte[] oldProgress = bytes(fixture);
    byte[] oldManifest =
        Files.readAllBytes(fixture.root().resolve(".fruit-and-faults/managed-files.json"));
    var disclosure =
        new DiscloseLesson(
            fixture.catalog(), fixture.files(), fixture.manifests(), fixture.progress(), journals);
    assertInstanceOf(
        AdvanceResult.Conflict.class,
        useCase(fixture, disclosure, journals, false)
            .execute(new AdvanceRequest(fixture.root(), Optional.empty(), confirmed)));
    assertArrayEquals(oldJournal, Files.readAllBytes(journalPath));
    assertArrayEquals(oldProgress, bytes(fixture));
    assertArrayEquals(
        oldManifest,
        Files.readAllBytes(fixture.root().resolve(".fruit-and-faults/managed-files.json")));
    assertTrue(
        Files.notExists(
            fixture.root().resolve("src/main/java/org/fruitandfaults/game/Direction.java")));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void progressChangedSinceJournalCannotOfferOrApplyARecoveryPreview(boolean confirmed)
      throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    var journals = new JacksonTransitionJournalRepository(fixture.course());
    useCase(fixture, crashing(fixture, journals, "journal"), journals, true)
        .execute(answer(fixture, "compile-before-tests"));
    fixture
        .progress()
        .save(
            fixture.root(),
            CourseProgress.opening(fixture.course(), null)
                .revealHint(fixture.course().lessonOrder().getFirst()));
    byte[] oldProgress = bytes(fixture);
    var disclosure =
        new DiscloseLesson(
            fixture.catalog(), fixture.files(), fixture.manifests(), fixture.progress(), journals);
    assertInstanceOf(
        AdvanceResult.Conflict.class,
        useCase(fixture, disclosure, journals, false)
            .execute(new AdvanceRequest(fixture.root(), Optional.empty(), confirmed)));
    assertArrayEquals(oldProgress, bytes(fixture));
    assertTrue(journals.load(fixture.root()).isPresent());
  }

  @Test
  void journalReplacedDuringFinalGitInspectionCannotChangeValidatedRecovery() throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    var journals = new JacksonTransitionJournalRepository(fixture.course());
    useCase(fixture, crashing(fixture, journals, "journal"), journals, true)
        .execute(answer(fixture, "compile-before-tests"));
    Path journalPath = fixture.root().resolve(".fruit-and-faults/transition.json");
    byte[] oldProgress = bytes(fixture);
    Path manifestPath = fixture.root().resolve(".fruit-and-faults/managed-files.json");
    byte[] oldManifest = Files.readAllBytes(manifestPath);
    AtomicInteger inspections = new AtomicInteger();
    GitRepository replacingGit =
        new GitRepository() {
          @Override
          public void initialize(Path root) {
            throw new AssertionError();
          }

          @Override
          public void requireInitialized(Path root) {}

          @Override
          public GitStatus status(Path root) throws IOException {
            if (inspections.incrementAndGet() == 2) {
              var mapper = new ObjectMapper();
              var document = mapper.readTree(Files.readAllBytes(journalPath));
              ((ObjectNode) Objects.requireNonNull(document.get("intendedProgress")))
                  .put("activeLessonOpenedAtRevision", "b".repeat(40));
              Files.write(journalPath, mapper.writeValueAsBytes(document));
            }
            return new GitStatus(Optional.of("a".repeat(40)), 3, 0, false, false);
          }
        };
    var disclosure =
        new DiscloseLesson(
            fixture.catalog(), fixture.files(), fixture.manifests(), fixture.progress(), journals);
    var advance =
        new AdvanceLesson(
            fixture.catalog(),
            fixture.progress(),
            fixture.manifests(),
            journals,
            request -> {
              throw new AssertionError("Recovery must not recheck partial new work");
            },
            replacingGit,
            disclosure);

    assertInstanceOf(
        AdvanceResult.Conflict.class, advance.execute(answer(fixture, "compile-before-tests")));
    assertEquals(2, inspections.get());
    assertArrayEquals(oldProgress, bytes(fixture));
    assertArrayEquals(oldManifest, Files.readAllBytes(manifestPath));
    assertEquals(
        Optional.of("b".repeat(40)),
        journals
            .load(fixture.root())
            .orElseThrow()
            .intendedProgress()
            .activeLessonOpenedAtRevision());
    assertTrue(
        Files.notExists(
            fixture.root().resolve("src/main/java/org/fruitandfaults/game/Direction.java")));
    assertTrue(
        Files.notExists(
            fixture.root().resolve("src/main/java/org/fruitandfaults/game/Coordinate.java")));
  }

  private AdvanceLesson useCase(
      CourseApplicationFixture fixture,
      DiscloseLesson disclosure,
      TransitionJournalRepository journals,
      boolean allowCheck) {
    return new AdvanceLesson(
        fixture.catalog(),
        fixture.progress(),
        fixture.manifests(),
        journals,
        request -> {
          if (!allowCheck) throw new AssertionError("Recovery must use its validated journal");
          return new CheckOutcome.Passed(List.of());
        },
        git("a".repeat(40), allowCheck ? 0 : 3),
        disclosure);
  }

  private static GitRepository git(String revision, int dirt) {
    return git(revision, dirt, () -> {});
  }

  private static GitRepository git(String revision, int dirt, Runnable inspection) {
    return new GitRepository() {
      @Override
      public void initialize(Path root) {
        throw new AssertionError();
      }

      @Override
      public void requireInitialized(Path root) {}

      @Override
      public GitStatus status(Path root) {
        inspection.run();
        return new GitStatus(Optional.of(revision), dirt, 0, false, false);
      }
    };
  }

  private DiscloseLesson crashing(
      CourseApplicationFixture fixture, TransitionJournalRepository journals, String boundary) {
    return crashing(fixture, journals, boundary, fixture.progress());
  }

  private DiscloseLesson crashing(
      CourseApplicationFixture fixture,
      TransitionJournalRepository journals,
      String boundary,
      ProgressRepository states) {
    AtomicInteger writes = new AtomicInteger();
    WorkspaceFiles files =
        new WorkspaceFiles() {
          @Override
          public Optional<byte[]> read(Path root, WorkspacePath path) throws IOException {
            return fixture.files().read(root, path);
          }

          @Override
          public DisclosurePlan.Observation inspect(Path root, WorkspacePath path)
              throws IOException {
            return fixture.files().inspect(root, path);
          }

          @Override
          public DisclosurePlan preflight(Path root, List<ManagedFile> assets, ManagedFiles managed)
              throws IOException {
            return fixture.files().preflight(root, assets, managed);
          }

          @Override
          public void writeNewSafely(Path root, WorkspacePath path, byte[] bytes)
              throws IOException {
            fixture.files().writeNewSafely(root, path, bytes);
            crash(boundary, "asset-" + writes.incrementAndGet());
          }

          @Override
          public void writeNewSafely(
              Path root, DisclosurePlan plan, Map<WorkspacePath, byte[]> contents) {
            throw new AssertionError();
          }
        };
    ManagedFilesRepository manifests =
        new ManagedFilesRepository() {
          @Override
          public Optional<ManagedFiles> load(Path root) throws IOException {
            return fixture.manifests().load(root);
          }

          @Override
          public void save(Path root, ManagedFiles state) throws IOException {
            fixture.manifests().save(root, state);
            crash(boundary, "manifest");
          }
        };
    ProgressRepository progress =
        new ProgressRepository() {
          @Override
          public Optional<CourseProgress> load(Path root) throws IOException {
            return states.load(root);
          }

          @Override
          public void save(Path root, CourseProgress state) throws IOException {
            states.save(root, state);
            crash(boundary, "progress");
          }
        };
    TransitionJournalRepository pending =
        new TransitionJournalRepository() {
          @Override
          public Optional<TransitionJournal> load(Path root) throws IOException {
            return journals.load(root);
          }

          @Override
          public void create(Path root, TransitionJournal state) throws IOException {
            journals.create(root, state);
            crash(boundary, "journal");
          }

          @Override
          public void remove(Path root, TransitionJournal state) throws IOException {
            crash(boundary, "remove");
            journals.remove(root, state);
          }
        };
    return new DiscloseLesson(fixture.catalog(), files, manifests, progress, pending);
  }

  private static void crash(String selected, String actual) throws IOException {
    if (selected.equals(actual)) throw new IOException("Injected crash");
  }

  private static byte[] bytes(CourseApplicationFixture fixture) throws IOException {
    return Files.readAllBytes(fixture.root().resolve(".fruit-and-faults/progress.json"));
  }

  private static AdvanceRequest answer(CourseApplicationFixture fixture, String answer) {
    return new AdvanceRequest(fixture.root(), Optional.of(new ReflectionAnswer(answer)), true);
  }
}
