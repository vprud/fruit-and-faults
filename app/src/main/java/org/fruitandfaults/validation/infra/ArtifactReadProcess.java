package org.fruitandfaults.validation.infra;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.fruitandfaults.validation.application.ProcessResult;
import org.fruitandfaults.validation.application.ProcessRunner;
import org.fruitandfaults.validation.domain.FailureCategory;
import org.fruitandfaults.workspace.domain.WorkspacePath;

/**
 * Moves every potentially blocking learner-file open/read behind process ownership and a deadline.
 */
final class ArtifactReadProcess {
  private ArtifactReadProcess() {}

  static Optional<ArtifactFingerprint> read(ProcessRunner runner, Path root, WorkspacePath path)
      throws ReadException {
    WorkerProcess.Reply reply =
        WorkerProcess.run(runner, root, ArtifactReadWorker.class, List.of(path.value()));
    switch (reply.result()) {
      case ProcessResult.TimedOut timed ->
          throw new ReadException(FailureCategory.WORKSPACE_CONFLICT, timed.cleanup());
      case ProcessResult.Interrupted interrupted ->
          throw new ReadException(FailureCategory.INTERRUPTED, interrupted.cleanup());
      case ProcessResult.Failed failed ->
          throw new ReadException(
              failed.reason() == ProcessResult.FailureReason.INVALID_WORKING_DIRECTORY
                  ? FailureCategory.WORKSPACE_CONFLICT
                  : FailureCategory.INTERNAL_ERROR);
      case ProcessResult.Exited ignored -> {}
    }
    String payload = reply.payload().orElse("");
    if (payload.equals("MISSING")) {
      return Optional.empty();
    }
    if (payload.equals("WORKSPACE_CONFLICT")) {
      throw new ReadException(FailureCategory.WORKSPACE_CONFLICT);
    }
    if (payload.matches("REGULAR:[0-9a-f]{64}:[0-9a-f]{64}")) {
      return Optional.of(new ArtifactFingerprint(payload.substring(8, 72), payload.substring(73)));
    }
    throw new ReadException(FailureCategory.INTERNAL_ERROR);
  }

  static final class ReadException extends IOException {
    private final FailureCategory category;
    private final ProcessResult.Cleanup cleanup;

    ReadException(FailureCategory category) {
      this(category, ProcessResult.Cleanup.COMPLETE);
    }

    ReadException(FailureCategory category, ProcessResult.Cleanup cleanup) {
      super("Artifact inspection could not finish safely within its bounded worker.");
      this.category = category;
      this.cleanup = cleanup;
    }

    FailureCategory category() {
      return category;
    }

    ProcessResult.Cleanup cleanup() {
      return cleanup;
    }
  }
}
