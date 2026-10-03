package org.fruitandfaults.git.application;

import java.io.IOException;
import java.util.OptionalInt;

/** Bounded process diagnostics retained for explicit debug output, with safe default messages. */
public final class GitInitializationException extends IOException {
  private static final long serialVersionUID = 1L;
  private final Reason reason;
  private final OptionalInt exitCode;
  private final String diagnostics;

  /**
   * Keeps observed process facts distinct from human-oriented default output.
   *
   * @param reason process failure category
   * @param exitCode observed exit code, if the process completed
   * @param diagnostics bounded process output for explicit debugging
   */
  public GitInitializationException(Reason reason, OptionalInt exitCode, String diagnostics) {
    super(
        switch (reason) {
          case EXIT_FAILURE ->
              "Git init failed with exit code "
                  + exitCode.orElse(-1)
                  + "; inspect Git diagnostics.";
          case TIMEOUT ->
              "Git init exceeded its deadline; inspect the retained directory before retrying.";
          case INTERRUPTED ->
              "Git init was interrupted; inspect the retained directory before retrying.";
          case UNAVAILABLE ->
              "Git could not be launched or supervised; install Git and inspect the retained directory.";
        });
    this.reason = reason;
    this.exitCode = exitCode;
    this.diagnostics = diagnostics;
  }

  /**
   * Returns the process failure category.
   *
   * @return process failure category
   */
  public Reason reason() {
    return reason;
  }

  /**
   * Returns the observed process exit status.
   *
   * @return observed exit code, if available
   */
  public OptionalInt exitCode() {
    return exitCode;
  }

  /**
   * Returns captured process output for explicit debugging.
   *
   * @return bounded captured output, for explicit debug mode only
   */
  public String diagnostics() {
    return diagnostics;
  }

  /** Explicit process outcomes requiring different useful next actions. */
  public enum Reason {
    /** Git returned a nonzero exit code. */
    EXIT_FAILURE,
    /** Git exceeded the configured bounded runtime. */
    TIMEOUT,
    /** Cancellation interrupted process supervision. */
    INTERRUPTED,
    /** Git launch or output supervision failed. */
    UNAVAILABLE
  }
}
