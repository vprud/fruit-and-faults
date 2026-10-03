package org.fruitandfaults.course.application;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import org.fruitandfaults.course.domain.Course;
import org.fruitandfaults.progress.application.ProgressRepository;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.fruitandfaults.validation.domain.Diagnostic;
import org.fruitandfaults.validation.domain.FailureCategory;

/** Loads a selected active hint before atomically persisting its level; level three is stable. */
public final class ShowHint {
  private final CourseCatalog catalog;
  private final ProgressRepository progress;

  /**
   * Composes installed text loading and existing atomic progress persistence.
   *
   * @param catalog validated lesson text loader
   * @param progress workspace-scoped atomic repository
   */
  public ShowHint(CourseCatalog catalog, ProgressRepository progress) {
    this.catalog = Objects.requireNonNull(catalog);
    this.progress = Objects.requireNonNull(progress);
  }

  /**
   * Reveals one hint level and returns no text if its persistence fails.
   *
   * @param root selected learner workspace
   * @return active hint, completion, or safe failure
   */
  public HintResult execute(Path root) {
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
      if (current.activeLessonId().isEmpty()) {
        return new HintResult.CourseComplete();
      }
      var id = current.activeLessonId().orElseThrow();
      CourseProgress revealed = current.revealHint(id);
      int index = installed.lessonOrder().indexOf(id);
      int level = revealed.lessons().get(index).hintLevel();
      String text = installed.lessons().get(index).hints().get(level - 1);
      if (!revealed.equals(current)) {
        progress.save(root, revealed);
      }
      return new HintResult.Revealed(id, level, text);
    } catch (IOException | IllegalArgumentException failed) {
      return unavailable(FailureCategory.WORKSPACE_CONFLICT);
    } catch (RuntimeException failed) {
      return unavailable(FailureCategory.INTERNAL_ERROR);
    }
  }

  private static HintResult.Unavailable unavailable(FailureCategory category) {
    return new HintResult.Unavailable(
        category,
        new Diagnostic(
            "Loadable active lesson hints and safely replaceable valid progress.",
            category == FailureCategory.INTERNAL_ERROR
                ? "Installed hint text or an adapter is unavailable."
                : "Progress is absent, incompatible, malformed, or could not be safely persisted.",
            "Preserve the previous progress and inspect the course installation and workspace metadata before retrying hint."));
  }
}
