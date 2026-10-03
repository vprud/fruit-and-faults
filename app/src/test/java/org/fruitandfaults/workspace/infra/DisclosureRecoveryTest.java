package org.fruitandfaults.workspace.infra;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.fruitandfaults.course.domain.Course;
import org.fruitandfaults.course.domain.Lesson;
import org.fruitandfaults.course.infra.ClasspathCourseCatalog;
import org.fruitandfaults.progress.application.ProgressRepository;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.fruitandfaults.progress.infra.AtomicProgressRepository;
import org.fruitandfaults.progress.infra.JacksonProgressCodec;
import org.fruitandfaults.workspace.application.DiscloseLesson;
import org.fruitandfaults.workspace.application.DisclosureResult;
import org.fruitandfaults.workspace.application.ManagedFilesRepository;
import org.fruitandfaults.workspace.application.TransitionJournalReadException;
import org.fruitandfaults.workspace.application.TransitionJournalRepository;
import org.fruitandfaults.workspace.application.WorkspaceFiles;
import org.fruitandfaults.workspace.domain.DisclosureConflict;
import org.fruitandfaults.workspace.domain.DisclosurePlan;
import org.fruitandfaults.workspace.domain.ManagedFile;
import org.fruitandfaults.workspace.domain.ManagedFiles;
import org.fruitandfaults.workspace.domain.TransitionJournal;
import org.fruitandfaults.workspace.domain.TransitionStatus;
import org.fruitandfaults.workspace.domain.WorkspacePath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DisclosureRecoveryTest {
  @TempDir private Path root;
  private final ClasspathCourseCatalog catalog = new ClasspathCourseCatalog("course-valid");
  private final Course course = course();
  private final CourseProgress previous = CourseProgress.opening(course, "before");
  private final CourseProgress intended =
      previous.advance(previous.activeLessonId().orElseThrow(), "yes", "after").progress();
  private final Lesson lesson = course.lessons().getLast();
  private final SafeWorkspaceFiles files = new SafeWorkspaceFiles();
  private final ManagedFilesRepository manifests = new JacksonManagedFilesRepository();
  private final ProgressRepository progress =
      new AtomicProgressRepository(new JacksonProgressCodec(course));
  private final JacksonTransitionJournalRepository journals =
      new JacksonTransitionJournalRepository(course);

  @BeforeEach
  void initializePriorProgress() throws IOException {
    root = root.toRealPath();
    progress.save(root, previous);
  }

  @ParameterizedTest
  @ValueSource(strings = {"journal", "asset-1", "asset-3", "manifest", "progress"})
  void completesMatchingJournalAfterEveryDurableCrashBoundary(String crash) throws IOException {
    assertThrows(
        IOException.class,
        () ->
            crashing(crash).apply(root, lesson, Optional.empty(), Optional.of(previous), intended));
    assertTrue(journals.load(root).isPresent());
    assertEquals(Optional.of(crash.equals("progress") ? intended : previous), progress.load(root));
    DiscloseLesson recovered = disclosure(files, manifests, progress, journals);
    assertEquals(
        crash.equals("progress") ? TransitionStatus.ALREADY_APPLIED : TransitionStatus.RECOVERED,
        recovered.recover(root).status());
    assertEquals(Optional.of(intended), progress.load(root));
    assertEquals(3, manifests.load(root).orElseThrow().files().size());
    for (var asset : lesson.assets()) {
      assertArrayEquals(
          catalog.load(asset), Files.readAllBytes(root.resolve(asset.relativePath())));
    }
    assertTrue(journals.load(root).isEmpty());
    byte[] committed = Files.readAllBytes(root.resolve(".fruit-and-faults/progress.json"));
    assertEquals(TransitionStatus.ALREADY_APPLIED, recovered.recover(root).status());
    assertEquals(
        TransitionStatus.ALREADY_APPLIED,
        recovered.apply(root, lesson, Optional.empty(), Optional.of(previous), intended).status());
    assertArrayEquals(
        committed, Files.readAllBytes(root.resolve(".fruit-and-faults/progress.json")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"learner edit", "", "different foreign bytes"})
  void preservesPartialEditedOrForeignTargetsAndPendingJournal(String content) throws IOException {
    assertThrows(
        IOException.class,
        () ->
            crashing("asset-1")
                .apply(root, lesson, Optional.empty(), Optional.of(previous), intended));
    Path first = root.resolve("src/Raw.txt");
    Files.writeString(first, content);
    byte[] pending = Files.readAllBytes(journalFile());
    DisclosureResult.Conflict conflict =
        assertInstanceOf(
            DisclosureResult.Conflict.class,
            disclosure(files, manifests, progress, journals).recover(root));
    assertEquals(DisclosureResult.Reason.ASSET_CONFLICT, conflict.reason());
    assertEquals(
        List.of(
            new DisclosureConflict(
                WorkspacePath.parse("src/Raw.txt"), DisclosureConflict.Reason.CONTENT_MISMATCH)),
        conflict.paths());
    assertEquals(content, Files.readString(first));
    assertTrue(Files.notExists(root.resolve("src/Template.txt")));
    assertArrayEquals(pending, Files.readAllBytes(journalFile()));
    assertEquals(Optional.of(previous), progress.load(root));
    assertTrue(manifests.load(root).isEmpty());
  }

  @Test
  void recoveryAcceptsExactBytesAtUnmanagedTargetOnlyWhenJournalAttributesThem()
      throws IOException {
    assertThrows(
        IOException.class,
        () ->
            crashing("journal")
                .apply(root, lesson, Optional.empty(), Optional.of(previous), intended));
    Files.createDirectory(root.resolve("src"));
    Files.write(root.resolve("src/Raw.txt"), catalog.load(lesson.assets().getFirst()));
    assertEquals(
        TransitionStatus.RECOVERED,
        disclosure(files, manifests, progress, journals).recover(root).status());
    assertEquals(Optional.of(intended), progress.load(root));
  }

  @Test
  void learnerSymlinkEscapeStopsRecoveryWithoutTouchingOutsideBytes() throws IOException {
    assertThrows(
        IOException.class,
        () ->
            crashing("journal")
                .apply(root, lesson, Optional.empty(), Optional.of(previous), intended));
    Path outside = Files.createTempDirectory(root.getParent(), "outside-disclosure-").toRealPath();
    Files.writeString(outside.resolve("Raw.txt"), "outside");
    Files.createSymbolicLink(root.resolve("src"), outside);
    assertEquals(
        TransitionStatus.CONFLICT,
        disclosure(files, manifests, progress, journals).recover(root).status());
    assertEquals("outside", Files.readString(outside.resolve("Raw.txt")));
    assertEquals(Optional.of(previous), progress.load(root));
    assertTrue(journals.load(root).isPresent());
  }

  @Test
  void differentOwnershipSnapshotStopsRecoveryBeforeAnyAssetWrite() throws IOException {
    assertThrows(
        IOException.class,
        () ->
            crashing("journal")
                .apply(root, lesson, Optional.empty(), Optional.of(previous), intended));
    manifests.save(root, ManagedFiles.empty());
    assertEquals(
        TransitionStatus.CONFLICT,
        disclosure(files, manifests, progress, journals).recover(root).status());
    assertTrue(Files.notExists(root.resolve("src")));
    assertEquals(Optional.of(previous), progress.load(root));
    assertTrue(journals.load(root).isPresent());
  }

  @Test
  void initialDisclosureCanRecoverWithAbsentManifestAndProgress() throws IOException {
    Path initial = Files.createDirectory(root.resolve("initial"));
    CourseProgress opening = CourseProgress.opening(course, null);
    Lesson first = course.lessons().getFirst();
    List<ManagedFile> declared =
        first.assets().stream()
            .map(
                asset ->
                    new ManagedFile(
                        WorkspacePath.parse(asset.relativePath()),
                        asset.id(),
                        asset.sha256(),
                        first.id(),
                        asset.policy()))
            .toList();
    journals.create(
        initial,
        new TransitionJournal(
            1,
            Optional.empty(),
            first.id(),
            declared,
            1,
            Optional.empty(),
            Optional.empty(),
            opening));
    assertEquals(
        TransitionStatus.RECOVERED,
        disclosure(files, manifests, progress, journals).recover(initial).status());
    assertEquals(Optional.of(opening), progress.load(initial));
    assertTrue(journals.load(initial).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "{",
        "[]",
        "null",
        "{}",
        "{\"formatVersion\":\"1\"}",
        "{\"formatVersion\":1,\"formatVersion\":1}"
      })
  void malformedJournalsAreTypedDiagnosticsAndRemainUntouched(String invalid) throws IOException {
    Files.writeString(journalFile(), invalid);
    byte[] bytes = Files.readAllBytes(journalFile());
    TransitionJournalReadException failure =
        assertThrows(TransitionJournalReadException.class, () -> journals.load(root));
    assertEquals(TransitionJournalReadException.Reason.MALFORMED, failure.reason());
    assertArrayEquals(bytes, Files.readAllBytes(journalFile()));
    assertThrows(TransitionJournalReadException.class, () -> journals.create(root, journal()));
    assertArrayEquals(bytes, Files.readAllBytes(journalFile()));
  }

  @Test
  void futureJournalVersionIsPreservedBeforeInterpretingOtherFields() throws IOException {
    Files.writeString(journalFile(), "{\"formatVersion\":2,\"future\":true}");
    TransitionJournalReadException failure =
        assertThrows(
            TransitionJournalReadException.class,
            () -> disclosure(files, manifests, progress, journals).recover(root));
    assertEquals(TransitionJournalReadException.Reason.UNSUPPORTED_FORMAT, failure.reason());
    assertEquals("{\"formatVersion\":2,\"future\":true}", Files.readString(journalFile()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "wrong-lesson",
        "reordered-assets",
        "wrong-hash",
        "wrong-manifest-version",
        "unknown-field",
        "trailing-json"
      })
  void alteredPlansCannotRecoverOrBeDeleted(String change) throws IOException {
    journals.create(root, journal());
    String valid = Files.readString(journalFile());
    String changed =
        switch (change) {
          case "wrong-lesson" ->
              valid.replace("\"toLessonId\" : \"second\"", "\"toLessonId\" : \"first\"");
          case "reordered-assets" -> valid.replace("src/Raw.txt", "src/Template.txt");
          case "wrong-hash" -> valid.replace(lesson.assets().getFirst().sha256(), "a".repeat(64));
          case "wrong-manifest-version" ->
              valid.replace("\"expectedManifestVersion\" : 1", "\"expectedManifestVersion\" : 2");
          case "unknown-field" -> valid.replaceFirst("\\{", "{\"extra\":true,");
          case "trailing-json" -> valid + " {}";
          default -> throw new AssertionError(change);
        };
    Files.writeString(journalFile(), changed);
    assertThrows(TransitionJournalReadException.class, () -> journals.remove(root, journal()));
    assertEquals(changed, Files.readString(journalFile()));
    assertTrue(Files.notExists(root.resolve("src")));
    assertEquals(Optional.of(previous), progress.load(root));
  }

  @Test
  void journalRoundTripsOnlyIdentitiesHashesPoliciesAndVersionedSnapshots() throws IOException {
    journals.create(root, journal());
    assertEquals(Optional.of(journal()), journals.load(root));
    String json = Files.readString(journalFile());
    assertTrue(json.contains("\"expectedManifestVersion\" : 1"));
    assertTrue(json.contains("\"fromLessonId\" : \"first\""));
    assertTrue(json.contains("\"toLessonId\" : \"second\""));
    assertTrue(
        !json.contains("resourcePath")
            && !json.contains("instructions")
            && !json.contains("rawContent"));
    assertThrows(IOException.class, () -> journals.create(root, journal()));
    assertEquals(Optional.of(journal()), journals.load(root));
  }

  @Test
  void failedJournalWriteRetainsPartialTargetAndNeverTouchesAssets() throws IOException {
    SafeWorkspaceFiles failingFiles =
        new SafeWorkspaceFiles(
            (channel, bytes) -> {
              channel.write(ByteBuffer.wrap("{".getBytes(StandardCharsets.UTF_8)));
              throw new IOException("Injected partial write");
            },
            SafeWorkspaceFiles::createNewChannel);
    var failingJournals = new JacksonTransitionJournalRepository(course, failingFiles);
    assertThrows(
        IOException.class,
        () ->
            disclosure(files, manifests, progress, failingJournals)
                .apply(root, lesson, Optional.empty(), Optional.of(previous), intended));
    assertEquals("{", Files.readString(journalFile()));
    assertTrue(Files.notExists(root.resolve("src")));
    assertEquals(Optional.of(previous), progress.load(root));
    assertThrows(TransitionJournalReadException.class, () -> journals.load(root));
  }

  @Test
  void failedAssetWriteBecomesExplicitRecoveryConflictWithPartialBytesPreserved()
      throws IOException {
    SafeWorkspaceFiles partial =
        new SafeWorkspaceFiles(
            (channel, bytes) -> {
              channel.write(ByteBuffer.wrap(new byte[] {13}));
              throw new IOException("Injected asset write failure");
            },
            SafeWorkspaceFiles::createNewChannel);
    assertThrows(
        IOException.class,
        () ->
            disclosure(partial, manifests, progress, journals)
                .apply(root, lesson, Optional.empty(), Optional.of(previous), intended));
    assertArrayEquals(new byte[] {13}, Files.readAllBytes(root.resolve("src/Raw.txt")));
    assertTrue(journals.load(root).isPresent());
    assertEquals(
        TransitionStatus.CONFLICT,
        disclosure(files, manifests, progress, journals).recover(root).status());
    assertArrayEquals(new byte[] {13}, Files.readAllBytes(root.resolve("src/Raw.txt")));
    assertTrue(Files.notExists(root.resolve("src/Template.txt")));
    assertEquals(Optional.of(previous), progress.load(root));
    assertTrue(manifests.load(root).isEmpty());
  }

  @Test
  void ownershipSnapshotRoundTripsAndRecoveryAddsEachNewAssetOnce() throws IOException {
    ManagedFile known = journal().assets().getFirst();
    ManagedFiles prior = new ManagedFiles(List.of(known));
    files.writeNewSafely(root, known.path(), catalog.load(lesson.assets().getFirst()));
    manifests.save(root, prior);
    TransitionJournal pending =
        new TransitionJournal(
            1,
            previous.activeLessonId(),
            lesson.id(),
            journal().assets(),
            1,
            Optional.of(prior),
            Optional.of(previous),
            intended);
    journals.create(root, pending);
    assertEquals(Optional.of(pending), journals.load(root));
    assertEquals(
        TransitionStatus.RECOVERED,
        disclosure(files, manifests, progress, journals).recover(root).status());
    assertEquals(3, manifests.load(root).orElseThrow().files().size());
    assertEquals(known, manifests.load(root).orElseThrow().files().getFirst());
  }

  @Test
  void oversizedJournalIsRejectedAndPreserved() throws IOException {
    byte[] oversized = new byte[1_048_577];
    Files.write(journalFile(), oversized);
    assertEquals(
        TransitionJournalReadException.Reason.MALFORMED,
        assertThrows(TransitionJournalReadException.class, () -> journals.load(root)).reason());
    assertArrayEquals(oversized, Files.readAllBytes(journalFile()));
  }

  @Test
  void differentValidJournalIsNeverRemoved() throws IOException {
    journals.create(root, journal());
    CourseProgress otherIntended =
        previous
            .advance(previous.activeLessonId().orElseThrow(), "yes", "another-revision")
            .progress();
    TransitionJournal other =
        new TransitionJournal(
            1,
            previous.activeLessonId(),
            lesson.id(),
            journal().assets(),
            1,
            Optional.empty(),
            Optional.of(previous),
            otherIntended);
    assertThrows(IOException.class, () -> journals.remove(root, other));
    assertEquals(Optional.of(journal()), journals.load(root));
    assertEquals(
        DisclosureResult.Reason.PLAN_MISMATCH,
        assertInstanceOf(
                DisclosureResult.Conflict.class,
                disclosure(files, manifests, progress, journals)
                    .apply(root, lesson, Optional.empty(), Optional.of(previous), otherIntended))
            .reason());
    assertTrue(Files.notExists(root.resolve("src")));
  }

  @Test
  void journalWritesStayAnchoredWhenMetadataParentIsReplacedBySymlink() throws IOException {
    Path directory = root.resolve(".fruit-and-faults");
    Path detached = root.resolve("detached-metadata");
    Path outside = Files.createDirectory(root.resolve("outside"));
    Files.writeString(outside.resolve("transition.json"), "outside journal");
    SafeWorkspaceFiles swapped =
        new SafeWorkspaceFiles(
            (channel, bytes) -> {
              SafeWorkspaceFiles.writeFlushed(channel, bytes);
              Files.move(directory, detached);
              Files.createSymbolicLink(directory, outside);
            },
            SafeWorkspaceFiles::createNewChannel);
    assertThrows(
        IOException.class,
        () -> new JacksonTransitionJournalRepository(course, swapped).create(root, journal()));
    assertEquals("outside journal", Files.readString(outside.resolve("transition.json")));
    assertTrue(Files.exists(detached.resolve("transition.json")));
    assertThrows(IOException.class, () -> journals.load(root));
  }

  @Test
  void journalEntryAppearingAfterReadIsNeverOverwritten() throws IOException {
    SafeWorkspaceFiles racing =
        new SafeWorkspaceFiles(
            SafeWorkspaceFiles::writeFlushed,
            (directory, target) -> {
              if (target.toString().equals("transition.json")) {
                Files.writeString(journalFile(), "foreign journal");
              }
              return SafeWorkspaceFiles.createNewChannel(directory, target);
            });
    assertThrows(
        IOException.class,
        () -> new JacksonTransitionJournalRepository(course, racing).create(root, journal()));
    assertEquals("foreign journal", Files.readString(journalFile()));
  }

  @Test
  void metadataSymlinkIsRejectedForJournalReadWriteAndDeletion() throws IOException {
    Path other = Files.createDirectory(root.resolve("other"));
    Files.createSymbolicLink(other.resolve(".fruit-and-faults"), root.resolve(".fruit-and-faults"));
    assertThrows(IOException.class, () -> journals.load(other));
    assertThrows(IOException.class, () -> journals.create(other, journal()));
    assertThrows(IOException.class, () -> journals.remove(other, journal()));
    assertTrue(Files.notExists(journalFile()));
  }

  private TransitionJournal journal() {
    List<ManagedFile> assets =
        lesson.assets().stream()
            .map(
                asset ->
                    new ManagedFile(
                        WorkspacePath.parse(asset.relativePath()),
                        asset.id(),
                        asset.sha256(),
                        lesson.id(),
                        asset.policy()))
            .toList();
    return new TransitionJournal(
        1,
        previous.activeLessonId(),
        lesson.id(),
        assets,
        1,
        Optional.empty(),
        Optional.of(previous),
        intended);
  }

  private Path journalFile() {
    return root.resolve(".fruit-and-faults/transition.json");
  }

  private DiscloseLesson disclosure(
      WorkspaceFiles workspaceFiles,
      ManagedFilesRepository ownership,
      ProgressRepository states,
      TransitionJournalRepository pending) {
    return new DiscloseLesson(catalog, workspaceFiles, ownership, states, pending);
  }

  private DiscloseLesson crashing(String boundary) {
    AtomicInteger count = new AtomicInteger();
    WorkspaceFiles crashingFiles =
        new WorkspaceFiles() {
          @Override
          public DisclosurePlan.Observation inspect(Path path, WorkspacePath target)
              throws IOException {
            return files.inspect(path, target);
          }

          @Override
          public DisclosurePlan preflight(
              Path path, List<ManagedFile> requested, ManagedFiles managed) throws IOException {
            return files.preflight(path, requested, managed);
          }

          @Override
          public void writeNewSafely(Path path, WorkspacePath target, byte[] bytes)
              throws IOException {
            files.writeNewSafely(path, target, bytes);
            crash(boundary, "asset-" + count.incrementAndGet());
          }

          @Override
          public void writeNewSafely(
              Path path, DisclosurePlan plan, Map<WorkspacePath, byte[]> contents) {
            throw new AssertionError("Per-asset transaction writes required");
          }
        };
    ManagedFilesRepository crashingManifests =
        new ManagedFilesRepository() {
          @Override
          public Optional<ManagedFiles> load(Path path) throws IOException {
            return manifests.load(path);
          }

          @Override
          public void save(Path path, ManagedFiles managed) throws IOException {
            manifests.save(path, managed);
            crash(boundary, "manifest");
          }
        };
    ProgressRepository crashingProgress =
        new ProgressRepository() {
          @Override
          public Optional<CourseProgress> load(Path path) throws IOException {
            return progress.load(path);
          }

          @Override
          public void save(Path path, CourseProgress state) throws IOException {
            progress.save(path, state);
            crash(boundary, "progress");
          }
        };
    TransitionJournalRepository crashingJournals =
        new TransitionJournalRepository() {
          @Override
          public Optional<TransitionJournal> load(Path path) throws IOException {
            return journals.load(path);
          }

          @Override
          public void create(Path path, TransitionJournal value) throws IOException {
            journals.create(path, value);
            crash(boundary, "journal");
          }

          @Override
          public void remove(Path path, TransitionJournal value) throws IOException {
            journals.remove(path, value);
          }
        };
    return disclosure(crashingFiles, crashingManifests, crashingProgress, crashingJournals);
  }

  private static void crash(String requested, String actual) throws IOException {
    if (requested.equals(actual)) {
      throw new IOException("Injected crash after " + actual);
    }
  }

  private Course course() {
    Course base = catalog.load();
    Lesson second = base.lessons().getLast();
    Lesson target =
        new Lesson(
            second.id(),
            second.title(),
            second.goal(),
            second.prerequisites(),
            base.lessons().getFirst().assets(),
            second.expectedArtifacts(),
            second.completionCriteria(),
            second.instructions(),
            second.hints(),
            second.question(),
            second.recommendedCommitMessage());
    return new Course(
        base.id(),
        base.contentVersion(),
        base.title(),
        base.lessonOrder(),
        List.of(base.lessons().getFirst(), target));
  }
}
