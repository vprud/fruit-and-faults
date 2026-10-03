package org.fruitandfaults.course.domain;

import java.util.List;

/** Proves append-only evolution against trusted historical lesson contracts. */
public final class CourseCompatibility {
  private CourseCompatibility() {}

  /**
   * Requires the entire prior route and its observable contracts to remain supported.
   *
   * @param installed current installed course
   * @param previous trusted historical definition, never inferred from progress IDs
   * @throws IllegalArgumentException if identity, order, version, or a prior contract changed
   */
  public static void requirePrefix(Course installed, Course previous) {
    if (!installed.id().equals(previous.id())
        || installed.contentVersion() < previous.contentVersion()
        || installed.lessons().size() < previous.lessons().size()
        || !installed
            .lessonOrder()
            .subList(0, previous.lessons().size())
            .equals(previous.lessonOrder())
        || (installed.contentVersion() == previous.contentVersion()
            && !installed.equals(previous))) {
      throw new IllegalArgumentException(
          "Expected matching or trusted append-compatible course content.");
    }
    for (int index = 0; index < previous.lessons().size(); index++) {
      Lesson older = previous.lessons().get(index);
      Lesson current = installed.lessons().get(index);
      if (!older.prerequisites().equals(current.prerequisites())
          || !assetContracts(older).equals(assetContracts(current))
          || !older.expectedArtifacts().equals(current.expectedArtifacts())
          || !older.completionCriteria().equals(current.completionCriteria())
          || !older.question().equals(current.question())) {
        throw new IllegalArgumentException(
            "A historical lesson contract changed; install compatible course content.");
      }
    }
  }

  private static List<AssetContract> assetContracts(Lesson lesson) {
    return lesson.assets().stream()
        .map(
            asset ->
                new AssetContract(asset.id(), asset.relativePath(), asset.sha256(), asset.policy()))
        .toList();
  }

  private record AssetContract(AssetId id, String path, String hash, AssetPolicy policy) {}
}
