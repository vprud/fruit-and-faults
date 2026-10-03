package org.fruitandfaults.validation.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.fruitandfaults.course.domain.AssetId;
import org.fruitandfaults.course.domain.AssetPolicy;
import org.fruitandfaults.course.domain.Lesson;
import org.fruitandfaults.course.infra.ClasspathCourseCatalog;
import org.fruitandfaults.course.infra.LearnerJourneyFixture;
import org.fruitandfaults.validation.application.ProcessResult;
import org.fruitandfaults.validation.domain.CheckOutcome;
import org.fruitandfaults.validation.domain.FailureCategory;
import org.fruitandfaults.workspace.domain.ManagedFile;
import org.fruitandfaults.workspace.domain.ManagedFiles;
import org.fruitandfaults.workspace.domain.WorkspacePath;
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
      new ManifestArtifactInspector(new BoundedProcessRunner(), catalog);
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

  @Test
  void anchoredOpenSwappedToFifoReturnsTypedFailureAndKillsItsWorker() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeFalse(
        System.getProperty("os.name").startsWith("Windows"));
    AtomicLong clock = new AtomicLong();
    AtomicReference<Process> worker = new AtomicReference<>();
    AtomicReference<ProcessResult> processResult = new AtomicReference<>();
    try (var ready = root.getFileSystem().newWatchService()) {
      root.register(ready, java.nio.file.StandardWatchEventKinds.ENTRY_CREATE);
      BoundedProcessRunner runner =
          new BoundedProcessRunner(
              builder -> {
                var command = new ArrayList<>(builder.command());
                int entry = command.indexOf(ArtifactReadWorker.class.getName());
                command.set(entry, ArtifactFifoFixture.class.getName());
                command.set(
                    command.indexOf("-cp") + 1,
                    command.get(command.indexOf("-cp") + 1)
                        + java.io.File.pathSeparator
                        + Path.of(
                            ArtifactFifoFixture.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .getPath()));
                builder.command(command);
                Process child = builder.start();
                worker.set(child);
                return child;
              },
              (child, nanos) -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Files.exists(root.resolve("fifo-ready"))) {
                  var event =
                      ready.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                  assertTrue(
                      event != null, "The FIFO must replace the regular entry before expiry");
                  event.pollEvents();
                  event.reset();
                }
                clock.set(TimeUnit.SECONDS.toNanos(10));
                return false;
              },
              clock::get);
      ManifestArtifactInspector bounded =
          new ManifestArtifactInspector(
              request -> {
                ProcessResult result = runner.run(request);
                processResult.set(result);
                return result;
              },
              catalog);
      CheckOutcome.Failed result =
          assertInstanceOf(CheckOutcome.Failed.class, bounded.inspect(root, opened, manifest));
      assertEquals(FailureCategory.WORKSPACE_CONFLICT, result.category());
      assertEquals(
          ProcessResult.Cleanup.COMPLETE,
          assertInstanceOf(ProcessResult.TimedOut.class, processResult.get()).cleanup());
      assertFalse(Objects.requireNonNull(worker.get()).isAlive());
      assertFalse(
          Thread.getAllStackTraces().keySet().stream()
              .anyMatch(thread -> thread.isAlive() && thread.getName().startsWith("faf-process-")));
    }
  }

  @Test
  void incompleteArtifactWorkerCleanupIsVisibleWithoutExposingItsOutput() {
    var bounded =
        new ManifestArtifactInspector(
            request ->
                new ProcessResult.TimedOut(
                    new ProcessResult.Output("secret /outside/path", "\u001b", false, false),
                    ProcessResult.Cleanup.INCOMPLETE),
            catalog);
    var result =
        assertInstanceOf(CheckOutcome.Failed.class, bounded.inspect(root, opened, manifest));
    assertEquals(FailureCategory.WORKSPACE_CONFLICT, result.category());
    assertTrue(result.diagnostics().stream().anyMatch(d -> d.observed().contains("cleanup")));
    assertFalse(result.diagnostics().toString().contains("secret"));
  }

  private CheckOutcome inspect() {
    return inspector.inspect(root, opened, manifest);
  }

  private CheckOutcome.Failed failed() {
    return assertInstanceOf(CheckOutcome.Failed.class, inspect());
  }
}
