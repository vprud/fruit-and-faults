package org.fruitandfaults.validation.application;

import java.util.Objects;

/**
 * Process facts kept separate from learner validation decisions. Output is untrusted UTF-8 text.
 */
public sealed interface ProcessResult {
  /**
   * Returns bounded tails and explicit stream truncation facts.
   *
   * @return raw captured output, which must be sanitized before terminal presentation
   */
  Output output();

  /**
   * A normal exit with its exact status.
   *
   * @param exitCode process exit status, zero or non-zero
   * @param output retained stream tails
   */
  record Exited(int exitCode, Output output) implements ProcessResult {}

  /**
   * The deadline expired before process or output completion.
   *
   * @param output retained stream tails
   * @param cleanup whether owned processes and drain threads terminated
   */
  record TimedOut(Output output, Cleanup cleanup) implements ProcessResult {}

  /**
   * The caller cancelled execution; its interrupt flag is restored before returning.
   *
   * @param output retained stream tails
   * @param cleanup whether owned processes and drain threads terminated
   */
  record Interrupted(Output output, Cleanup cleanup) implements ProcessResult {}

  /**
   * The process could not be launched or reliably observed, without leaking exception text.
   *
   * @param reason typed infrastructure failure
   * @param diagnostic safe adapter explanation without arguments, environment, or local paths
   * @param output retained stream tails, empty if no process started
   */
  record Failed(FailureReason reason, String diagnostic, Output output) implements ProcessResult {}

  /** Infrastructure alternatives that must never be silently blamed on learner source. */
  enum FailureReason {
    /** Missing executable, denied permission, or another launch failure. */
    UNAVAILABLE,
    /** Working directory is absent, not a directory, or traverses a symlink. */
    INVALID_WORKING_DIRECTORY,
    /** Reading process output failed. */
    OUTPUT_FAILURE,
    /** Process-tree support, ownership limit, or termination could not be verified. */
    CLEANUP_FAILURE
  }

  /** Explicit bounded-cleanup evidence, including platform limitations. */
  enum Cleanup {
    /** All observed descendants, the direct process, and owned drain threads stopped. */
    COMPLETE,
    /** At least one owned process or drain thread remained after the cleanup deadline. */
    INCOMPLETE,
    /** This process provider cannot enumerate or terminate descendant handles. */
    UNSUPPORTED
  }

  /**
   * Independently retained stream tails sharing one aggregate byte budget. Malformed UTF-8 and
   * incomplete leading/trailing characters are discarded rather than expanding the byte budget.
   *
   * @param stdout retained standard-output tail
   * @param stderr retained standard-error tail
   * @param stdoutTruncated whether any stdout bytes were discarded by its budget
   * @param stderrTruncated whether any stderr bytes were discarded by its budget
   */
  record Output(String stdout, String stderr, boolean stdoutTruncated, boolean stderrTruncated) {
    /**
     * Requires complete non-null stream values.
     *
     * @param stdout retained standard-output tail
     * @param stderr retained standard-error tail
     * @param stdoutTruncated whether stdout exceeded its capture budget
     * @param stderrTruncated whether stderr exceeded its capture budget
     */
    public Output {
      Objects.requireNonNull(stdout);
      Objects.requireNonNull(stderr);
    }

    /**
     * Returns the output fact for a request that launched no process.
     *
     * @return empty complete streams
     */
    public static Output empty() {
      return new Output("", "", false, false);
    }
  }
}
