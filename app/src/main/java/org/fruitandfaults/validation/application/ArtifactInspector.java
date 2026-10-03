package org.fruitandfaults.validation.application;

import java.nio.file.Path;
import java.util.List;

import org.fruitandfaults.course.domain.Lesson;
import org.fruitandfaults.validation.domain.CheckOutcome;
import org.fruitandfaults.workspace.domain.ManagedFiles;

/** Read-only required-artifact and disclosed-byte policy checks. */
@FunctionalInterface
public interface ArtifactInspector {
  /**
   * Inspects only artifacts belonging to the cumulative opened lessons.
   *
   * @param root selected normalized workspace
   * @param openedLessons ordered lessons through the active lesson
   * @param manifest disclosed ownership facts
   * @return explicit integrity, absence, or incomplete-work observations
   */
  CheckOutcome inspect(Path root, List<Lesson> openedLessons, ManagedFiles manifest);
}
