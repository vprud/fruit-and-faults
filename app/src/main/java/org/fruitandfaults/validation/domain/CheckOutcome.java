package org.fruitandfaults.validation.domain;

import java.util.List;
import java.util.Objects;

/** Explicit validation success or failure, with no process, filesystem, or CLI dependency. */
public sealed interface CheckOutcome {
  /**
   * Returns safely described validation facts and next actions.
   *
   * @return immutable actionable diagnostics
   */
  List<Diagnostic> diagnostics();

  /**
   * The checks performed by this validation stage passed.
   *
   * @param diagnostics observations for subsequent stages or learner presentation
   */
  record Passed(List<Diagnostic> diagnostics) implements CheckOutcome {
    /**
     * Copies observations so later adapter mutation cannot change the outcome.
     *
     * @param diagnostics safely described observations
     */
    public Passed {
      diagnostics = List.copyOf(diagnostics);
    }
  }

  /**
   * A validation stage failed without changing learner progress.
   *
   * @param category meaningful failure alternative
   * @param diagnostics at least one expected/observed/next-action explanation
   */
  record Failed(FailureCategory category, List<Diagnostic> diagnostics) implements CheckOutcome {
    /**
     * Requires a category and at least one immutable actionable observation.
     *
     * @param category meaningful failure alternative
     * @param diagnostics at least one actionable observation
     */
    public Failed {
      Objects.requireNonNull(category);
      diagnostics = List.copyOf(diagnostics);
      if (diagnostics.isEmpty()) {
        throw new IllegalArgumentException("A failed check requires an actionable diagnostic.");
      }
    }
  }
}
