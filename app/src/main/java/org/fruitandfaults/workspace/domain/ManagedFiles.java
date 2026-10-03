package org.fruitandfaults.workspace.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * An immutable ownership snapshot with one record per normalized destination.
 *
 * @param files ordered disclosed ownership facts
 */
public record ManagedFiles(List<ManagedFile> files) {
  /** Copies the snapshot and rejects ambiguous duplicate destinations. */
  public ManagedFiles {
    files = List.copyOf(files);
    Set<WorkspacePath> paths = new HashSet<>();
    for (ManagedFile file : files) {
      if (!paths.add(file.path())) {
        throw new IllegalArgumentException("Duplicate managed path: " + file.path().value());
      }
    }
  }

  /**
   * Creates a snapshot for a workspace with no disclosed files.
   *
   * @return empty ownership snapshot
   */
  public static ManagedFiles empty() {
    return new ManagedFiles(List.of());
  }

  /**
   * Finds recorded ownership without treating an unknown file as tool-owned.
   *
   * @param path logical destination
   * @return recorded file, if known
   */
  public Optional<ManagedFile> find(WorkspacePath path) {
    return files.stream().filter(file -> file.path().equals(path)).findFirst();
  }

  /**
   * Finds ambiguous portable destinations independently of serialized input validation.
   *
   * @return path-level conflicts for later case or Unicode aliases
   */
  public List<DisclosureConflict> aliasConflicts() {
    Set<String> aliases = new HashSet<>();
    List<DisclosureConflict> conflicts = new ArrayList<>();
    for (ManagedFile file : files) {
      if (!aliases.add(file.path().aliasKey())) {
        conflicts.add(
            new DisclosureConflict(file.path(), DisclosureConflict.Reason.CASE_COLLISION));
      }
    }
    return List.copyOf(conflicts);
  }
}
