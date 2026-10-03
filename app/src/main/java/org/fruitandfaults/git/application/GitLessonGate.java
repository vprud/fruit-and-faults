package org.fruitandfaults.git.application;

import org.jspecify.annotations.Nullable;

/** Requires a new local commit and clean worktree; publishing advice never blocks a lesson. */
public final class GitLessonGate {
  private GitLessonGate() {}

  /**
   * Evaluates local revision and change facts without performing IO or mutating progress.
   *
   * @param status safely observed repository facts
   * @param openedAtRevision revision when this lesson opened, absent for the first lesson
   * @return missing commit, dirty worktree, or readiness for the transition
   */
  public static Decision evaluate(GitStatus status, @Nullable String openedAtRevision) {
    if (status.headRevision().isEmpty()
        || status.headRevision().orElseThrow().equals(openedAtRevision)) {
      return Decision.MISSING_COMMIT;
    }
    return status.trackedChanges() + status.untrackedChanges() > 0
        ? Decision.DIRTY_WORKTREE
        : Decision.READY;
  }

  /** Local conditions checked before lesson disclosure. */
  public enum Decision {
    /** HEAD exists and differs from the opening revision, with no pending changes. */
    READY,
    /** There is no new local lesson commit. */
    MISSING_COMMIT,
    /** There are pending tracked or untracked entries. */
    DIRTY_WORKTREE
  }
}
