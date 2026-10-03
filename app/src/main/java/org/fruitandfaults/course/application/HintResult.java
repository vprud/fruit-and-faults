package org.fruitandfaults.course.application;

import org.fruitandfaults.course.domain.LessonId;
import org.fruitandfaults.validation.domain.Diagnostic;
import org.fruitandfaults.validation.domain.FailureCategory;

/** A selected active hint, a completed course, or a safe failure without hint disclosure. */
public sealed interface HintResult {
  /**
   * A successfully loaded hint whose revealed level was persisted before returning.
   *
   * @param lessonId active lesson only
   * @param level revealed level from one through three
   * @param text selected course-owned hint text
   */
  record Revealed(LessonId lessonId, int level, String text) implements HintResult {}

  /** No lesson is currently open; future hints cannot be disclosed. */
  record CourseComplete() implements HintResult {}

  /**
   * No hint is disclosed when content, state, or persistence fails.
   *
   * @param category typed safe failure
   * @param diagnostic actionable explanation without untrusted exception details
   */
  record Unavailable(FailureCategory category, Diagnostic diagnostic) implements HintResult {}
}
