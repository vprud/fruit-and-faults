package org.fruitandfaults.progress.domain;

/** Meaningful outcomes of an attempted lesson completion. */
public sealed interface ProgressTransition {
  /**
   * Returns the resulting valid state, unchanged when the transition was not applied.
   *
   * @return resulting progress
   */
  CourseProgress progress();

  /**
   * Completion opened the next lesson.
   *
   * @param progress new state
   */
  record Advanced(CourseProgress progress) implements ProgressTransition {}

  /**
   * The same accepted answer was already recorded.
   *
   * @param progress unchanged state
   */
  record AlreadyApplied(CourseProgress progress) implements ProgressTransition {}

  /**
   * The lesson is not yet active or its prerequisites are incomplete.
   *
   * @param progress unchanged state
   */
  record InvalidPrerequisite(CourseProgress progress) implements ProgressTransition {}

  /**
   * Completion finished the entire course.
   *
   * @param progress terminal state
   */
  record CourseComplete(CourseProgress progress) implements ProgressTransition {}
}
