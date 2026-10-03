package org.fruitandfaults.course.application;

import java.util.List;

import org.fruitandfaults.course.domain.Course;

/** A source of immutable, validated course content. */
public interface CourseCatalog {
  /**
   * Loads the complete course or fails without exposing a partial course.
   *
   * @return validated course content
   * @throws IllegalArgumentException if bundled content is missing or invalid
   */
  Course load();

  /**
   * Loads only explicitly shipped trusted content versions; defaults to exact current content.
   *
   * @return installed definition and any supported historical contracts
   */
  default List<Course> supportedCourses() {
    return List.of(load());
  }

  /**
   * Resolves only an explicitly supported trusted definition.
   *
   * @param contentVersion requested content version
   * @return exact historical or current course
   * @throws IllegalArgumentException if this release does not ship that definition
   */
  default Course load(int contentVersion) {
    return supportedCourses().stream()
        .filter(course -> course.contentVersion() == contentVersion)
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Unsupported course content version; install compatible content."));
  }
}
