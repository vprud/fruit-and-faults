package org.fruitandfaults.validation.application;

import java.nio.file.Path;
import java.util.Objects;

import org.fruitandfaults.course.domain.Course;
import org.fruitandfaults.course.domain.LessonId;
import org.fruitandfaults.workspace.domain.ManagedFiles;

/**
 * Read-only lesson validation inputs; physical path safety remains the artifact/process boundary.
 *
 * @param workspaceRoot normalized selected learner workspace
 * @param course installed declarative route
 * @param activeLessonId selected opened lesson in that route
 * @param manifest validated disclosed ownership snapshot
 */
public record CheckRequest(
    Path workspaceRoot, Course course, LessonId activeLessonId, ManagedFiles manifest) {
  /** Normalizes the root and requires a lesson belonging to the installed route. */
  public CheckRequest {
    workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    Objects.requireNonNull(course);
    Objects.requireNonNull(activeLessonId);
    Objects.requireNonNull(manifest);
    if (!course.lessonOrder().contains(activeLessonId)) {
      throw new IllegalArgumentException("Expected an active lesson from the installed course.");
    }
  }
}
