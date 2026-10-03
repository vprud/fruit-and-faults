package org.fruitandfaults.validation.domain;

import java.util.Objects;

/**
 * An actionable observation independent of terminal formatting or exception types.
 *
 * @param expected required observable state
 * @param observed actual safely described state
 * @param nextAction useful learner action
 */
public record Diagnostic(String expected, String observed, String nextAction) {
  /** Requires all three parts so a failure cannot omit the next useful action. */
  public Diagnostic {
    Objects.requireNonNull(expected);
    Objects.requireNonNull(observed);
    Objects.requireNonNull(nextAction);
    if (expected.isBlank() || observed.isBlank() || nextAction.isBlank()) {
      throw new IllegalArgumentException("Expected, observed, and next action must be non-blank.");
    }
  }
}
