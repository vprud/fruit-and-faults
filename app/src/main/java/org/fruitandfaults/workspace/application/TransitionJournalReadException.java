package org.fruitandfaults.workspace.application;

import java.io.IOException;
import java.util.Objects;

/** A journal that cannot safely authorize recovery and must be preserved. */
public final class TransitionJournalReadException extends IOException {
  private static final long serialVersionUID = 1L;
  private final Reason reason;

  /**
   * Creates a typed actionable diagnostic without learner content.
   *
   * @param reason precise failure category
   * @param message expected state and next useful action
   */
  public TransitionJournalReadException(Reason reason, String message) {
    super(message);
    this.reason = Objects.requireNonNull(reason);
  }

  /**
   * Returns the distinction needed for repair or compatible reading.
   *
   * @return precise failure category
   */
  public Reason reason() {
    return reason;
  }

  /** Supported invalid-journal categories. */
  public enum Reason {
    /** JSON shape, fields, types, or size are invalid. */
    MALFORMED,
    /** The journal format is unknown and must not be interpreted. */
    UNSUPPORTED_FORMAT,
    /** Facts do not describe the exact installed transition. */
    INVALID_STATE
  }
}
