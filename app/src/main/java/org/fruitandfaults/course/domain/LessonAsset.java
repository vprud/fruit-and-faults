package org.fruitandfaults.course.domain;

/**
 * The identity, destination, source, and disclosed-byte fingerprint of an asset.
 *
 * @param id stable asset identity
 * @param relativePath normalized relative destination within a learner workspace
 * @param resourcePath normalized classpath resource name
 * @param sha256 lowercase SHA-256 of the raw source bytes
 * @param policy ownership and verification policy
 */
public record LessonAsset(
    AssetId id, String relativePath, String resourcePath, String sha256, AssetPolicy policy) {
  /** Validates paths, fingerprint, and required values. */
  public LessonAsset {
    java.util.Objects.requireNonNull(id);
    java.util.Objects.requireNonNull(policy);
    relativePath = ContentValidation.path(relativePath, "asset path");
    resourcePath = ContentValidation.path(resourcePath, "resource path");
    if (!sha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Expected lowercase SHA-256; observed '" + sha256 + "'.");
    }
  }
}
