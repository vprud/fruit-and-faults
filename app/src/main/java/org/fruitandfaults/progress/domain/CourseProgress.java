package org.fruitandfaults.progress.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.fruitandfaults.course.domain.Course;
import org.fruitandfaults.course.domain.CourseCompatibility;
import org.fruitandfaults.course.domain.Lesson;
import org.fruitandfaults.course.domain.LessonId;
import org.jspecify.annotations.Nullable;

/**
 * Validated progress along a course's ordered route.
 *
 * @param course matching validated course content
 * @param formatVersion supported progress schema
 * @param lessons progress in course order, with a contiguous completed prefix
 * @param activeLessonId first incomplete lesson, or empty after completion
 * @param activeLessonOpenedAtRevision revision when the active lesson opened, if available
 */
public record CourseProgress(
    Course course,
    ProgressFormatVersion formatVersion,
    List<LessonProgress> lessons,
    Optional<LessonId> activeLessonId,
    Optional<String> activeLessonOpenedAtRevision) {
  /** Validates the entire snapshot before it can become domain state. */
  public CourseProgress {
    Objects.requireNonNull(course);
    Objects.requireNonNull(formatVersion);
    Objects.requireNonNull(activeLessonId);
    Objects.requireNonNull(activeLessonOpenedAtRevision);
    lessons = List.copyOf(lessons);
    if (lessons.size() != course.lessons().size()) {
      throw new IllegalArgumentException("Expected progress for every course lesson.");
    }
    Optional<LessonId> expectedActive = Optional.empty();
    for (int index = 0; index < lessons.size(); index++) {
      LessonProgress state = lessons.get(index);
      Lesson lesson = course.lessons().get(index);
      if (!state.lessonId().equals(lesson.id())) {
        throw new IllegalArgumentException("Expected unique lesson IDs in course order.");
      }
      if (state.completedOptionId().isPresent()) {
        if (expectedActive.isPresent()
            || !state
                .completedOptionId()
                .orElseThrow()
                .equals(lesson.question().correctOptionId())) {
          throw new IllegalArgumentException(
              "Expected a completed prefix with accepted option IDs.");
        }
      } else if (expectedActive.isEmpty()) {
        expectedActive = Optional.of(lesson.id());
      } else if (state.hintLevel() != 0) {
        throw new IllegalArgumentException("Unopened lessons cannot have revealed hints.");
      }
    }
    if (!activeLessonId.equals(expectedActive)) {
      throw new IllegalArgumentException(
          "Expected active lesson to be the first incomplete lesson.");
    }
    if (activeLessonId.isEmpty() && activeLessonOpenedAtRevision.isPresent()) {
      throw new IllegalArgumentException(
          "Completed courses cannot have an active opening revision.");
    }
    activeLessonOpenedAtRevision.ifPresent(CourseProgress::validateRevision);
  }

  /** Opens a course at its first lesson, optionally remembering a Git revision. */
  public static CourseProgress opening(Course course, @Nullable String revision) {
    return new CourseProgress(
        course,
        new ProgressFormatVersion(1),
        course.lessonOrder().stream()
            .map(id -> new LessonProgress(id, 0, Optional.empty()))
            .toList(),
        Optional.of(course.lessonOrder().getFirst()),
        Optional.ofNullable(revision));
  }

  /** Reveals one hint for the active lesson, capped at the lesson's maximum. */
  public CourseProgress revealHint(LessonId lessonId) {
    int index = lessonIndex(lessonId);
    if (!activeLessonId.equals(Optional.of(lessonId))) {
      throw new IllegalArgumentException("Hints are available only for the active lesson.");
    }
    LessonProgress state = lessons.get(index);
    if (state.hintLevel() == course.lessons().get(index).hints().size()) {
      return this;
    }
    List<LessonProgress> updated = new ArrayList<>(lessons);
    updated.set(index, new LessonProgress(lessonId, state.hintLevel() + 1, Optional.empty()));
    return new CourseProgress(
        course, formatVersion, updated, activeLessonId, activeLessonOpenedAtRevision);
  }

  /**
   * Rebinds trusted compatible content in memory, opening appended content only at a supplied
   * revision.
   *
   * @param installed compatible installed route
   * @param revision current validated local revision when opening a continuation
   * @return in-memory state; callers commit it only after complete disclosure
   */
  public CourseProgress continueWith(Course installed, @Nullable String revision) {
    CourseCompatibility.requirePrefix(installed, course);
    List<LessonProgress> updated = new ArrayList<>(lessons);
    installed.lessonOrder().stream()
        .skip(lessons.size())
        .map(id -> new LessonProgress(id, 0, Optional.empty()))
        .forEach(updated::add);
    Optional<LessonId> active = activeLessonId;
    Optional<String> openedAt = activeLessonOpenedAtRevision;
    if (active.isEmpty() && installed.lessons().size() > lessons.size()) {
      active = Optional.of(installed.lessonOrder().get(lessons.size()));
      openedAt = Optional.ofNullable(revision);
    }
    return new CourseProgress(installed, formatVersion, updated, active, openedAt);
  }

  /** Completes an active lesson with its accepted stable option ID and opens the next lesson. */
  public ProgressTransition advance(
      LessonId lessonId, String optionId, @Nullable String nextRevision) {
    int index = lessonIndex(lessonId);
    Lesson lesson = course.lessons().get(index);
    if (!lesson.question().correctOptionId().equals(optionId)) {
      throw new IllegalArgumentException(
          "Expected the accepted reflection option ID for " + lessonId.value() + ".");
    }
    if (lessons.get(index).completedOptionId().isPresent()) {
      return new ProgressTransition.AlreadyApplied(this);
    }
    if (!activeLessonId.equals(Optional.of(lessonId))
        || lesson.prerequisites().stream()
            .anyMatch(id -> lessons.get(lessonIndex(id)).completedOptionId().isEmpty())) {
      return new ProgressTransition.InvalidPrerequisite(this);
    }
    List<LessonProgress> updated = new ArrayList<>(lessons);
    updated.set(
        index, new LessonProgress(lessonId, lessons.get(index).hintLevel(), Optional.of(optionId)));
    if (index + 1 == lessons.size()) {
      return new ProgressTransition.CourseComplete(
          new CourseProgress(course, formatVersion, updated, Optional.empty(), Optional.empty()));
    }
    return new ProgressTransition.Advanced(
        new CourseProgress(
            course,
            formatVersion,
            updated,
            Optional.of(course.lessonOrder().get(index + 1)),
            Optional.ofNullable(nextRevision)));
  }

  private int lessonIndex(LessonId id) {
    int index = course.lessonOrder().indexOf(id);
    if (index < 0) {
      throw new IllegalArgumentException("Lesson is absent from course: " + id.value());
    }
    return index;
  }

  private static void validateRevision(String revision) {
    if (revision.isBlank() || revision.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("Expected a nonblank revision without whitespace.");
    }
  }
}
