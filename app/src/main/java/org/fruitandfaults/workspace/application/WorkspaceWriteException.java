package org.fruitandfaults.workspace.application;

import java.io.IOException;
import java.util.Objects;

/** A safe publication failure; the caller must preserve state and may retry after fixing access. */
public final class WorkspaceWriteException extends IOException {
  private static final long serialVersionUID = 1L;
  private final Reason reason;

  /**
   * Creates an actionable publication failure without exposing machine-local paths.
   *
   * @param reason precise publication category
   * @param message safe human-oriented diagnostic
   * @param cause original diagnostic for explicit debug output
   */
  public WorkspaceWriteException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = Objects.requireNonNull(reason);
  }

  /**
   * Identifies whether the filesystem lacks safe publication or publication failed.
   *
   * @return precise failure category
   */
  public Reason reason() {
    return reason;
  }

  /** Explicit safe-publication failure categories. */
  public enum Reason {
    /** Provider does not support exclusive publication of a completed asset. */
    UNSUPPORTED_PUBLICATION,
    /** Exclusive publication failed; existing destination content is preserved. */
    PUBLICATION_FAILED
  }
}
