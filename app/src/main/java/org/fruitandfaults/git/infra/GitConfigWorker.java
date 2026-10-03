package org.fruitandfaults.git.infra;

import java.io.IOException;
import java.nio.file.Path;

/** Isolates potentially blocking local Git configuration reads from the CLI process. */
public final class GitConfigWorker {
  static final int SAFE = 0;
  static final int WORKSPACE_CONFLICT = 20;
  private static final int INTERNAL_ERROR = 21;

  private GitConfigWorker() {}

  /**
   * Inspects only the selected working directory and reports by fixed exit code without output.
   *
   * @param arguments no arguments are accepted
   */
  public static void main(String[] arguments) {
    if (arguments.length != 0) {
      System.exit(INTERNAL_ERROR);
    }
    try {
      ProcessGitRepository.inspectLocalConfigurations(
          Path.of(""), (name, attributes) -> attributes.fileKey());
    } catch (IOException unsafe) {
      System.exit(WORKSPACE_CONFLICT);
    } catch (RuntimeException | Error failed) {
      System.exit(INTERNAL_ERROR);
    }
  }
}
