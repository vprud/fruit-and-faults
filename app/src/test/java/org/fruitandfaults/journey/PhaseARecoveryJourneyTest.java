package org.fruitandfaults.journey;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.fruitandfaults.cli.JourneyApplications;
import org.fruitandfaults.course.infra.LearnerJourneyFixture;
import org.fruitandfaults.git.infra.ProcessGitRepository;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.fruitandfaults.progress.infra.AtomicProgressRepository;
import org.fruitandfaults.progress.infra.JacksonProgressCodec;
import org.fruitandfaults.workspace.application.DiscloseLesson;
import org.fruitandfaults.workspace.application.StartRequest;
import org.fruitandfaults.workspace.application.StartResult;
import org.fruitandfaults.workspace.domain.WorkspaceMetadata;
import org.fruitandfaults.workspace.infra.JacksonManagedFilesRepository;
import org.fruitandfaults.workspace.infra.JacksonTransitionJournalRepository;
import org.fruitandfaults.workspace.infra.SafeWorkspaceFiles;
import org.fruitandfaults.workspace.infra.SafeWorkspaceSetup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ResourceLock("java.lang.System.properties")
class PhaseARecoveryJourneyTest {
  @TempDir private Path temporary;

  @Test
  void trustedAppendOpensLessonFiveNoninteractivelyAndPreservesEveryPhaseAFile() throws Exception {
    var fixture = new PhaseAJourneyFixture(temporary);
    assertEquals(
        0, fixture.run(fixture.parent(), "start", fixture.root().toString(), "--yes").code());
    completeHistoricalRoute(fixture);
    var oldState = fixture.progressBytes();
    var oldFiles = new java.util.LinkedHashMap<String, byte[]>();
    for (var lesson : fixture.catalog().load().lessons())
      for (var asset : lesson.assets())
        oldFiles.put(
            asset.relativePath(), Files.readAllBytes(fixture.root().resolve(asset.relativePath())));
    var updated = new JourneyContinuation();
    var application =
        JourneyApplications.compose(
            updated,
            updated,
            request -> {
              throw new AssertionError("A completed historical course must not be regraded.");
            });
    var status = fixture.run(fixture.root(), () -> application, "status");
    assertEquals(0, status.code(), status.err());
    assertTrue(status.out().contains("Доступно продолжение курса"));
    assertArrayEquals(oldState, fixture.progressBytes());
    var next = fixture.run(fixture.root(), () -> application, "next", "--yes");
    assertEquals(0, next.code(), next.err());
    assertTrue(Files.exists(fixture.root().resolve("docs/lesson-5.txt")));
    var progress =
        new AtomicProgressRepository(new JacksonProgressCodec(updated))
            .load(fixture.root())
            .orElseThrow();
    assertEquals(2, progress.course().contentVersion());
    assertEquals("phase-b-pilot", progress.activeLessonId().orElseThrow().value());
    assertEquals(
        4,
        progress.lessons().stream()
            .filter(lesson -> lesson.completedOptionId().isPresent())
            .count());
    for (var entry : oldFiles.entrySet())
      assertArrayEquals(
          entry.getValue(),
          Files.readAllBytes(fixture.root().resolve(entry.getKey())),
          entry.getKey());
  }

  @ParameterizedTest
  @ValueSource(strings = {"manifest", "progress"})
  void answerlessNextRecoversAppendedLessonAfterPublicationAndThenPreservesOpenedState(
      String boundary) throws Exception {
    var fixture = new PhaseAJourneyFixture(temporary);
    assertEquals(
        0, fixture.run(fixture.parent(), "start", fixture.root().toString(), "--yes").code());
    completeHistoricalRoute(fixture);
    var updated = new JourneyContinuation();
    var before = fixture.progress();
    String head = fixture.git("rev-parse", "HEAD").strip();
    var intended = before.continueWith(updated.load(), head);
    var historical = new java.util.LinkedHashMap<String, byte[]>();
    for (var lesson : fixture.catalog().load().lessons())
      for (var asset : lesson.assets())
        historical.put(
            asset.relativePath(), Files.readAllBytes(fixture.root().resolve(asset.relativePath())));
    assertThrows(
        IOException.class,
        () ->
            JourneyCrashes.disclosure(fixture, updated, updated, boundary)
                .apply(
                    fixture.root(),
                    updated.load().lessons().getLast(),
                    new JacksonManagedFilesRepository().load(fixture.root()),
                    Optional.of(before),
                    intended));
    Path journal = fixture.root().resolve(".fruit-and-faults/transition.json");
    Path manifest = fixture.root().resolve(".fruit-and-faults/managed-files.json");
    byte[] pending = Files.readAllBytes(journal);
    byte[] saved = fixture.progressBytes();
    byte[] owned = Files.readAllBytes(manifest);
    byte[] note = Files.readAllBytes(fixture.root().resolve("docs/lesson-5.txt"));
    var application =
        JourneyApplications.compose(
            updated,
            updated,
            request -> {
              throw new AssertionError(
                  "Continuation recovery must not check new or historical work.");
            });
    var refused =
        assertInstanceOf(
            StartResult.Conflict.class,
            application.start().apply(new StartRequest(fixture.root(), true)));
    assertTrue(refused.diagnostic().contains("next"));
    fixture.assertSafeFailure(
        fixture.run(
            fixture.parent(), () -> application, "start", fixture.root().toString(), "--yes"),
        3);
    assertArrayEquals(pending, Files.readAllBytes(journal));
    assertArrayEquals(saved, fixture.progressBytes());
    assertArrayEquals(owned, Files.readAllBytes(manifest));
    fixture.assertSafeFailure(
        fixture.run(fixture.root(), () -> application, "next", "--answer", "arbitrary", "--yes"),
        3);
    assertArrayEquals(pending, Files.readAllBytes(journal));
    assertArrayEquals(saved, fixture.progressBytes());
    assertArrayEquals(owned, Files.readAllBytes(manifest));
    var recovered = fixture.run(fixture.root(), () -> application, "next", "--yes");
    assertEquals(0, recovered.code(), recovered.err());
    assertTrue(recovered.out().contains("Предпросмотр:"));
    assertEquals(
        intended,
        new AtomicProgressRepository(new JacksonProgressCodec(updated))
            .load(fixture.root())
            .orElseThrow());
    assertFalse(Files.exists(journal));
    assertEquals(head, fixture.git("rev-parse", "HEAD").strip());
    assertArrayEquals(note, Files.readAllBytes(fixture.root().resolve("docs/lesson-5.txt")));
    for (var entry : historical.entrySet())
      assertArrayEquals(
          entry.getValue(),
          Files.readAllBytes(fixture.root().resolve(entry.getKey())),
          entry.getKey());
    byte[] opened = fixture.progressBytes();
    byte[] disclosed = Files.readAllBytes(manifest);
    fixture.assertSafeFailure(fixture.run(fixture.root(), () -> application, "next", "--yes"), 2);
    assertArrayEquals(opened, fixture.progressBytes());
    assertArrayEquals(disclosed, Files.readAllBytes(manifest));
    assertFalse(Files.exists(journal));
    assertFalse(Files.exists(fixture.root().resolve("build")));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void startCannotBypassPendingLessonAdvancementOrItsOriginalOpeningHead(boolean changedHead)
      throws Exception {
    var fixture = new PhaseAJourneyFixture(temporary);
    assertEquals(
        0, fixture.run(fixture.parent(), "start", fixture.root().toString(), "--yes").code());
    fixture.solve(0);
    String opening = fixture.commit("fix(game): before pending lesson transition");
    var before = fixture.progress();
    var intended =
        before
            .advance(before.activeLessonId().orElseThrow(), "compile-before-tests", opening)
            .progress();
    assertThrows(
        IOException.class,
        () ->
            JourneyCrashes.disclosure(fixture, "asset-1")
                .apply(
                    fixture.root(),
                    fixture.catalog().load().lessons().get(1),
                    new JacksonManagedFilesRepository().load(fixture.root()),
                    Optional.of(before),
                    intended));
    String current =
        changedHead
            ? fixture.commit("chore(game): learner checkpoint during pending transition")
            : opening;
    Path journal = fixture.root().resolve(".fruit-and-faults/transition.json");
    Path manifest = fixture.root().resolve(".fruit-and-faults/managed-files.json");
    Path asset = fixture.root().resolve(PhaseAJourneyFixture.MAIN + "Direction.java");
    byte[] pending = Files.readAllBytes(journal);
    byte[] saved = fixture.progressBytes();
    byte[] owned = Files.readAllBytes(manifest);
    byte[] published = Files.readAllBytes(asset);
    String gitState = fixture.git("status", "--porcelain=v1");
    if (changedHead)
      fixture.assertSafeFailure(
          fixture.run("next", "--answer", "compile-before-tests", "--yes"), 3);
    for (boolean confirmed : new boolean[] {true, false}) {
      var refused =
          confirmed
              ? fixture.run(fixture.parent(), "start", fixture.root().toString(), "--yes")
              : fixture.run(fixture.parent(), "start", fixture.root().toString());
      fixture.assertSafeFailure(refused, 3);
      assertTrue(refused.err().contains("next"));
      assertArrayEquals(pending, Files.readAllBytes(journal));
      assertArrayEquals(saved, fixture.progressBytes());
      assertArrayEquals(owned, Files.readAllBytes(manifest));
      assertArrayEquals(published, Files.readAllBytes(asset));
      assertEquals(current, fixture.git("rev-parse", "HEAD").strip());
      assertEquals(gitState, fixture.git("status", "--porcelain=v1"));
      assertFalse(
          Files.exists(fixture.root().resolve(PhaseAJourneyFixture.MAIN + "Coordinate.java")));
    }
    if (!changedHead) {
      assertEquals(0, fixture.run("next", "--answer", "compile-before-tests", "--yes").code());
      assertFalse(Files.exists(journal));
      assertEquals(opening, fixture.progress().activeLessonOpenedAtRevision().orElseThrow());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "journal",
        "asset-1",
        "asset-2",
        "asset-3",
        "asset-4",
        "asset-5",
        "asset-6",
        "asset-7",
        "asset-8",
        "asset-9",
        "asset-10",
        "manifest",
        "progress",
        "remove",
        "removed"
      })
  void startStillRecoversEveryInitialDisclosureBoundaryAndRepeatedStartIsReadOnly(String boundary)
      throws Exception {
    var fixture = new PhaseAJourneyFixture(temporary);
    var setup = new SafeWorkspaceSetup();
    setup.createDirectory(fixture.root());
    new ProcessGitRepository().initialize(fixture.root());
    setup.createMetadata(fixture.root(), new WorkspaceMetadata(fixture.catalog().load().id(), 1));
    var intended = CourseProgress.opening(fixture.catalog().load(), null);
    assertThrows(
        IOException.class,
        () ->
            JourneyCrashes.disclosure(fixture, boundary)
                .apply(
                    fixture.root(),
                    fixture.catalog().load().lessons().getFirst(),
                    Optional.empty(),
                    Optional.empty(),
                    intended));
    var recovered = fixture.run(fixture.parent(), "start", fixture.root().toString(), "--yes");
    assertEquals(0, recovered.code(), recovered.err());
    assertEquals(intended, fixture.progress());
    assertEquals(
        10, new JacksonManagedFilesRepository().load(fixture.root()).orElseThrow().files().size());
    assertFalse(Files.exists(fixture.root().resolve(".fruit-and-faults/transition.json")));
    assertEquals("", fixture.git("rev-list", "--all"));
    for (var asset : fixture.catalog().load().lessons().getFirst().assets())
      assertArrayEquals(
          fixture.catalog().load(asset),
          Files.readAllBytes(fixture.root().resolve(asset.relativePath())));
    byte[] saved = fixture.progressBytes();
    assertEquals(0, fixture.run(fixture.parent(), "start", fixture.root().toString()).code());
    assertArrayEquals(saved, fixture.progressBytes());
    assertFalse(
        Files.exists(fixture.root().resolve(PhaseAJourneyFixture.MAIN + "Coordinate.java")));
  }

  @Test
  void nestedResumeRepeatedCommandsAndLocalCloneRestorePortableCourseAndGitState()
      throws Exception {
    var fixture = new PhaseAJourneyFixture(temporary);
    fixture.start();
    fixture.solve(0);
    assertEquals(0, fixture.run("check").code());
    byte[] before = fixture.progressBytes();
    assertEquals(0, fixture.run("check").code());
    assertArrayEquals(before, fixture.progressBytes());
    fixture.commit("fix(game): complete first lesson");
    assertEquals(0, fixture.run("next", "--answer", "compile-before-tests", "--yes").code());
    byte[] opened = fixture.progressBytes();
    var repeated = fixture.run("next", "--answer", "compile-before-tests", "--yes");
    fixture.assertSafeFailure(repeated, 4);
    assertArrayEquals(opened, fixture.progressBytes());
    Path nested = fixture.root().resolve("src/main/java/org/fruitandfaults/game");
    assertTrue(fixture.run(nested, "status").out().contains("Coordinate and Direction"));
    assertEquals(0, fixture.run(fixture.parent(), "start", fixture.root().toString()).code());
    assertArrayEquals(opened, fixture.progressBytes());
    fixture.commit("chore(course): save opened lesson state");
    var restored = new PhaseAJourneyFixture(temporary, "Restored clone копия");
    LearnerJourneyFixture.runGit(
        fixture.parent(),
        "clone",
        "--no-local",
        "--no-hardlinks",
        fixture.root().toString(),
        restored.root().toString());
    assertArrayEquals(opened, restored.progressBytes());
    for (String state : new String[] {"managed-files.json", "workspace.properties"}) {
      assertArrayEquals(
          Files.readAllBytes(fixture.root().resolve(".fruit-and-faults/" + state)),
          Files.readAllBytes(restored.root().resolve(".fruit-and-faults/" + state)));
    }
    assertEquals(fixture.git("rev-parse", "HEAD"), restored.git("rev-parse", "HEAD"));
    assertEquals("", restored.git("status", "--porcelain=v1"));
    assertFalse(Files.exists(restored.root().resolve(".gradle")));
    assertFalse(Files.exists(restored.root().resolve("build")));
    assertEquals(0, restored.run(restored.root().resolve("src/main"), "status").code());
    assertEquals(0, restored.run(restored.parent(), "start", restored.root().toString()).code());
    PhaseAJourneyFixture.prepareCache(restored.root());
    restored.solve(1);
    assertEquals(0, restored.run("check").code());
    assertEquals(5, LearnerJourneyFixture.passingTestCount(restored.root()));
    assertArrayEquals(opened, restored.progressBytes());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "journal",
        "asset-1",
        "asset-2",
        "asset-3",
        "manifest",
        "progress",
        "remove",
        "removed"
      })
  void installedNextRecoversEveryDurableDisclosureBoundaryWithoutOverwritingFiles(String boundary)
      throws Exception {
    var fixture = new PhaseAJourneyFixture(temporary);
    assertEquals(
        0, fixture.run(fixture.parent(), "start", fixture.root().toString(), "--yes").code());
    fixture.solve(0);
    String head = fixture.commit("fix(game): saved lesson before injected crash");
    var previous = fixture.progress();
    byte[] before = fixture.progressBytes();
    var intended =
        previous
            .advance(previous.activeLessonId().orElseThrow(), "compile-before-tests", head)
            .progress();
    var managed = new JacksonManagedFilesRepository().load(fixture.root());
    // Only the precise failing IO boundary is injected. Assets, journals, state and Git are real.
    assertThrows(
        IOException.class,
        () ->
            JourneyCrashes.disclosure(fixture, boundary)
                .apply(
                    fixture.root(),
                    fixture.catalog().load().lessons().get(1),
                    managed,
                    Optional.of(previous),
                    intended));
    boolean committed =
        boundary.equals("progress") || boundary.equals("remove") || boundary.equals("removed");
    if (!committed) assertArrayEquals(before, fixture.progressBytes());
    if (boundary.equals("removed")) {
      assertFalse(Files.exists(fixture.root().resolve(".fruit-and-faults/transition.json")));
      assertEquals(0, fixture.run(fixture.parent(), "start", fixture.root().toString()).code());
    } else {
      assertTrue(Files.exists(fixture.root().resolve(".fruit-and-faults/transition.json")));
      var recovered = fixture.run("next", "--answer", "compile-before-tests", "--yes");
      assertEquals(0, recovered.code(), recovered.err());
      assertTrue(recovered.out().contains("Предпросмотр:"));
    }
    assertEquals(intended, fixture.progress());
    assertEquals(head, fixture.git("rev-parse", "HEAD").strip());
    assertEquals(
        13, new JacksonManagedFilesRepository().load(fixture.root()).orElseThrow().files().size());
    for (var asset : fixture.catalog().load().lessons().get(1).assets()) {
      assertArrayEquals(
          fixture.catalog().load(asset),
          Files.readAllBytes(fixture.root().resolve(asset.relativePath())));
    }
    assertFalse(Files.exists(fixture.root().resolve(".fruit-and-faults/transition.json")));
    byte[] after = fixture.progressBytes();
    assertEquals(0, fixture.run(fixture.parent(), "start", fixture.root().toString()).code());
    assertArrayEquals(after, fixture.progressBytes());
  }

  private static void completeHistoricalRoute(PhaseAJourneyFixture fixture) throws IOException {
    var states = new AtomicProgressRepository(new JacksonProgressCodec(fixture.catalog()));
    var manifests = new JacksonManagedFilesRepository();
    var disclosure =
        new DiscloseLesson(
            fixture.catalog(),
            new SafeWorkspaceFiles(),
            manifests,
            states,
            new JacksonTransitionJournalRepository(new JacksonProgressCodec(fixture.catalog())));
    // Real completed v1 storage/disclosure; the happy journey proves every real prerequisite check.
    for (int index = 0; index < 4; index++) {
      fixture.solve(index);
      String head = fixture.commit("feat(game): complete retained lesson " + (index + 1));
      var before = fixture.progress();
      var intended =
          before
              .advance(
                  before.activeLessonId().orElseThrow(),
                  fixture.catalog().load().lessons().get(index).question().correctOptionId(),
                  index == 3 ? null : head)
              .progress();
      if (index < 3)
        disclosure.apply(
            fixture.root(),
            fixture.catalog().load().lessons().get(index + 1),
            manifests.load(fixture.root()),
            Optional.of(before),
            intended);
      else states.save(fixture.root(), intended);
    }
    fixture.commit("chore(course): record completed historical state");
  }
}
