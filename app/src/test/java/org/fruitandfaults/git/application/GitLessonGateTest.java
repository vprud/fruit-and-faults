package org.fruitandfaults.git.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class GitLessonGateTest {
  @Test
  void requiresFirstCommitAndChangedRevisionForLaterLessons() {
    assertEquals(
        GitLessonGate.Decision.MISSING_COMMIT, GitLessonGate.evaluate(status(null, 0, 0), null));
    assertEquals(
        GitLessonGate.Decision.READY, GitLessonGate.evaluate(status("a".repeat(40), 0, 0), null));
    assertEquals(
        GitLessonGate.Decision.MISSING_COMMIT,
        GitLessonGate.evaluate(status("a".repeat(40), 0, 0), "a".repeat(40)));
    assertEquals(
        GitLessonGate.Decision.READY,
        GitLessonGate.evaluate(status("b".repeat(40), 0, 0), "a".repeat(40)));
  }

  @Test
  void trackedAndUntrackedChangesBlockButPublicationFactsDoNot() {
    assertEquals(
        GitLessonGate.Decision.DIRTY_WORKTREE,
        GitLessonGate.evaluate(status("b".repeat(40), 1, 0), "a".repeat(40)));
    assertEquals(
        GitLessonGate.Decision.DIRTY_WORKTREE,
        GitLessonGate.evaluate(status("b".repeat(40), 0, 1), "a".repeat(40)));
    for (boolean origin : new boolean[] {false, true}) {
      for (boolean upstream : new boolean[] {false, true}) {
        assertEquals(
            GitLessonGate.Decision.READY,
            GitLessonGate.evaluate(
                new GitStatus(Optional.of("b".repeat(40)), 0, 0, origin, upstream),
                "a".repeat(40)));
      }
    }
  }

  private static GitStatus status(@Nullable String revision, int tracked, int untracked) {
    return new GitStatus(Optional.ofNullable(revision), tracked, untracked, false, false);
  }
}
