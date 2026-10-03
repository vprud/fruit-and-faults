package org.fruitandfaults.course.application;

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
}
