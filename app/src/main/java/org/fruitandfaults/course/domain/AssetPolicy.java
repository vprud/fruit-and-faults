package org.fruitandfaults.course.domain;

/** Ownership and verification policy for a disclosed lesson asset. */
public enum AssetPolicy {
  /** Course-owned visible check that must retain its disclosed bytes. */
  IMMUTABLE_CHECK,
  /** Learner-owned test template expected to change after disclosure. */
  EDITABLE_TEMPLATE,
  /** Learner-owned starter or scaffold that may be freely edited. */
  LEARNER_SCAFFOLD
}
