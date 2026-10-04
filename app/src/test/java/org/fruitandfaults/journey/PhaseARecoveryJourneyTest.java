package org.fruitandfaults.journey;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.fruitandfaults.cli.JourneyApplications;
import org.fruitandfaults.course.infra.LearnerJourneyFixture;
import org.fruitandfaults.progress.infra.AtomicProgressRepository;
import org.fruitandfaults.progress.infra.JacksonProgressCodec;
import org.fruitandfaults.workspace.application.DiscloseLesson;
import org.fruitandfaults.workspace.infra.JacksonManagedFilesRepository;
import org.fruitandfaults.workspace.infra.JacksonTransitionJournalRepository;
import org.fruitandfaults.workspace.infra.SafeWorkspaceFiles;
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
    var states = new AtomicProgressRepository(new JacksonProgressCodec(fixture.catalog()));
    var manifests = new JacksonManagedFilesRepository();
    var disclosure =
        new DiscloseLesson(
            fixture.catalog(),
            new SafeWorkspaceFiles(),
            manifests,
            states,
            new JacksonTransitionJournalRepository(new JacksonProgressCodec(fixture.catalog())));
    // Establish a valid completed v1 snapshot through genuine domain/storage/disclosure adapters.
    // The separate happy journey proves that every prerequisite can be checked through the CLI.
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
}
