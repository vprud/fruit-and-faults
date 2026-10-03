package org.fruitandfaults.workspace.application;

import java.io.IOException;
import java.util.Objects;

/** An invalid ownership manifest that must be preserved for repair or compatible reading. */
public final class ManagedFilesReadException extends IOException {
  private static final long serialVersionUID = 1L;
  private final Reason reason;

  /**
   * Describes a manifest failure without exposing learner source or machine-local paths.
   *
   * @param reason precise read category
   * @param message actionable diagnostic
   */
  public ManagedFilesReadException(Reason reason, String message) {
    super(message);
    this.reason = Objects.requireNonNull(reason);
  }

  /**
   * Returns the distinction callers must retain when reporting the failure.
   *
   * @return precise manifest failure category
   */
  public Reason reason() {
    return reason;
  }

  /** Supported categories of invalid persisted ownership. */
  public enum Reason {
    /** Invalid JSON, shape, size, field set, or field type. */
    MALFORMED,
    /** An unsupported format version must not be interpreted or rewritten. */
    UNSUPPORTED_FORMAT,
    /** Facts violate normalized paths, identity, fingerprint, policy, or uniqueness. */
    INVALID_STATE
  }
}
