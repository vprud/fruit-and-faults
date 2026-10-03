package org.fruitandfaults.workspace.domain;

import java.util.Objects;

/**
 * A precise logical destination and the reason disclosure must stop.
 *
 * @param path affected normalized path
 * @param reason actionable conflict category
 */
public record DisclosureConflict(WorkspacePath path, Reason reason) {
  /** Requires an exact path and reason. */
  public DisclosureConflict {
    Objects.requireNonNull(path);
    Objects.requireNonNull(reason);
  }

  /** Explicit alternatives for conflict reporting and recovery decisions. */
  public enum Reason {
    /** Two requested assets have the same logical destination. */
    DUPLICATE_TARGET,
    /** Different logical spellings address the same filesystem destination. */
    CASE_COLLISION,
    /** A requested file is also a parent directory of another requested file. */
    TARGET_ANCESTOR,
    /** Existing file has no recorded ownership, even if its bytes match. */
    EXISTING_UNMANAGED,
    /** Recorded identity, lesson, hash, or policy differs from the requested asset. */
    OWNERSHIP_MISMATCH,
    /** A recorded file has changed since disclosure; preserve the learner's bytes. */
    CONTENT_MISMATCH,
    /** A symlink or non-regular path blocks access; select a real directory or move it aside. */
    UNSAFE_PATH
  }
}
