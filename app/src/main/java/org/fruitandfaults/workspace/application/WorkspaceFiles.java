package org.fruitandfaults.workspace.application;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.fruitandfaults.workspace.domain.DisclosurePlan;
import org.fruitandfaults.workspace.domain.ManagedFile;
import org.fruitandfaults.workspace.domain.ManagedFiles;
import org.fruitandfaults.workspace.domain.WorkspacePath;

/** The filesystem boundary for learner-owned destinations; no operation overwrites them. */
public interface WorkspaceFiles {
  /**
   * Observes anchored metadata only, without opening file content or calculating fingerprints.
   *
   * @param root selected real learner workspace
   * @param path normalized relative artifact
   * @return regular-file presence, absence, or an unsafe entry
   * @throws IOException if metadata access is unsupported or changes concurrently
   */
  default Presence presence(Path root, WorkspacePath path) throws IOException {
    throw new IOException("Cheap artifact metadata inspection is unavailable.");
  }

  /** Cheap observations that do not establish compilation, test integrity, or behavior. */
  enum Presence {
    /** A regular file exists; its contents have not been validated. */
    PRESENT,
    /** The artifact or a parent is absent. */
    MISSING,
    /** A symlink, special file, or inappropriate directory prevents safe access. */
    UNSAFE
  }

  /**
   * Reads at most 16 MiB from one regular file through verified directory handles. Callers must put
   * untrusted file opens and reads in an owned deadline-bound process: a byte budget alone cannot
   * bound an operating-system special-file replacement race.
   *
   * @param root selected existing workspace
   * @param path normalized logical file path
   * @return bounded bytes, or empty when a path segment is absent
   * @throws IOException if unsafe, unsupported, changed during reading, or oversized
   */
  Optional<byte[]> read(Path root, WorkspacePath path) throws IOException;

  /**
   * Inspects a logical destination without reading through workspace symlinks.
   *
   * @param root selected existing workspace
   * @param path logical destination
   * @return explicit missing, regular-file fingerprint, or unsafe-path observation
   * @throws IOException if the selected root or access is invalid
   */
  DisclosurePlan.Observation inspect(Path root, WorkspacePath path) throws IOException;

  /**
   * Inspects all requested destinations without creating directories or files.
   *
   * @param root selected existing workspace
   * @param requested ordered asset facts
   * @param managed current ownership snapshot
   * @return applicable plan or exact conflicts
   * @throws IOException if the selected root or access is invalid
   */
  DisclosurePlan preflight(Path root, List<ManagedFile> requested, ManagedFiles managed)
      throws IOException;

  /**
   * Exclusively creates and writes through a verified directory handle. Bytes are visible during
   * writing. Success requires the target's bytes to match the requested bytes. Failures retain
   * partial or ambiguous targets for recovery, never as overwrite or deletion permission.
   *
   * @param root selected existing workspace
   * @param path missing logical destination
   * @param bytes raw asset bytes
   * @throws IOException if unsafe, already present, unsupported, or writing fails
   */
  void writeNewSafely(Path root, WorkspacePath path, byte[] bytes) throws IOException;

  /**
   * Rechecks every target and source fingerprint before the first batch write. Multi-file crash
   * recovery remains the disclosure use case's responsibility.
   *
   * @param root selected existing workspace
   * @param plan previewed disclosure decision
   * @param contents raw bytes for each missing file
   * @throws IOException if any preflight fact or source bytes no longer match
   */
  void writeNewSafely(Path root, DisclosurePlan plan, Map<WorkspacePath, byte[]> contents)
      throws IOException;
}
