package org.fruitandfaults.progress.domain;

/**
 * The supported persisted progress schema version.
 *
 * @param value exactly one for the initial schema
 */
public record ProgressFormatVersion(int value) {
  /** Rejects schemas this application cannot safely interpret. */
  public ProgressFormatVersion {
    if (value != 1) {
      throw new IllegalArgumentException(
          "Expected progress format version 1; observed " + value + ".");
    }
  }
}
