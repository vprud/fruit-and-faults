package org.fruitandfaults.workspace.domain;

/** Observable outcomes of applying or recovering a disclosure transaction. */
public enum TransitionStatus {
  /** The requested transaction completed. */
  APPLIED,
  /** A durable incomplete transaction was resumed and completed. */
  RECOVERED,
  /** The same transaction was already committed, or no recovery is pending. */
  ALREADY_APPLIED,
  /** Conflicting state was preserved and the transaction did not advance. */
  CONFLICT
}
