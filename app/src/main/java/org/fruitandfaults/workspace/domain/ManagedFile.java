package org.fruitandfaults.workspace.domain;

import java.util.Objects;

import org.fruitandfaults.course.domain.AssetId;
import org.fruitandfaults.course.domain.AssetPolicy;
import org.fruitandfaults.course.domain.LessonId;

/**
 * Disclosed ownership facts; these never grant permission to overwrite a learner file.
 *
 * @param path normalized logical destination
 * @param assetId stable source asset identity
 * @param sha256 lowercase SHA-256 of originally disclosed bytes
 * @param lessonId lesson that disclosed the asset
 * @param policy exact course asset ownership policy
 */
public record ManagedFile(
    WorkspacePath path, AssetId assetId, String sha256, LessonId lessonId, AssetPolicy policy) {
  /** Validates complete ownership facts and the disclosed fingerprint. */
  public ManagedFile {
    Objects.requireNonNull(path);
    Objects.requireNonNull(assetId);
    Objects.requireNonNull(lessonId);
    Objects.requireNonNull(policy);
    if (!sha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Expected a lowercase disclosed SHA-256.");
    }
  }
}
