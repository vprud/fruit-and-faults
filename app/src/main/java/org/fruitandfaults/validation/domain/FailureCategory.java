package org.fruitandfaults.validation.domain;

/** Stable meanings for validation feedback without delivery-layer exit codes. */
public enum FailureCategory {
  /** Required learner work, such as editing a test template, remains incomplete. */
  INCOMPLETE_WORK,
  /** An unsafe path, altered immutable check, or conflicting ownership prevents validation. */
  WORKSPACE_CONFLICT,
  /** Java source or visible tests did not compile. */
  COMPILATION_ERROR,
  /** Visible tests ran and reported a failure. */
  TEST_FAILURE,
  /** A required course or build artifact is absent. */
  MISSING_ARTIFACT,
  /** The bounded validation deadline expired. */
  TIMEOUT,
  /** The learner or caller cancelled validation. */
  INTERRUPTED,
  /** Infrastructure failed without sufficient evidence to attribute it to learner work. */
  INTERNAL_ERROR
}
