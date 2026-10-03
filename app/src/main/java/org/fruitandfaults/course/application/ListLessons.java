package org.fruitandfaults.course.application;

import java.util.ArrayList;
import java.util.List;

import org.fruitandfaults.course.domain.Course;
import org.fruitandfaults.progress.domain.CourseProgress;

/** Produces only ordered route titles and progress states, without disclosing lesson content. */
public final class ListLessons {
  /**
   * Lists an identical route or compatible appended content against validated progress.
   *
   * @param course installed course
   * @param progress workspace snapshot validated by its persistence boundary
   * @return immutable route views
   * @throws IllegalArgumentException if the installed course cannot continue this snapshot
   */
  public List<LessonSummary> execute(Course course, CourseProgress progress) {
    requireCompatible(course, progress);
    List<LessonSummary> route = new ArrayList<>();
    for (int index = 0; index < course.lessons().size(); index++) {
      var lesson = course.lessons().get(index);
      LessonSummary.State state = LessonSummary.State.LOCKED;
      if (index < progress.lessons().size()) {
        var current = progress.lessons().get(index);
        if (current.completedOptionId().isPresent()) {
          state = LessonSummary.State.COMPLETED;
        } else if (progress.activeLessonId().filter(lesson.id()::equals).isPresent()) {
          state = LessonSummary.State.ACTIVE;
        }
      } else if (index == progress.lessons().size() && progress.activeLessonId().isEmpty()) {
        state = LessonSummary.State.AVAILABLE;
      }
      route.add(new LessonSummary(lesson.id(), lesson.title(), state));
    }
    return List.copyOf(route);
  }

  static void requireCompatible(Course installed, CourseProgress progress) {
    Course saved = progress.course();
    if (!installed.id().equals(saved.id())
        || installed.contentVersion() < saved.contentVersion()
        || installed.lessonOrder().size() < saved.lessonOrder().size()
        || !installed
            .lessonOrder()
            .subList(0, saved.lessonOrder().size())
            .equals(saved.lessonOrder())
        || (installed.contentVersion() == saved.contentVersion() && !installed.equals(saved))) {
      throw new IllegalArgumentException(
          "Expected matching or append-compatible installed course content.");
    }
  }
}
