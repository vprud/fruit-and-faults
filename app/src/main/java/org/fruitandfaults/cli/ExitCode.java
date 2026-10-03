package org.fruitandfaults.cli;

/** Stable process exit codes for the course CLI. */
public enum ExitCode {
  /** The command completed successfully. */
  SUCCESS(0),
  /** Expected learning work remains incomplete. */
  INCOMPLETE(1),
  /** Arguments are invalid or the command was invoked outside a workspace. */
  INVALID_ARGUMENTS(2),
  /** The workspace has conflicting files or an unsafe state. */
  WORKSPACE_CONFLICT(3),
  /** Compilation or tests failed. */
  VALIDATION_FAILURE(4),
  /** Validation timed out or was interrupted. */
  VALIDATION_INTERRUPTED(5),
  /** An internal course or CLI error occurred. */
  INTERNAL_ERROR(10);

  private final int value;

  ExitCode(int value) {
    this.value = value;
  }

  /**
   * Returns the stable process exit value.
   *
   * @return numeric exit code
   */
  public int value() {
    return value;
  }
}
