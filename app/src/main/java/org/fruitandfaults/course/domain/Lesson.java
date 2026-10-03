package org.fruitandfaults.course.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * A declarative lesson whose learner work is distinct from course-owned content.
 *
 * @param id stable lesson identity
 * @param title display title
 * @param goal learning goal
 * @param prerequisites lessons that must have been completed earlier
 * @param assets explicitly declared disclosure assets
 * @param expectedArtifacts normalized workspace paths required for completion
 * @param completionCriteria observable checks required for completion
 * @param instructions UTF-8 Markdown instruction text
 * @param hints exactly three progressively helpful hints
 * @param question reflection question
 * @param recommendedCommitMessage suggested Conventional Commit message
 */
public record Lesson(
    LessonId id,
    String title,
    String goal,
    List<LessonId> prerequisites,
    List<LessonAsset> assets,
    List<String> expectedArtifacts,
    List<CompletionCriterion> completionCriteria,
    String instructions,
    List<String> hints,
    ReflectionQuestion question,
    String recommendedCommitMessage) {
  /** Validates lesson metadata and takes immutable copies of all collections. */
  public Lesson {
    Objects.requireNonNull(id);
    Objects.requireNonNull(question);
    title = ContentValidation.text(title, "lesson title");
    goal = ContentValidation.text(goal, "lesson goal");
    instructions = ContentValidation.text(instructions, "lesson instructions");
    recommendedCommitMessage =
        ContentValidation.text(recommendedCommitMessage, "recommended commit message");
    prerequisites = List.copyOf(prerequisites);
    assets = List.copyOf(assets);
    expectedArtifacts = List.copyOf(expectedArtifacts);
    completionCriteria = List.copyOf(completionCriteria);
    hints = List.copyOf(hints);
    if (hints.size() != 3) {
      throw new IllegalArgumentException(
          "Expected exactly three lesson hints; observed " + hints.size() + ".");
    }
    hints.forEach(hint -> ContentValidation.text(hint, "hint"));
    expectedArtifacts.forEach(path -> ContentValidation.path(path, "expected artifact path"));
    if (completionCriteria.isEmpty()) {
      throw new IllegalArgumentException("Expected at least one completion criterion.");
    }
    if (new HashSet<>(prerequisites).size() != prerequisites.size()
        || assets.stream().map(LessonAsset::id).distinct().count() != assets.size()
        || assets.stream().map(LessonAsset::relativePath).distinct().count() != assets.size()
        || completionCriteria.stream().map(CompletionCriterion::id).distinct().count()
            != completionCriteria.size()
        || new HashSet<>(expectedArtifacts).size() != expectedArtifacts.size()) {
      throw new IllegalArgumentException(
          "Duplicate lesson metadata identities or paths: " + id.value());
    }
  }
}
