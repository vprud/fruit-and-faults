package org.fruitandfaults.workspace.application;

import java.io.IOException;

/** A discovery failure that callers can map to invocation or workspace diagnostics. */
public final class WorkspaceLocationException extends IOException {
  private static final long serialVersionUID = 1L;
  private final Reason reason;

  /**
   * Creates an actionable categorized failure without exposing learner contents.
   *
   * @param reason failure category
   * @param message useful next action
   */
  public WorkspaceLocationException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  /**
   * Returns the explicit failure category.
   *
   * @return discovery failure category
   */
  public Reason reason() {
    return reason;
  }

  /** Distinctions needed by command exit codes and recovery guidance. */
  public enum Reason {
    /** No marker exists among ancestors. */
    NOT_FOUND,
    /** More than one nested workspace marker exists. */
    AMBIGUOUS,
    /** The course identity or layout is unsupported. */
    INCOMPATIBLE,
    /** Symlink, malformed marker, or unsafe directory access. */
    UNSAFE
  }
}
