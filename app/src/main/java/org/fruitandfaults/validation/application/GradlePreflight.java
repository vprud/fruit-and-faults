package org.fruitandfaults.validation.application;

import java.nio.file.Path;

import org.fruitandfaults.validation.domain.CheckOutcome;

/**
 * Verifies the exact locally installed wrapper distribution before bootstrap can contact a server.
 */
@FunctionalInterface
public interface GradlePreflight {
  /**
   * Checks cache readiness without downloading, repairing, or launching the wrapper.
   *
   * @param workspaceRoot normalized selected learner workspace
   * @return verified pinned Gradle user home or a typed safe failure
   */
  Preparation prepare(Path workspaceRoot);

  /** Exhaustive readiness alternatives, never a boolean or absent diagnostic. */
  sealed interface Preparation permits Ready, Unavailable {}

  /**
   * A selected cache verified ready for bootstrap.
   *
   * @param gradleUserHome explicit normalized home to pass to the wrapper
   */
  record Ready(Path gradleUserHome) implements Preparation {
    /**
     * Normalizes the pinned path before command construction.
     *
     * @param gradleUserHome selected verified native Gradle cache home
     */
    public Ready {
      gradleUserHome = gradleUserHome.toAbsolutePath().normalize();
    }
  }

  /**
   * Missing, unsafe, malformed, or unavailable infrastructure; the wrapper must not run.
   *
   * @param outcome sanitized learner-facing failure
   */
  record Unavailable(CheckOutcome.Failed outcome) implements Preparation {}
}
