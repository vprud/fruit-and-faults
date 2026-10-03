package org.fruitandfaults.course.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.fruitandfaults.course.domain.Course;
import org.fruitandfaults.course.infra.ClasspathCourseCatalog;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.fruitandfaults.progress.domain.ProgressTransition;
import org.junit.jupiter.api.Test;

class ListLessonsTest {
  private final Course course = new ClasspathCourseCatalog("course").load();

  @Test
  void listsOrderedTitlesAndRouteStatesWithoutFutureDetails() {
    CourseProgress current =
        ((ProgressTransition.Advanced)
                CourseProgress.opening(course, null)
                    .advance(
                        course.lessonOrder().getFirst(),
                        course.lessons().getFirst().question().correctOptionId(),
                        "a".repeat(40)))
            .progress();
    var route = new ListLessons().execute(course, current);
    assertEquals(course.lessonOrder(), route.stream().map(LessonSummary::id).toList());
    assertEquals(
        course.lessons().stream().map(lesson -> lesson.title()).toList(),
        route.stream().map(LessonSummary::title).toList());
    assertEquals(
        java.util.List.of(
            LessonSummary.State.COMPLETED,
            LessonSummary.State.ACTIVE,
            LessonSummary.State.LOCKED,
            LessonSummary.State.LOCKED),
        route.stream().map(LessonSummary::state).toList());
    for (var lesson : course.lessons()) {
      assertFalse(route.toString().contains(lesson.instructions()));
      assertFalse(route.toString().contains(lesson.hints().getFirst()));
      assertFalse(route.toString().contains(lesson.question().prompt()));
    }
  }

  @Test
  void showsAvailableContinuationAfterCompletedOlderRouteAndRejectsReordering() {
    Course earlier =
        new Course(
            course.id(),
            1,
            course.title(),
            course.lessonOrder().subList(0, 1),
            course.lessons().subList(0, 1));
    CourseProgress completed =
        ((ProgressTransition.CourseComplete)
                CourseProgress.opening(earlier, null)
                    .advance(
                        earlier.lessonOrder().getFirst(),
                        earlier.lessons().getFirst().question().correctOptionId(),
                        null))
            .progress();
    Course newer =
        new Course(course.id(), 2, course.title(), course.lessonOrder(), course.lessons());
    var route = new ListLessons().execute(newer, completed);
    assertEquals(LessonSummary.State.AVAILABLE, route.get(1).state());
    assertEquals(LessonSummary.State.LOCKED, route.get(2).state());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ListLessons().execute(earlier, CourseProgress.opening(course, null)));
  }
}
