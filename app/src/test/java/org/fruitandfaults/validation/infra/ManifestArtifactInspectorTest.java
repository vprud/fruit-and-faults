package org.fruitandfaults.validation.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.fruitandfaults.course.domain.AssetId;
import org.fruitandfaults.course.domain.AssetPolicy;
import org.fruitandfaults.course.domain.Lesson;
import org.fruitandfaults.course.infra.ClasspathCourseCatalog;
import org.fruitandfaults.course.infra.LearnerJourneyFixture;
import org.fruitandfaults.validation.domain.CheckOutcome;
import org.fruitandfaults.validation.domain.FailureCategory;
import org.fruitandfaults.workspace.domain.ManagedFile;
import org.fruitandfaults.workspace.domain.ManagedFiles;
import org.fruitandfaults.workspace.domain.WorkspacePath;
import org.fruitandfaults.workspace.infra.SafeWorkspaceFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestArtifactInspectorTest {
  private static final String TEMPLATE =
      "src/test/java/org/fruitandfaults/game/AnalogousBoundaryTest.java";
  private static final String IMMUTABLE = "src/test/java/org/fruitandfaults/game/StarterTest.java";
  private final ClasspathCourseCatalog catalog = new ClasspathCourseCatalog("course");
  private final List<Lesson> opened = catalog.load().lessons().subList(0, 3);
  private final ManifestArtifactInspector inspector =
      new ManifestArtifactInspector(new SafeWorkspaceFiles(), catalog);
  @TempDir private Path root;
  private ManagedFiles manifest;

  @BeforeEach
  void discloseOnlyOpenedLessons() throws Exception {
    root = root.toRealPath();
    List<ManagedFile> managed = new ArrayList<>();
    for (Lesson lesson : opened) {
      LearnerJourneyFixture.disclose(root, lesson, catalog);
      lesson
          .assets()
          .forEach(
              asset ->
                  managed.add(
                      new ManagedFile(
                          WorkspacePath.parse(asset.relativePath()),
                          asset.id(),
                          asset.sha256(),
                          lesson.id(),
                          asset.policy())));
    }
    manifest = new ManagedFiles(managed);
  }

  @Test
  void unchangedEditableTemplateIsIncompleteEvenWhenEveryArtifactExists() {
    CheckOutcome.Failed result = failed();
    assertEquals(FailureCategory.INCOMPLETE_WORK, result.category());
    assertTrue(result.diagnostics().stream().anyMatch(d -> d.observed().contains("unchanged")));
  }

  @Test
  void changedTemplateAndFreelyEditedScaffoldAllowLaterCompilation() throws Exception {
    LearnerJourneyFixture.applySolution(root, "field-valid-move", TEMPLATE);
    Files.writeString(
        root.resolve("src/main/java/org/fruitandfaults/game/Starter.java"),
        "learner implementation");
    CheckOutcome.Passed result = assertInstanceOf(CheckOutcome.Passed.class, inspect());
    assertTrue(result.diagnostics().stream().anyMatch(d -> d.nextAction().contains("compilation")));
  }

  @Test
  void whitespaceOnlyEditIsChangedButDoesNotProveMeaningfulWork() throws Exception {
    Files.writeString(root.resolve(TEMPLATE), Files.readString(root.resolve(TEMPLATE)) + " \n\t");
    CheckOutcome.Passed result = assertInstanceOf(CheckOutcome.Passed.class, inspect());
    assertTrue(
        result.diagnostics().stream().anyMatch(d -> d.observed().contains("Only whitespace")));
    assertFalse(result.diagnostics().toString().contains("meaningful change"));
  }

  @Test
  void missingImmutableCheckIsDistinctFromModifiedImmutableCheck() throws Exception {
    Files.delete(root.resolve(IMMUTABLE));
    assertEquals(FailureCategory.MISSING_ARTIFACT, failed().category());
    Files.writeString(root.resolve(IMMUTABLE), "altered visible check");
    CheckOutcome.Failed result = failed();
    assertEquals(FailureCategory.WORKSPACE_CONFLICT, result.category());
    assertTrue(result.diagnostics().stream().anyMatch(d -> d.observed().contains("modified")));
  }

  @Test
  void manifestCannotReclassifyAnImmutableCheckOrReplaceItsBaseline() throws Exception {
    ManagedFile original = manifest.find(WorkspacePath.parse(IMMUTABLE)).orElseThrow();
    List<ManagedFile> changed = new ArrayList<>(manifest.files());
    changed.remove(original);
    changed.add(
        new ManagedFile(
            original.path(),
            original.assetId(),
            "0".repeat(64),
            original.lessonId(),
            AssetPolicy.LEARNER_SCAFFOLD));
    manifest = new ManagedFiles(changed);
    assertEquals(FailureCategory.WORKSPACE_CONFLICT, failed().category());
  }

  @Test
  void missingManifestEntryDoesNotGrantTrustToExistingBytes() {
    manifest =
        new ManagedFiles(
            manifest.files().stream()
                .filter(file -> !file.path().value().equals(IMMUTABLE))
                .toList());
    assertEquals(FailureCategory.WORKSPACE_CONFLICT, failed().category());
  }

  @Test
  void symlinkedTargetAndParentAreRejectedWithoutReadingExternalContent() throws Exception {
    Path external = Files.createDirectory(root.resolve("external"));
    Path sentinel = Files.writeString(external.resolve("secret"), "sensitive sentinel");
    Files.delete(root.resolve(IMMUTABLE));
    Files.createSymbolicLink(root.resolve(IMMUTABLE), sentinel);
    CheckOutcome.Failed result = failed();
    assertEquals(FailureCategory.WORKSPACE_CONFLICT, result.category());
    assertFalse(result.diagnostics().toString().contains("sensitive sentinel"));
    Files.delete(root.resolve(IMMUTABLE));
    Files.move(root.resolve("src"), root.resolve("original-src"));
    Files.createSymbolicLink(root.resolve("src"), external);
    assertEquals(FailureCategory.WORKSPACE_CONFLICT, failed().category());
    assertEquals("sensitive sentinel", Files.readString(sentinel));
  }

  @Test
  void unrelatedManifestAndWorkspaceTreesAreNeverRead() throws Exception {
    LearnerJourneyFixture.applySolution(root, "field-valid-move", TEMPLATE);
    Path external = Files.createDirectory(root.resolve("foreign"));
    Files.createSymbolicLink(root.resolve("unrelated"), external);
    List<ManagedFile> extended = new ArrayList<>(manifest.files());
    extended.add(
        new ManagedFile(
            WorkspacePath.parse("unrelated/secret"),
            new AssetId("unrelated"),
            "0".repeat(64),
            opened.getLast().id(),
            AssetPolicy.IMMUTABLE_CHECK));
    manifest = new ManagedFiles(extended);
    assertInstanceOf(CheckOutcome.Passed.class, inspect());
  }

  private CheckOutcome inspect() {
    return inspector.inspect(root, opened, manifest);
  }

  private CheckOutcome.Failed failed() {
    return assertInstanceOf(CheckOutcome.Failed.class, inspect());
  }
}
