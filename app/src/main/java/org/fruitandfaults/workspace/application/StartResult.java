package org.fruitandfaults.workspace.application;

import java.nio.file.Path;
import java.util.List;

import org.fruitandfaults.progress.domain.CourseProgress;

/** Explicit start outcomes for CLI presentation without exception or stack-trace output. */
public sealed interface StartResult {
  /**
   * A read-only preview awaiting confirmation.
   *
   * @param root chosen destination
   * @param paths exact root, Git directory, asset and state paths affected
   */
  record PreviewRequired(Path root, List<Path> paths) implements StartResult {
    /**
     * Copies the preview so confirmation cannot alter its listed paths.
     *
     * @param root chosen destination
     * @param paths exact affected paths
     */
    public PreviewRequired {
      paths = List.copyOf(paths);
    }
  }

  /**
   * A freshly initialized workspace with committed lesson-one progress.
   *
   * @param workspace initialized identity and location
   * @param progress committed initial progress
   */
  record Created(WorkspaceRoot workspace, CourseProgress progress) implements StartResult {}

  /**
   * A compatible workspace resumed without replacing learner work.
   *
   * @param workspace compatible identity and location
   * @param progress current committed progress
   */
  record Resumed(WorkspaceRoot workspace, CourseProgress progress) implements StartResult {}

  /**
   * A preserved incompatible or unsafe destination.
   *
   * @param root selected destination
   * @param diagnostic actionable explanation
   * @param paths affected conflicting paths
   */
  record Conflict(Path root, String diagnostic, List<Path> paths) implements StartResult {
    /**
     * Copies exact conflicting paths without capturing learner contents.
     *
     * @param root selected destination
     * @param diagnostic actionable explanation
     * @param paths exact conflicting paths
     */
    public Conflict {
      paths = List.copyOf(paths);
    }
  }

  /**
   * Initialization failed, with existing files retained for inspection or recovery.
   *
   * @param root selected destination
   * @param stage last attempted operation
   * @param diagnostic explanation of retained state and useful next action
   */
  record Failed(Path root, Stage stage, String diagnostic) implements StartResult {}

  /** Last attempted mutation, distinguishing which state may already exist. */
  enum Stage {
    /** Creation of the chosen destination. */
    DIRECTORY_CREATION,
    /** Git init, before any course-owned state is created. */
    GIT_INITIALIZATION,
    /** Exclusive creation of the portable workspace marker. */
    METADATA_CREATION,
    /** Recoverable lesson disclosure with progress committed last. */
    DISCLOSURE
  }
}
