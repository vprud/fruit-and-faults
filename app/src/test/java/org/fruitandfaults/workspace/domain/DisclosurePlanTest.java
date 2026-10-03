package org.fruitandfaults.workspace.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.fruitandfaults.course.domain.AssetId;
import org.fruitandfaults.course.domain.AssetPolicy;
import org.fruitandfaults.course.domain.LessonId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DisclosurePlanTest {
  private static final String HASH = "a".repeat(64);

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        " ",
        ".",
        "..",
        "/tmp/a",
        "C:/a",
        "C:\\a",
        "\\\\host\\share",
        "a/../b",
        "a/./b",
        "a//b",
        "a/",
        "a\\b",
        "a\u0000b"
      })
  void logicalPathsRejectNonNormalizedAndPlatformAbsoluteForms(String value) {
    assertThrows(IllegalArgumentException.class, () -> WorkspacePath.parse(value));
    assertThrows(IllegalArgumentException.class, () -> new WorkspacePath(value));
  }

  @Test
  void logicalPathsPreserveSpacesAndCyrillicWithoutFilesystemTypes() {
    assertEquals("src/моя игра/Game.java", WorkspacePath.parse("src/моя игра/Game.java").value());
  }

  @Test
  void missingTargetsProduceApplicablePlanWithExactDistinctPolicies() {
    List<ManagedFile> requested =
        List.of(
            file("check", AssetPolicy.IMMUTABLE_CHECK),
            file("template", AssetPolicy.EDITABLE_TEMPLATE),
            file("scaffold", AssetPolicy.LEARNER_SCAFFOLD));
    DisclosurePlan.Applicable plan =
        assertInstanceOf(
            DisclosurePlan.Applicable.class,
            DisclosurePlan.evaluate(requested, ManagedFiles.empty(), missing(requested)));
    assertEquals(requested, plan.filesToCreate());
    assertEquals(List.of(), plan.alreadyApplied());
  }

  @Test
  void duplicateTargetsAreConflictsBeforeAnyCreation() {
    ManagedFile first = file("check", AssetPolicy.IMMUTABLE_CHECK);
    ManagedFile second =
        new ManagedFile(
            first.path(),
            new AssetId("other"),
            HASH,
            first.lessonId(),
            AssetPolicy.LEARNER_SCAFFOLD);
    DisclosurePlan.Conflicted plan =
        assertInstanceOf(
            DisclosurePlan.Conflicted.class,
            DisclosurePlan.evaluate(
                List.of(first, second),
                ManagedFiles.empty(),
                Map.of(first.path(), new DisclosurePlan.Missing())));
    assertEquals(
        List.of(new DisclosureConflict(first.path(), DisclosureConflict.Reason.DUPLICATE_TARGET)),
        plan.conflicts());
    assertThrows(IllegalArgumentException.class, () -> new ManagedFiles(List.of(first, second)));
  }

  @Test
  void matchingUnknownFileRemainsLearnerOwnedAndConflicts() {
    ManagedFile asset = file("check", AssetPolicy.IMMUTABLE_CHECK);
    assertConflict(
        asset,
        ManagedFiles.empty(),
        new DisclosurePlan.RegularFile(HASH),
        DisclosureConflict.Reason.EXISTING_UNMANAGED);
  }

  @Test
  void matchingManagedAssetIsDistinguishedFromNewAssets() {
    ManagedFile asset = file("check", AssetPolicy.IMMUTABLE_CHECK);
    ManagedFile newAsset = file("scaffold", AssetPolicy.LEARNER_SCAFFOLD);
    DisclosurePlan.Applicable plan =
        assertInstanceOf(
            DisclosurePlan.Applicable.class,
            DisclosurePlan.evaluate(
                List.of(asset, newAsset),
                new ManagedFiles(List.of(asset)),
                Map.of(
                    asset.path(),
                    new DisclosurePlan.RegularFile(HASH),
                    newAsset.path(),
                    new DisclosurePlan.Missing())));
    assertEquals(List.of(newAsset), plan.filesToCreate());
    assertEquals(List.of(asset), plan.alreadyApplied());
  }

  @Test
  void ownershipAndContentMismatchesAreDifferentConflicts() {
    ManagedFile asset = file("check", AssetPolicy.IMMUTABLE_CHECK);
    ManagedFile changedPolicy =
        new ManagedFile(
            asset.path(), asset.assetId(), HASH, asset.lessonId(), AssetPolicy.EDITABLE_TEMPLATE);
    assertConflict(
        asset,
        new ManagedFiles(List.of(changedPolicy)),
        new DisclosurePlan.RegularFile(HASH),
        DisclosureConflict.Reason.OWNERSHIP_MISMATCH);
    assertConflict(
        asset,
        new ManagedFiles(List.of(asset)),
        new DisclosurePlan.RegularFile("b".repeat(64)),
        DisclosureConflict.Reason.CONTENT_MISMATCH);
    assertConflict(
        asset,
        ManagedFiles.empty(),
        new DisclosurePlan.UnsafePath(),
        DisclosureConflict.Reason.UNSAFE_PATH);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "bad", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
  void managedOwnershipRequiresAnExactDisclosedHash(String hash) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ManagedFile(
                WorkspacePath.parse("src/Game.java"),
                new AssetId("game"),
                hash,
                new LessonId("first-run"),
                AssetPolicy.LEARNER_SCAFFOLD));
  }

  private static void assertConflict(
      ManagedFile asset,
      ManagedFiles managed,
      DisclosurePlan.Observation observation,
      DisclosureConflict.Reason reason) {
    DisclosurePlan.Conflicted plan =
        assertInstanceOf(
            DisclosurePlan.Conflicted.class,
            DisclosurePlan.evaluate(List.of(asset), managed, Map.of(asset.path(), observation)));
    assertEquals(List.of(new DisclosureConflict(asset.path(), reason)), plan.conflicts());
  }

  private static ManagedFile file(String name, AssetPolicy policy) {
    return new ManagedFile(
        WorkspacePath.parse("src/" + name + ".java"),
        new AssetId(name),
        HASH,
        new LessonId("first-run"),
        policy);
  }

  private static Map<WorkspacePath, DisclosurePlan.Observation> missing(List<ManagedFile> files) {
    return files.stream()
        .collect(Collectors.toMap(ManagedFile::path, _ -> new DisclosurePlan.Missing()));
  }
}
