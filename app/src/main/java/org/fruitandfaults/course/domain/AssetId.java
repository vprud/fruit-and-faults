package org.fruitandfaults.course.domain;

/**
 * A stable asset identifier, independent of display text or resource location.
 *
 * @param value lowercase letters, digits, and single hyphen-separated segments
 */
public record AssetId(String value) {
  /** Validates the stable identifier. */
  public AssetId {
    value = ContentValidation.identifier(value, "AssetId");
  }
}
