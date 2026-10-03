package org.fruitandfaults.progress.application;

import java.io.IOException;

/** A persisted progress document that must be preserved for diagnosis or a compatible reader. */
public final class ProgressReadException extends IOException {
  private final Reason reason;

  /** Categories callers can handle without inspecting Jackson exceptions or diagnostic text. */
  public enum Reason {
    /** JSON syntax, fields, or types do not match the schema. */
    MALFORMED,
    /** The progress schema version is unsupported. */
    UNSUPPORTED_FORMAT,
    /** Course identity or content version does not match the installed course. */
    INCOMPATIBLE_COURSE,
    /** The document describes impossible lesson progress. */
    INVALID_STATE
  }

  /**
   * Constructs an actionable boundary failure with a stable category.
   *
   * @param reason stable failure category
   * @param message actionable diagnostic without learner source or local paths
   */
  public ProgressReadException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  /**
   * Returns the stable category of the invalid persisted document.
   *
   * @return failure category
   */
  public Reason reason() {
    return reason;
  }
}
