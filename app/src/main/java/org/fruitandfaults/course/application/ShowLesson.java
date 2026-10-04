package org.fruitandfaults.course.application;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import org.fruitandfaults.course.domain.Course;
import org.fruitandfaults.progress.application.ProgressRepository;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.fruitandfaults.validation.domain.Diagnostic;
import org.fruitandfaults.validation.domain.FailureCategory;
import org.fruitandfaults.workspace.application.WorkspaceCancellation;

/** Reads the active installed lesson without changing saved progress. */
public final class ShowLesson {
  private final CourseCatalog catalog;
  private final ProgressRepository progress;

  /**
   * Creates a read-only lesson selector.
   *
   * @param catalog validated installed content
   * @param progress validated workspace progress
   */
  public ShowLesson(CourseCatalog catalog, ProgressRepository progress) {
    this.catalog = Objects.requireNonNull(catalog);
    this.progress = Objects.requireNonNull(progress);
  }

  /**
   * Selects only the current lesson from compatible saved progress.
   *
   * @param root selected learner workspace
   * @return active lesson, completion, or safe failure
   */
  public LessonResult execute(Path root) {
    if (WorkspaceCancellation.restoreIfInterrupted(null))
      return unavailable(FailureCategory.INTERRUPTED);
    Course installed;
    try {
      installed = catalog.load();
    } catch (RuntimeException failed) {
      return unavailable(FailureCategory.INTERNAL_ERROR);
    }
    try {
      CourseProgress current =
          progress.load(root).orElseThrow(() -> new IOException("Missing progress."));
      ListLessons.requireCompatible(installed, current);
      if (current.activeLessonId().isEmpty()) return new LessonResult.CourseComplete();
      int index = installed.lessonOrder().indexOf(current.activeLessonId().orElseThrow());
      if (index < 0) return unavailable(FailureCategory.WORKSPACE_CONFLICT);
      return new LessonResult.Active(installed.lessons().get(index));
    } catch (IOException | IllegalArgumentException failed) {
      return unavailable(
          WorkspaceCancellation.restoreIfInterrupted(failed)
              ? FailureCategory.INTERRUPTED
              : FailureCategory.WORKSPACE_CONFLICT);
    } catch (RuntimeException failed) {
      return unavailable(FailureCategory.INTERNAL_ERROR);
    }
  }

  private static LessonResult.Unavailable unavailable(FailureCategory category) {
    return new LessonResult.Unavailable(
        category,
        new Diagnostic(
            "Valid progress and a compatible installed active lesson.",
            switch (category) {
              case INTERRUPTED -> "Reading the active lesson was interrupted.";
              case INTERNAL_ERROR -> "Installed lesson content is unavailable.";
              default -> "Saved progress is missing, malformed, or incompatible.";
            },
            category == FailureCategory.INTERRUPTED
                ? "Keep existing files and retry lesson when ready."
                : "Inspect the course installation and saved workspace state before retrying lesson."));
  }
}
