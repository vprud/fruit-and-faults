package org.fruitandfaults.progress.domain;

import java.util.Objects;
import java.util.Optional;

import org.fruitandfaults.course.domain.LessonId;

/**
 * Persistable facts about a lesson, with completion represented by its accepted answer.
 *
 * @param lessonId stable lesson identity
 * @param hintLevel number of revealed hints, zero through three
 * @param completedOptionId accepted reflection option, or empty before completion
 */
public record LessonProgress(LessonId lessonId, int hintLevel, Optional<String> completedOptionId) {
  /** Validates hint bounds and requires explicit optional completion. */
  public LessonProgress {
    Objects.requireNonNull(lessonId);
    Objects.requireNonNull(completedOptionId);
    if (hintLevel < 0 || hintLevel > 3) {
      throw new IllegalArgumentException(
          "Expected hint level from 0 to 3; observed " + hintLevel + ".");
    }
  }
}
