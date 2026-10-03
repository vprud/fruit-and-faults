package org.fruitandfaults.progress.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.fruitandfaults.course.domain.CompletionCriterion;
import org.fruitandfaults.course.domain.Course;
import org.fruitandfaults.course.domain.CourseId;
import org.fruitandfaults.course.domain.Lesson;
import org.fruitandfaults.course.domain.LessonId;
import org.fruitandfaults.course.domain.ReflectionOption;
import org.fruitandfaults.course.domain.ReflectionQuestion;
import org.junit.jupiter.api.Test;

class CourseProgressTest {
  private final LessonId first = new LessonId("first");
  private final LessonId second = new LessonId("second");

  @Test
  void openingSelectsFirstLessonAndRetainsNullableOpeningRevision() {
    CourseProgress progress = CourseProgress.opening(course(), null);
    assertEquals(Optional.of(first), progress.activeLessonId());
    assertEquals(Optional.empty(), progress.activeLessonOpenedAtRevision());
    assertEquals(
        List.of(0, 0), progress.lessons().stream().map(LessonProgress::hintLevel).toList());
    assertEquals(
        Optional.of("abc123"),
        CourseProgress.opening(course(), "abc123").activeLessonOpenedAtRevision());
  }

  @Test
  void hintsRevealOneLevelAtATimeAndStopAtMaximum() {
    CourseProgress opening = CourseProgress.opening(course(), "revision");
    CourseProgress one = opening.revealHint(first);
    CourseProgress two = one.revealHint(first);
    CourseProgress three = two.revealHint(first);
    assertEquals(0, opening.lessons().getFirst().hintLevel());
    assertEquals(1, one.lessons().getFirst().hintLevel());
    assertEquals(2, two.lessons().getFirst().hintLevel());
    assertEquals(3, three.lessons().getFirst().hintLevel());
    assertEquals(three, three.revealHint(first));
    assertThrows(IllegalArgumentException.class, () -> opening.revealHint(second));
  }

  @Test
  void advancesWithStableAnswerIdentityAndNewOpeningRevision() {
    CourseProgress opening = CourseProgress.opening(course(), null);
    CourseProgress next =
        assertInstanceOf(
                ProgressTransition.Advanced.class, opening.advance(first, "yes", "new-revision"))
            .progress();
    assertEquals(Optional.of(second), next.activeLessonId());
    assertEquals(Optional.of("new-revision"), next.activeLessonOpenedAtRevision());
    assertEquals(Optional.of("yes"), next.lessons().getFirst().completedOptionId());
    assertEquals(
        next,
        assertInstanceOf(
                ProgressTransition.AlreadyApplied.class, next.advance(first, "yes", "ignored"))
            .progress());
    assertThrows(IllegalArgumentException.class, () -> next.advance(first, "no", null));
    assertThrows(IllegalArgumentException.class, () -> opening.advance(first, "missing", null));
    assertThrows(IllegalArgumentException.class, () -> opening.advance(first, "no", null));
  }

  @Test
  void futureLessonCannotAdvanceBeforeItsPrerequisite() {
    CourseProgress opening = CourseProgress.opening(course(), null);
    assertEquals(
        opening,
        assertInstanceOf(
                ProgressTransition.InvalidPrerequisite.class, opening.advance(second, "yes", null))
            .progress());
  }

  @Test
  void finalCompletionHasNoActiveLessonAndRepeatsIdempotently() {
    CourseProgress next =
        CourseProgress.opening(course(), null).advance(first, "yes", "revision").progress();
    CourseProgress complete =
        assertInstanceOf(ProgressTransition.CourseComplete.class, next.advance(second, "yes", null))
            .progress();
    assertTrue(complete.activeLessonId().isEmpty());
    assertTrue(complete.activeLessonOpenedAtRevision().isEmpty());
    assertEquals(
        List.of(Optional.of("yes"), Optional.of("yes")),
        complete.lessons().stream().map(LessonProgress::completedOptionId).toList());
    assertEquals(
        complete,
        assertInstanceOf(
                ProgressTransition.AlreadyApplied.class, complete.advance(second, "yes", null))
            .progress());
    assertThrows(IllegalArgumentException.class, () -> complete.revealHint(second));
  }

  @Test
  void unknownLessonsAndUnsupportedFormatAreRejected() {
    CourseProgress opening = CourseProgress.opening(course(), null);
    assertThrows(
        IllegalArgumentException.class, () -> opening.advance(new LessonId("absent"), "yes", null));
    assertThrows(IllegalArgumentException.class, () -> opening.revealHint(new LessonId("absent")));
    assertThrows(IllegalArgumentException.class, () -> new ProgressFormatVersion(0));
    assertThrows(IllegalArgumentException.class, () -> new ProgressFormatVersion(2));
    assertThrows(
        IllegalArgumentException.class, () -> new LessonProgress(first, -1, Optional.empty()));
    assertThrows(
        IllegalArgumentException.class, () -> new LessonProgress(first, 4, Optional.empty()));
    assertThrows(IllegalArgumentException.class, () -> CourseProgress.opening(course(), " "));
  }

  private static Course course() {
    return new Course(
        new CourseId("course"),
        1,
        "Course",
        List.of(new LessonId("first"), new LessonId("second")),
        List.of(lesson("first", List.of()), lesson("second", List.of(new LessonId("first")))));
  }

  private static Lesson lesson(String id, List<LessonId> prerequisites) {
    return new Lesson(
        new LessonId(id),
        "Title",
        "Goal",
        prerequisites,
        List.of(),
        List.of(),
        List.of(new CompletionCriterion("check", "Check behavior")),
        "Instructions",
        List.of("One", "Two", "Three"),
        new ReflectionQuestion(
            "question",
            "Why?",
            List.of(
                new ReflectionOption("yes", "Yes", "Correct"),
                new ReflectionOption("no", "No", "Try again")),
            "yes"),
        "feat: learn");
  }
}
