package org.fruitandfaults.workspace.application;

import java.io.IOException;
import java.nio.file.Path;

/** Rediscovers portable workspace identity from the current directory on every invocation. */
@FunctionalInterface
public interface WorkspaceLocator {
  /**
   * Locates the one compatible marker among all current-directory ancestors.
   *
   * @param current existing invocation directory
   * @return safely resolved workspace
   * @throws IOException if absent, ambiguous, incompatible, unsafe, or inaccessible
   */
  WorkspaceRoot locate(Path current) throws IOException;
}
