package org.fruitandfaults.course.application;

import org.fruitandfaults.course.domain.Lesson;
import org.fruitandfaults.validation.domain.Diagnostic;
import org.fruitandfaults.validation.domain.FailureCategory;

/** The active installed lesson, course completion, or a safe read failure. */
public sealed interface LessonResult {
  /**
   * Details of the active lesson only.
   *
   * @param lesson validated installed lesson
   */
  record Active(Lesson lesson) implements LessonResult {}

  /** No active lesson remains. */
  record CourseComplete() implements LessonResult {}

  /**
   * No lesson text is exposed when saved state or installed content cannot be read safely.
   *
   * @param category typed failure
   * @param diagnostic safe actionable feedback
   */
  record Unavailable(FailureCategory category, Diagnostic diagnostic) implements LessonResult {}
}
