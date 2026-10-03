package org.fruitandfaults.course.domain;

/**
 * A stable lesson identifier, independent of display text or resource location.
 *
 * @param value lowercase letters, digits, and single hyphen-separated segments
 */
public record LessonId(String value) {
  /** Validates the stable identifier. */
  public LessonId {
    value = ContentValidation.identifier(value, "LessonId");
  }
}
