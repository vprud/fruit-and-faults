package org.fruitandfaults.course.application;

import java.util.List;

import org.fruitandfaults.course.domain.Lesson;
import org.fruitandfaults.course.domain.LessonId;
import org.fruitandfaults.git.application.GitLessonGate;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.fruitandfaults.validation.domain.CheckOutcome;
import org.fruitandfaults.validation.domain.Diagnostic;
import org.fruitandfaults.validation.domain.FailureCategory;
import org.fruitandfaults.workspace.domain.DisclosureConflict;
import org.fruitandfaults.workspace.domain.DisclosurePlan;

/** Typed next outcomes without raw adapter failures or untrusted answer echo. */
public sealed interface AdvanceResult {
  /**
   * Current work passed and an explicit answer is needed.
   *
   * @param lessonId active identity
   * @param questionId stable prompt identity
   * @param prompt question text
   * @param options choices without feedback or the correct option
   */
  record NeedsAnswer(LessonId lessonId, String questionId, String prompt, List<Option> options)
      implements AdvanceResult {}

  /**
   * A prompt choice containing no grading information.
   *
   * @param id stable option ID
   * @param text display text
   */
  record Option(String id, String text) {}

  /**
   * Recognition is incomplete; no state changed.
   *
   * @param feedback targeted course feedback or safe unknown-option guidance
   */
  record Incorrect(String feedback) implements AdvanceResult {}

  /**
   * Current lesson validation failed before answer handling.
   *
   * @param outcome complete typed check failure
   */
  record CheckFailed(CheckOutcome.Failed outcome) implements AdvanceResult {}

  /**
   * The local commit or clean worktree requirement is unmet.
   *
   * @param decision blocking local condition
   * @param advice optional publishing guidance
   */
  record GitBlocked(GitLessonGate.Decision decision, List<String> advice)
      implements AdvanceResult {}

  /**
   * Complete inspected plan awaiting confirmation, including metadata-only terminal completion.
   *
   * @param lessonId target lesson or final completed lesson
   * @param plan exact missing and already-attributed paths
   * @param intendedProgress state committed only after successful confirmation and disclosure
   * @param advice optional publishing guidance
   */
  record PreviewRequired(
      LessonId lessonId,
      DisclosurePlan.Applicable plan,
      CourseProgress intendedProgress,
      List<String> advice)
      implements AdvanceResult {}

  /**
   * Successful disclosure committed the accepted answer and opened the next lesson.
   *
   * @param progress durable new state
   * @param lesson newly opened content
   * @param advice optional publishing guidance
   */
  record Advanced(CourseProgress progress, Lesson lesson, List<String> advice)
      implements AdvanceResult {}

  /**
   * An exact pending disclosure was safely recovered without repeating lesson checks.
   *
   * @param progress committed state
   * @param lesson recovered opened content
   * @param advice optional publishing guidance
   */
  record Recovered(CourseProgress progress, Lesson lesson, List<String> advice)
      implements AdvanceResult {}

  /**
   * Terminal progress is durable; a final learner metadata commit is recommended.
   *
   * @param progress completed route
   * @param advice final metadata commit guidance
   */
  record CourseComplete(CourseProgress progress, List<String> advice) implements AdvanceResult {}

  /**
   * Conflicting state or paths remain untouched.
   *
   * @param diagnostic safe expected/observed/next-action feedback
   * @param paths exact learner-relative conflicts
   */
  record Conflict(Diagnostic diagnostic, List<DisclosureConflict> paths) implements AdvanceResult {}

  /**
   * IO, cancellation, or installation failure prevented transition completion.
   *
   * @param category safe typed failure
   * @param diagnostic actionable feedback
   */
  record Unavailable(FailureCategory category, Diagnostic diagnostic) implements AdvanceResult {}
}
