package org.fruitandfaults.course.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.fruitandfaults.progress.application.ProgressRepository;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShowHintTest {
  @TempDir private Path temporary;

  @Test
  void revealsOnlyActiveHintLevelsAndRepeatsLevelThreeWithoutAnotherWrite() throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    var hints = new ShowHint(fixture.catalog(), fixture.progress());
    for (int level = 1; level <= 3; level++) {
      HintResult.Revealed shown =
          assertInstanceOf(HintResult.Revealed.class, hints.execute(fixture.root()));
      assertEquals(level, shown.level());
      assertEquals(fixture.course().lessons().getFirst().hints().get(level - 1), shown.text());
      assertEquals(
          level,
          fixture.progress().load(fixture.root()).orElseThrow().lessons().getFirst().hintLevel());
      assertFalse(shown.toString().contains(fixture.course().lessons().get(1).hints().getFirst()));
    }
    HintResult.Revealed repeated =
        assertInstanceOf(
            HintResult.Revealed.class,
            new ShowHint(fixture.catalog(), refusingWrites(fixture)).execute(fixture.root()));
    assertEquals(3, repeated.level());
  }

  @Test
  void failedPersistenceAndFailedTextLoadingPreserveLastValidProgress() throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    byte[] before = Files.readAllBytes(fixture.root().resolve(".fruit-and-faults/progress.json"));
    HintResult.Unavailable failed =
        assertInstanceOf(
            HintResult.Unavailable.class,
            new ShowHint(fixture.catalog(), refusingWrites(fixture)).execute(fixture.root()));
    assertFalse(failed.toString().contains("SECRET"));
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        before, Files.readAllBytes(fixture.root().resolve(".fruit-and-faults/progress.json")));
    assertInstanceOf(
        HintResult.Unavailable.class,
        new ShowHint(
                () -> {
                  throw new IllegalArgumentException("missing selected text SECRET");
                },
                fixture.progress())
            .execute(fixture.root()));
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        before, Files.readAllBytes(fixture.root().resolve(".fruit-and-faults/progress.json")));
  }

  @Test
  void selectedActiveLessonAndCourseCompletionNeverRevealOtherHints() throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    CourseProgress current =
        CourseProgress.opening(fixture.course(), null)
            .advance(
                fixture.course().lessonOrder().getFirst(),
                fixture.course().lessons().getFirst().question().correctOptionId(),
                "a".repeat(40))
            .progress();
    fixture.progress().save(fixture.root(), current);
    HintResult.Revealed selected =
        assertInstanceOf(
            HintResult.Revealed.class,
            new ShowHint(fixture.catalog(), fixture.progress()).execute(fixture.root()));
    assertEquals("coordinate-direction", selected.lessonId().value());
    assertEquals(fixture.course().lessons().get(1).hints().getFirst(), selected.text());
    for (int index = 1; index < fixture.course().lessons().size(); index++) {
      var lesson = fixture.course().lessons().get(index);
      current =
          current
              .advance(lesson.id(), lesson.question().correctOptionId(), "b".repeat(40))
              .progress();
    }
    fixture.progress().save(fixture.root(), current);
    byte[] before = Files.readAllBytes(fixture.root().resolve(".fruit-and-faults/progress.json"));
    assertInstanceOf(
        HintResult.CourseComplete.class,
        new ShowHint(fixture.catalog(), refusingWrites(fixture)).execute(fixture.root()));
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        before, Files.readAllBytes(fixture.root().resolve(".fruit-and-faults/progress.json")));
  }

  @Test
  void malformedProgressProducesNoHintAndLeavesOriginalBytes() throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    Path path = fixture.root().resolve(".fruit-and-faults/progress.json");
    Files.writeString(path, "{SECRET}");
    HintResult.Unavailable result =
        assertInstanceOf(
            HintResult.Unavailable.class,
            new ShowHint(fixture.catalog(), fixture.progress()).execute(fixture.root()));
    assertFalse(result.toString().contains("SECRET"));
    assertEquals("{SECRET}", Files.readString(path));
  }

  private static ProgressRepository refusingWrites(CourseApplicationFixture fixture) {
    return new ProgressRepository() {
      @Override
      public Optional<CourseProgress> load(Path root) throws IOException {
        return fixture.progress().load(root);
      }

      @Override
      public void save(Path root, CourseProgress current) throws IOException {
        throw new IOException("SECRET injected atomic replacement failure");
      }
    };
  }
}
