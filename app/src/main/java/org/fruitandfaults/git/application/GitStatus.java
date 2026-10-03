package org.fruitandfaults.git.application;

import java.util.Objects;
import java.util.Optional;

/**
 * Bounded local Git facts, without filenames, remote URLs, credentials, or external state.
 *
 * @param headRevision complete local commit object ID, empty only for an unborn branch
 * @param trackedChanges number of changed tracked entries
 * @param untrackedChanges number of untracked entries
 * @param originPresent whether origin has a locally configured URL
 * @param upstreamPresent whether HEAD has a locally resolvable upstream
 */
public record GitStatus(
    Optional<String> headRevision,
    int trackedChanges,
    int untrackedChanges,
    boolean originPresent,
    boolean upstreamPresent) {
  /** Validates complete revisions and bounded nonnegative entry counts. */
  public GitStatus {
    Objects.requireNonNull(headRevision);
    if (headRevision.stream().anyMatch(value -> !value.matches("[0-9a-f]{40}|[0-9a-f]{64}"))
        || trackedChanges < 0
        || untrackedChanges < 0
        || trackedChanges > 65_536
        || untrackedChanges > 65_536) {
      throw new IllegalArgumentException(
          "Expected complete local Git facts and bounded change counts.");
    }
  }
}
