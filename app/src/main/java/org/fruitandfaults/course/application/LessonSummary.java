package org.fruitandfaults.course.application;

import org.fruitandfaults.course.domain.LessonId;

/**
 * A route view with no instructions, hints, answers, tests, or implementation details.
 *
 * @param id stable lesson identity
 * @param title public route title
 * @param state learner progress along the ordered route
 */
public record LessonSummary(LessonId id, String title, State state) {
  /** Observable progress states for route listings. */
  public enum State {
    /** The lesson was completed. */
    COMPLETED,
    /** The lesson is currently open. */
    ACTIVE,
    /** An appended lesson is available to open through next. */
    AVAILABLE,
    /** A future lesson has not been opened. */
    LOCKED
  }
}
