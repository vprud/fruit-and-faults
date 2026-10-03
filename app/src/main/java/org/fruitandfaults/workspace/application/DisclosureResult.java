package org.fruitandfaults.workspace.application;

import java.util.List;
import java.util.Objects;

import org.fruitandfaults.workspace.domain.DisclosureConflict;
import org.fruitandfaults.workspace.domain.TransitionStatus;

/** Explicit transaction outcomes, retaining actionable conflict categories. */
public sealed interface DisclosureResult
    permits DisclosureResult.Success, DisclosureResult.Conflict {
  /**
   * Returns the observable transition outcome.
   *
   * @return applied, recovered, already applied, or conflict
   */
  TransitionStatus status();

  /**
   * A completed or already committed operation.
   *
   * @param status successful outcome
   */
  record Success(TransitionStatus status) implements DisclosureResult {
    /**
     * Requires an explicit successful outcome.
     *
     * @param status successful outcome
     */
    public Success {
      Objects.requireNonNull(status);
      if (status == TransitionStatus.CONFLICT) {
        throw new IllegalArgumentException("Expected a successful status.");
      }
    }
  }

  /**
   * A preserved conflicting plan, state snapshot, or asset.
   *
   * @param reason conflict category
   * @param paths precise asset conflicts, empty for metadata conflicts
   */
  record Conflict(Reason reason, List<DisclosureConflict> paths) implements DisclosureResult {
    /**
     * Copies precise path facts without learner content.
     *
     * @param reason conflict category
     * @param paths precise asset conflicts
     */
    public Conflict {
      Objects.requireNonNull(reason);
      paths = List.copyOf(paths);
    }

    @Override
    public TransitionStatus status() {
      return TransitionStatus.CONFLICT;
    }
  }

  /** Distinctions needed to choose a useful recovery action. */
  enum Reason {
    /** Another immutable journal already owns the pending transition. */
    PLAN_MISMATCH,
    /** Progress or ownership differs from both exact journal snapshots. */
    STATE_MISMATCH,
    /** Learner bytes or paths differ from the course declaration. */
    ASSET_CONFLICT
  }
}
