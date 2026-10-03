package org.fruitandfaults.course.application;

import org.fruitandfaults.course.domain.LessonAsset;

/** Read-only access to the raw bytes of declared installed course assets. */
@FunctionalInterface
public interface CourseAssets {
  /**
   * Loads a declared asset without reading learner files.
   *
   * @param asset validated source declaration and stable identity
   * @return raw course-owned bytes
   */
  byte[] load(LessonAsset asset);
}
