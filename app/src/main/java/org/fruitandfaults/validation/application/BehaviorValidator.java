package org.fruitandfaults.validation.application;

import java.nio.file.Path;

import org.fruitandfaults.validation.domain.CheckOutcome;

/** Checks one stable completion criterion using only public observable game behavior. */
@FunctionalInterface
public interface BehaviorValidator {
  /**
   * Validates freshly compiled learner main classes without starting a framework.
   *
   * @param root selected normalized learner workspace
   * @return typed bounded learner-facing observations
   */
  CheckOutcome validate(Path root);
}
