package org.fruitandfaults.validation.infra;

import java.io.IOException;
import java.nio.file.Path;

import org.fruitandfaults.workspace.application.WorkspaceFiles;
import org.fruitandfaults.workspace.domain.WorkspacePath;
import org.fruitandfaults.workspace.infra.SafeWorkspaceFiles;

/**
 * Reads one declared artifact only in an owned worker; a special-file race cannot block the CLI.
 */
public final class ArtifactReadWorker {
  private ArtifactReadWorker() {}

  /**
   * Produces an authenticated fixed-size fingerprint, missing token, or sanitized failure token.
   *
   * @param arguments one normalized logical workspace path
   */
  public static void main(String[] arguments) {
    run(new SafeWorkspaceFiles(), arguments);
  }

  static void run(WorkspaceFiles files, String[] arguments) {
    if (arguments.length != 1) {
      return;
    }
    byte[] secret;
    try {
      secret = WorkerProtocol.consumeSecret();
    } catch (IOException invalidInput) {
      return;
    }
    String payload;
    try {
      var bytes = files.read(Path.of(""), WorkspacePath.parse(arguments[0]));
      if (bytes.isEmpty()) {
        payload = "MISSING";
      } else {
        ArtifactFingerprint fingerprint = ArtifactFingerprint.of(bytes.orElseThrow());
        payload = "REGULAR:" + fingerprint.sha256() + ":" + fingerprint.textSha256();
      }
    } catch (IOException unsafe) {
      payload = "WORKSPACE_CONFLICT";
    } catch (RuntimeException | Error failed) {
      payload = "INTERNAL_ERROR";
    }
    System.out.println(WorkerProtocol.frame(secret, payload));
    java.util.Arrays.fill(secret, (byte) 0);
  }
}
