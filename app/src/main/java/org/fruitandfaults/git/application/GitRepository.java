package org.fruitandfaults.git.application;

import java.io.IOException;
import java.nio.file.Path;

/** The only authorized Git mutation: initializing a new learner repository. */
@FunctionalInterface
public interface GitRepository {
  /**
   * Initializes Git without staging, committing, configuring a remote, or contacting the network.
   *
   * @param root existing real learner directory
   * @throws IOException if initialization fails, times out, or is interrupted
   */
  void initialize(Path root) throws IOException;
}
