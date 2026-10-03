package org.fruitandfaults.validation.application;

/** Owns a local process, its streams and observed descendants until bounded cleanup finishes. */
@FunctionalInterface
public interface ProcessRunner {
  /**
   * Executes literal arguments and returns launch, exit, timeout or cancellation facts.
   *
   * @param request selected directory and finite resource bounds
   * @return typed process facts; interruption also restores the calling thread's interrupt flag
   */
  ProcessResult run(ProcessRequest request);
}
