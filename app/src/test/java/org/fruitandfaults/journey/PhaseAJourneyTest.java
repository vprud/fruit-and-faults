package org.fruitandfaults.journey;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.fruitandfaults.course.domain.AssetPolicy;
import org.fruitandfaults.course.infra.LearnerJourneyFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("java.lang.System.properties")
class PhaseAJourneyTest {
  @TempDir private Path temporary;

  @Test
  void installedCompositionCompletesFourCumulativeLessonsAndCommitsPortableTerminalState()
      throws Exception {
    var fixture = new PhaseAJourneyFixture(temporary);
    var start = fixture.start();
    assertTrue(start.out().contains("docs/publishing-to-github.md"));
    assertTrue(Files.isDirectory(fixture.root().resolve(".git")));
    assertFalse(
        Files.exists(fixture.root().resolve(PhaseAJourneyFixture.MAIN + "Coordinate.java")));
    assertTrue(fixture.git("rev-parse", "--is-inside-work-tree").contains("true"));
    byte[] opening = fixture.progressBytes();
    var failure = fixture.run("check");
    fixture.assertSafeFailure(failure, 4);
    assertTrue(failure.err().contains("Starter.java:9"), failure.err());
    assertArrayEquals(opening, fixture.progressBytes());
    assertFalse(Files.exists(fixture.root().resolve("build/test-results/test")));
    for (int level = 1; level <= 3; level++) {
      assertTrue(fixture.run("hint").out().contains("Hint " + level + "/3"));
      assertEquals(level, fixture.progress().lessons().getFirst().hintLevel());
    }
    byte[] hints = fixture.progressBytes();
    assertTrue(fixture.run("hint").out().contains("Hint 3/3"));
    assertArrayEquals(hints, fixture.progressBytes());

    List<String> answers =
        List.of(
            "compile-before-tests",
            "returned-coordinate",
            "original-coordinate",
            "count-successful-transitions");
    List<Integer> counts = List.of(1, 5, 18, 21);
    for (int lesson = 0; lesson < 4; lesson++) {
      fixture.solve(lesson);
      byte[] beforeCheck = fixture.progressBytes();
      var checked = fixture.run("check");
      assertEquals(0, checked.code(), checked.err());
      assertTrue(checked.out().contains("Check passed"));
      assertEquals(counts.get(lesson), LearnerJourneyFixture.passingTestCount(fixture.root()));
      assertArrayEquals(beforeCheck, fixture.progressBytes());
      String head = fixture.commit("feat(game): finish lesson " + (lesson + 1));
      if (lesson == 0) {
        byte[] beforeWrongAnswer = fixture.progressBytes();
        fixture.assertSafeFailure(fixture.run("next", "--answer", "assertion-first", "--yes"), 1);
        assertArrayEquals(beforeWrongAnswer, fixture.progressBytes());
        assertEquals("", fixture.git("status", "--porcelain=v1"));
      }
      var next = fixture.run("next", "--answer", answers.get(lesson), "--yes");
      assertEquals(0, next.code(), next.err());
      assertTrue(next.out().contains("Preview:"));
      assertTrue(next.out().contains("origin"), next.out());
      assertEquals(head, fixture.git("rev-parse", "HEAD").strip());
      assertEquals(
          lesson + 1,
          fixture.progress().lessons().stream()
              .filter(item -> item.completedOptionId().isPresent())
              .count());
      assertEquals(
          answers.get(lesson),
          fixture.progress().lessons().get(lesson).completedOptionId().orElseThrow());
      if (lesson < 3) {
        assertEquals(head, fixture.progress().activeLessonOpenedAtRevision().orElseThrow());
        assertTrue(fixture.git("status", "--porcelain=v1").contains("progress.json"));
        assertFalse(Files.exists(fixture.root().resolve(".fruit-and-faults/transition.json")));
      } else {
        assertTrue(next.out().contains("Course complete"));
        assertTrue(next.out().contains("final metadata commit"), next.out());
      }
    }
    assertTrue(fixture.progress().activeLessonId().isEmpty());
    assertTrue(fixture.run("status").out().contains("final metadata commit"));
    assertEquals(4, fixture.run("list").out().split("completed", -1).length - 1);
    for (var lesson : fixture.catalog().load().lessons()) {
      for (var asset : lesson.assets()) {
        if (asset.policy() == AssetPolicy.IMMUTABLE_CHECK) {
          assertArrayEquals(
              fixture.catalog().load(asset),
              Files.readAllBytes(fixture.root().resolve(asset.relativePath())));
        }
      }
    }
    String metadata = fixture.commit("chore(course): record Phase A completion");
    assertTrue(fixture.run("status").out().contains("fruit-and-faults list"));
    assertEquals(metadata, fixture.git("rev-parse", "HEAD").strip());
    assertEquals(5, Integer.parseInt(fixture.git("rev-list", "--count", "HEAD").strip()));
    assertEquals("", fixture.git("remote"));
    String tracked = fixture.git("ls-files");
    assertTrue(tracked.contains(".fruit-and-faults/progress.json"));
    assertTrue(tracked.contains(".fruit-and-faults/managed-files.json"));
    assertTrue(tracked.contains(".fruit-and-faults/workspace.properties"));
    String state = Files.readString(fixture.root().resolve(".fruit-and-faults/progress.json"));
    assertFalse(state.contains(fixture.parent().toString()));
    byte[] terminal = fixture.progressBytes();
    assertEquals(
        0, fixture.run("next", "--answer", "count-successful-transitions", "--yes").code());
    assertEquals(0, fixture.run("next", "--yes").code());
    assertEquals(0, fixture.run("check").code());
    assertArrayEquals(terminal, fixture.progressBytes());
  }
}
