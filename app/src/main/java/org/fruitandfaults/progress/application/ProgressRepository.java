package org.fruitandfaults.progress.application;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import org.fruitandfaults.progress.domain.CourseProgress;

/** A workspace-scoped source and sink of validated course progress. */
public interface ProgressRepository {
  /**
   * Loads valid progress; empty means only that no state file exists.
   *
   * @param workspaceRoot selected learner workspace
   * @return current progress, or empty for an uninitialized workspace
   * @throws ProgressReadException if persisted data is malformed, unsupported, or inconsistent
   * @throws IOException if workspace access is unsafe or fails
   */
  Optional<CourseProgress> load(Path workspaceRoot) throws IOException;

  /**
   * Replaces valid tool-owned state only after a complete new document has been written.
   *
   * @param workspaceRoot selected learner workspace
   * @param progress valid new progress
   * @throws ProgressReadException if existing state cannot safely be interpreted
   * @throws IOException if workspace access or replacement fails
   */
  void save(Path workspaceRoot, CourseProgress progress) throws IOException;
}
