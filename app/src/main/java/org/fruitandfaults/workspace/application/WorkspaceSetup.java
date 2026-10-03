package org.fruitandfaults.workspace.application;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import org.fruitandfaults.workspace.domain.WorkspaceMetadata;

/**
 * Safe destination inspection, directory creation, and exclusive workspace identity persistence.
 */
public interface WorkspaceSetup {
  /**
   * Inspects a destination without mutation or symlink traversal.
   *
   * @param target chosen destination
   * @return explicit destination state
   * @throws IOException if the path or marker is unsafe or malformed
   */
  Destination inspect(Path target) throws IOException;

  /**
   * Creates a missing destination after repeating safe inspection.
   *
   * @param target previously previewed destination
   * @throws IOException if the destination changed or creation fails
   */
  void createDirectory(Path target) throws IOException;

  /**
   * Reads bounded, validated metadata without following marker symlinks.
   *
   * @param root existing real directory
   * @return metadata, empty only when the marker is absent
   * @throws IOException if metadata is unsafe, malformed, or inaccessible
   */
  Optional<WorkspaceMetadata> loadMetadata(Path root) throws IOException;

  /**
   * Writes metadata exclusively through an anchored directory handle.
   *
   * @param root existing real directory
   * @param metadata portable identity
   * @throws IOException if the marker exists or writing fails
   */
  void createMetadata(Path root, WorkspaceMetadata metadata) throws IOException;

  /** Destination facts discovered before any initialization. */
  sealed interface Destination permits Missing, Empty, Initialized, Occupied {
    /**
     * Returns the normalized destination.
     *
     * @return absolute root
     */
    Path root();
  }

  /**
   * A safely inspected absent destination.
   *
   * @param root missing destination
   */
  record Missing(Path root) implements Destination {}

  /**
   * A safely inspected empty destination.
   *
   * @param root existing empty real directory
   */
  record Empty(Path root) implements Destination {}

  /**
   * A safely inspected marker-bearing destination. The caller must validate its Git repository
   * before treating it as an initialized workspace.
   *
   * @param workspace existing workspace with validated identity
   */
  record Initialized(WorkspaceRoot workspace) implements Destination {
    @Override
    public Path root() {
      return workspace.path();
    }
  }

  /**
   * A preserved occupied destination.
   *
   * @param root nonempty directory without workspace identity
   */
  record Occupied(Path root) implements Destination {}
}
