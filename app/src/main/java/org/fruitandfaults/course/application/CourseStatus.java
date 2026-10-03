package org.fruitandfaults.course.application;

import java.util.List;
import java.util.Optional;

import org.fruitandfaults.course.domain.LessonId;
import org.fruitandfaults.git.application.GitLessonGate;
import org.fruitandfaults.git.application.GitStatus;
import org.fruitandfaults.validation.domain.Diagnostic;
import org.fruitandfaults.validation.domain.FailureCategory;
import org.fruitandfaults.workspace.application.WorkspaceFiles;
import org.fruitandfaults.workspace.domain.WorkspacePath;

/** Safe status views; no full lesson/progress objects or untrusted exception text are exposed. */
public sealed interface CourseStatus {
  /**
   * Complete cheap observations, which do not claim that the lesson has passed validation.
   *
   * @param activeLesson current goal and hint level, absent for a completed saved route
   * @param artifacts required artifacts of opened lessons, observed without reading contents
   * @param git read-only local repository facts
   * @param gate local commit/worktree condition, absent when no lesson is active
   * @param advice non-blocking publication and completion advice
   * @param continuationAvailable whether additional installed lessons can later be opened
   * @param nextCommand exactly one recommended command
   */
  record Ready(
      Optional<ActiveLesson> activeLesson,
      List<Artifact> artifacts,
      GitStatus git,
      Optional<GitLessonGate.Decision> gate,
      List<String> advice,
      boolean continuationAvailable,
      String nextCommand)
      implements CourseStatus {
    /**
     * Takes immutable copies of the bounded observation/advice lists.
     *
     * @param activeLesson current lesson facts only
     * @param artifacts metadata observations for opened lessons
     * @param git safe local repository facts
     * @param gate active local lesson gate
     * @param advice non-blocking suggestions
     * @param continuationAvailable installed appended-route availability
     * @param nextCommand exactly one recommended command
     */
    public Ready {
      artifacts = List.copyOf(artifacts);
      advice = List.copyOf(advice);
    }
  }

  /**
   * A safe failure without partial state or future course details.
   *
   * @param category workspace, internal, timeout, or interruption classification
   * @param diagnostic actionable safe explanation
   */
  record Unavailable(FailureCategory category, Diagnostic diagnostic) implements CourseStatus {}

  /**
   * The active lesson's learner-visible status facts.
   *
   * @param id current lesson identity
   * @param title current title
   * @param goal current learning goal
   * @param hintLevel revealed hint level
   */
  record ActiveLesson(LessonId id, String title, String goal, int hintLevel) {}

  /**
   * One inexpensive artifact observation, without a claim about its contents.
   *
   * @param path normalized disclosed artifact path
   * @param presence regular-file metadata observation
   */
  record Artifact(WorkspacePath path, WorkspaceFiles.Presence presence) {}
}
