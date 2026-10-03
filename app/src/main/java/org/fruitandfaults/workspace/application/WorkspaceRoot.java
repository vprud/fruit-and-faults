package org.fruitandfaults.workspace.application;

import java.nio.file.Path;
import java.util.Objects;

import org.fruitandfaults.workspace.domain.WorkspaceMetadata;

/**
 * A discovered workspace location and its validated portable identity.
 *
 * @param path normalized absolute root
 * @param metadata validated workspace identity
 */
public record WorkspaceRoot(Path path, WorkspaceMetadata metadata) {
  /** Normalizes the application location without persisting it in domain metadata. */
  public WorkspaceRoot {
    path = path.toAbsolutePath().normalize();
    Objects.requireNonNull(metadata);
  }
}
