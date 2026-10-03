package org.fruitandfaults.workspace.application;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import org.fruitandfaults.workspace.domain.ManagedFiles;

/** Workspace-scoped persistence of validated, versioned ownership snapshots. */
public interface ManagedFilesRepository {
  /**
   * Loads a valid ownership snapshot without altering the workspace.
   *
   * @param workspaceRoot selected existing learner workspace
   * @return recorded ownership, or empty only if the manifest is absent
   * @throws ManagedFilesReadException if the document is malformed, unsupported, or invalid
   * @throws IOException if access is unsafe or fails
   */
  Optional<ManagedFiles> load(Path workspaceRoot) throws IOException;

  /**
   * Creates absent state exclusively or replaces valid state after a flushed temporary write.
   * Initial creation writes directly to the reserved entry; failures may leave a partial or
   * ambiguous document requiring recovery rather than permission to overwrite or delete it.
   *
   * @param workspaceRoot selected existing learner workspace
   * @param managed validated ownership snapshot
   * @throws ManagedFilesReadException if existing state cannot safely be interpreted
   * @throws IOException if access, writing, or replacement fails
   */
  void save(Path workspaceRoot, ManagedFiles managed) throws IOException;
}
