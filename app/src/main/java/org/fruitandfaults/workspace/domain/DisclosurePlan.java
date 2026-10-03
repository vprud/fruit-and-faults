package org.fruitandfaults.workspace.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A pure all-target preflight decision, with no filesystem access or mutation. */
public sealed interface DisclosurePlan
    permits DisclosurePlan.Applicable, DisclosurePlan.Conflicted {
  /**
   * Evaluates exact ownership and independently observed target facts.
   *
   * @param requested ordered asset ownership facts
   * @param managed recorded ownership
   * @param observations one explicit observation per requested logical destination
   * @return applicable creation and recovery lists, or precise conflicts
   */
  static DisclosurePlan evaluate(
      List<ManagedFile> requested,
      ManagedFiles managed,
      Map<WorkspacePath, Observation> observations) {
    List<ManagedFile> create = new ArrayList<>();
    List<ManagedFile> applied = new ArrayList<>();
    List<DisclosureConflict> conflicts = new ArrayList<>();
    Set<WorkspacePath> paths = new HashSet<>();
    for (ManagedFile file : requested) {
      if (!paths.add(file.path())) {
        conflicts.add(
            new DisclosureConflict(file.path(), DisclosureConflict.Reason.DUPLICATE_TARGET));
        continue;
      }
      Observation observation =
          Objects.requireNonNull(observations.get(file.path()), "Missing target inspection");
      var known = managed.find(file.path());
      if (observation instanceof UnsafePath) {
        conflicts.add(new DisclosureConflict(file.path(), DisclosureConflict.Reason.UNSAFE_PATH));
      } else if (known.isPresent() && !known.orElseThrow().equals(file)) {
        conflicts.add(
            new DisclosureConflict(file.path(), DisclosureConflict.Reason.OWNERSHIP_MISMATCH));
      } else if (observation instanceof Missing) {
        create.add(file);
      } else if (observation instanceof RegularFile existing) {
        if (known.isEmpty()) {
          conflicts.add(
              new DisclosureConflict(file.path(), DisclosureConflict.Reason.EXISTING_UNMANAGED));
        } else if (!existing.sha256().equals(file.sha256())) {
          conflicts.add(
              new DisclosureConflict(file.path(), DisclosureConflict.Reason.CONTENT_MISMATCH));
        } else {
          applied.add(file);
        }
      }
    }
    return conflicts.isEmpty() ? new Applicable(create, applied) : new Conflicted(conflicts);
  }

  /**
   * A fully inspected plan with separately identified new and matching existing assets.
   *
   * @param filesToCreate destinations that are currently missing
   * @param alreadyApplied matching known ownership and disclosed bytes
   */
  record Applicable(List<ManagedFile> filesToCreate, List<ManagedFile> alreadyApplied)
      implements DisclosurePlan {
    /**
     * Copies asset lists for stable previews and application.
     *
     * @param filesToCreate destinations that are currently missing
     * @param alreadyApplied matching recorded assets
     */
    public Applicable {
      filesToCreate = List.copyOf(filesToCreate);
      alreadyApplied = List.copyOf(alreadyApplied);
    }
  }

  /**
   * A rejected plan; none of its requested destinations may be created.
   *
   * @param conflicts exact path-level conflicts
   */
  record Conflicted(List<DisclosureConflict> conflicts) implements DisclosurePlan {
    /**
     * Requires at least one conflict and copies the result.
     *
     * @param conflicts exact nonempty path-level conflicts
     */
    public Conflicted {
      conflicts = List.copyOf(conflicts);
      if (conflicts.isEmpty()) {
        throw new IllegalArgumentException("A conflicted plan must explain its conflict.");
      }
    }
  }

  /** Framework-free result of inspecting a single logical destination. */
  sealed interface Observation permits Missing, RegularFile, UnsafePath {}

  /** A verified missing destination. */
  record Missing() implements Observation {}

  /**
   * A verified regular file whose bytes were fingerprinted without following symlinks.
   *
   * @param sha256 lowercase SHA-256 of observed bytes
   */
  record RegularFile(String sha256) implements Observation {
    /**
     * Validates the observed fingerprint.
     *
     * @param sha256 lowercase observed SHA-256
     */
    public RegularFile {
      if (!sha256.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("Expected a lowercase observed SHA-256.");
      }
    }
  }

  /** A symlink, non-directory parent, or non-regular destination. */
  record UnsafePath() implements Observation {}
}
