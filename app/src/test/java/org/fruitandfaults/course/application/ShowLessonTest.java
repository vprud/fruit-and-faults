package org.fruitandfaults.course.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.fruitandfaults.progress.domain.CourseProgress;
import org.fruitandfaults.validation.domain.FailureCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShowLessonTest {
  @TempDir private Path temporary;

  @Test
  void activeLessonShowsOnlyItsInstalledInstructionsWithoutChangingProgress() throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    Path progress = fixture.root().resolve(".fruit-and-faults/progress.json");
    byte[] before = Files.readAllBytes(progress);

    var active =
        assertInstanceOf(
            LessonResult.Active.class,
            new ShowLesson(fixture.catalog(), fixture.progress()).execute(fixture.root()));

    assertEquals(fixture.course().lessons().getFirst(), active.lesson());
    assertFalse(active.toString().contains(fixture.course().lessons().get(1).instructions()));
    assertArrayEquals(before, Files.readAllBytes(progress));
  }

  @Test
  void completedCourseHasNoActiveInstructions() throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    CourseProgress current = CourseProgress.opening(fixture.course(), null);
    for (var lesson : fixture.course().lessons()) {
      current =
          current
              .advance(lesson.id(), lesson.question().correctOptionId(), "a".repeat(40))
              .progress();
    }
    fixture.progress().save(fixture.root(), current);

    assertInstanceOf(
        LessonResult.CourseComplete.class,
        new ShowLesson(fixture.catalog(), fixture.progress()).execute(fixture.root()));
  }

  @Test
  void malformedProgressDoesNotRevealInstructionsOrChangeBytes() throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    Path progress = fixture.root().resolve(".fruit-and-faults/progress.json");
    Files.writeString(progress, "{SECRET}");

    var result =
        assertInstanceOf(
            LessonResult.Unavailable.class,
            new ShowLesson(fixture.catalog(), fixture.progress()).execute(fixture.root()));

    assertEquals(FailureCategory.WORKSPACE_CONFLICT, result.category());
    assertFalse(result.toString().contains("SECRET"));
    assertEquals("{SECRET}", Files.readString(progress));
  }

  @Test
  void interruptedReadKeepsInterruptStatus() throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    Thread.currentThread().interrupt();
    try {
      var result =
          assertInstanceOf(
              LessonResult.Unavailable.class,
              new ShowLesson(fixture.catalog(), fixture.progress()).execute(fixture.root()));
      assertEquals(FailureCategory.INTERRUPTED, result.category());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }
}
