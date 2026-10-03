package org.fruitandfaults.workspace.domain;

import java.util.Objects;

import org.fruitandfaults.course.domain.CourseId;

/**
 * Portable workspace identity, containing no machine-specific locations.
 *
 * @param courseId stable installed course identity
 * @param layoutVersion positive workspace layout version
 */
public record WorkspaceMetadata(CourseId courseId, int layoutVersion) {
  /** Requires a course identity and a positive layout version. */
  public WorkspaceMetadata {
    Objects.requireNonNull(courseId);
    if (layoutVersion < 1) {
      throw new IllegalArgumentException("Expected a positive workspace layout version.");
    }
  }
}
