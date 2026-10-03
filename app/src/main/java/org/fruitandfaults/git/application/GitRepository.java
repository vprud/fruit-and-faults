package org.fruitandfaults.git.application;

import java.io.IOException;
import java.nio.file.Path;

/** Safe repository validation and the sole authorized mutation, new repository initialization. */
public interface GitRepository {
  /**
   * Initializes Git without staging, committing, configuring a remote, or contacting the network.
   *
   * @param root existing real learner directory
   * @throws IOException if initialization fails, times out, or is interrupted
   */
  void initialize(Path root) throws IOException;

  /**
   * Requires an existing non-bare repository rooted exactly at the real workspace .git directory.
   * This operation must not initialize, repair, stage, commit, or contact the network.
   *
   * @param root existing real learner directory
   * @throws IOException if Git state is absent, unsafe, incompatible, or cannot be validated
   */
  void requireInitialized(Path root) throws IOException;
}
