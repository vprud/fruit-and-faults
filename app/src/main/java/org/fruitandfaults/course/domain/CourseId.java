package org.fruitandfaults.course.domain;

/**
 * A stable course identifier, independent of display text or resource location.
 *
 * @param value lowercase letters, digits, and single hyphen-separated segments
 */
public record CourseId(String value) {
  /** Validates the stable identifier. */
  public CourseId {
    value = ContentValidation.identifier(value, "CourseId");
  }
}
