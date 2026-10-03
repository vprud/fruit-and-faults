package org.fruitandfaults.workspace.application;

import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;

import org.jspecify.annotations.Nullable;

/** Preserves cancellation evidence at workspace I/O and application delivery boundaries. */
public final class WorkspaceCancellation {
  private WorkspaceCancellation() {}

  /**
   * Recognizes direct or wrapped interruption and restores, never clears, the thread flag.
   *
   * @param failure optional boundary exception, inspected through at most 32 causes
   * @return whether the current operation has cancellation evidence
   */
  public static boolean restoreIfInterrupted(@Nullable Throwable failure) {
    boolean interrupted = Thread.currentThread().isInterrupted();
    for (int count = 0; failure != null && count < 32; count++, failure = failure.getCause()) {
      if (failure instanceof InterruptedIOException
          || failure instanceof ClosedByInterruptException
          || failure instanceof InterruptedException) {
        interrupted = true;
        break;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
    return interrupted;
  }
}
