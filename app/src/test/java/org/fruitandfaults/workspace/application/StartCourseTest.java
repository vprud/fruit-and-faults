package org.fruitandfaults.workspace.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.fruitandfaults.course.application.CourseAssets;
import org.fruitandfaults.course.domain.Course;
import org.fruitandfaults.course.infra.ClasspathCourseCatalog;
import org.fruitandfaults.git.application.GitInitializationException;
import org.fruitandfaults.git.application.GitRepository;
import org.fruitandfaults.git.infra.ProcessGitRepository;
import org.fruitandfaults.progress.application.ProgressRepository;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.fruitandfaults.progress.infra.AtomicProgressRepository;
import org.fruitandfaults.progress.infra.JacksonProgressCodec;
import org.fruitandfaults.workspace.domain.DisclosurePlan;
import org.fruitandfaults.workspace.domain.ManagedFile;
import org.fruitandfaults.workspace.domain.ManagedFiles;
import org.fruitandfaults.workspace.domain.WorkspacePath;
import org.fruitandfaults.workspace.infra.JacksonManagedFilesRepository;
import org.fruitandfaults.workspace.infra.JacksonTransitionJournalRepository;
import org.fruitandfaults.workspace.infra.SafeWorkspaceFiles;
import org.fruitandfaults.workspace.infra.SafeWorkspaceSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StartCourseTest {
  @TempDir private Path temporary;
  private final ClasspathCourseCatalog catalog = new ClasspathCourseCatalog("course-valid");
  private final Course course = catalog.load();
  private final ProgressRepository progress =
      new AtomicProgressRepository(new JacksonProgressCodec(course));
  private final ManagedFilesRepository manifests = new JacksonManagedFilesRepository();
  private final TransitionJournalRepository journals =
      new JacksonTransitionJournalRepository(course);
  private final SafeWorkspaceFiles files = new SafeWorkspaceFiles();
  private Path target;

  @BeforeEach
  void selectRealTemporaryDestination() throws IOException {
    target = temporary.toRealPath().resolve("Моя игра with spaces");
  }

  @Test
  void previewsEveryInitialDestinationWithoutCreatingMissingDirectory() {
    StartResult.PreviewRequired preview =
        assertInstanceOf(
            StartResult.PreviewRequired.class,
            start(catalog, files, new ProcessGitRepository())
                .execute(new StartRequest(target, false)));
    assertEquals(target, preview.root());
    assertEquals(
        List.of(
            target,
            target.resolve(".git"),
            target.resolve(".fruit-and-faults/workspace.properties"),
            target.resolve(".fruit-and-faults/transition.json"),
            target.resolve("src/Raw.txt"),
            target.resolve("src/Template.txt"),
            target.resolve("src/Scaffold.txt"),
            target.resolve(".fruit-and-faults/managed-files.json"),
            target.resolve(".fruit-and-faults/progress.json")),
        preview.paths());
    assertTrue(Files.notExists(target));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void initializesMissingOrEmptyDirectoryWithOnlyFirstLessonAndNoOpeningRevision(boolean existing)
      throws IOException {
    if (existing) {
      Files.createDirectory(target);
    }
    StartResult.Created created =
        assertInstanceOf(
            StartResult.Created.class,
            start(catalog, files, new ProcessGitRepository())
                .execute(new StartRequest(target, true)));
    assertEquals(target, created.workspace().path());
    assertEquals(Optional.of(course.lessonOrder().getFirst()), created.progress().activeLessonId());
    assertTrue(created.progress().activeLessonOpenedAtRevision().isEmpty());
    assertEquals(Optional.of(created.progress()), progress.load(target));
    assertEquals(3, manifests.load(target).orElseThrow().files().size());
    assertTrue(journals.load(target).isEmpty());
    assertTrue(Files.isDirectory(target.resolve(".git")));
    for (var asset : course.lessons().getFirst().assets()) {
      assertArrayEquals(
          catalog.load(asset), Files.readAllBytes(target.resolve(asset.relativePath())));
    }
    assertEquals(
        "courseId=fixture\nlayoutVersion=1\n",
        Files.readString(target.resolve(".fruit-and-faults/workspace.properties")));
  }

  @Test
  void emptyDirectoryStillRequiresConfirmationWithoutMutation() throws IOException {
    Files.createDirectory(target);
    assertInstanceOf(
        StartResult.PreviewRequired.class,
        start(catalog, files, new ProcessGitRepository()).execute(new StartRequest(target, false)));
    try (var entries = Files.list(target)) {
      assertEquals(0L, entries.count());
    }
  }

  @Test
  void rejectsNonEmptyUninitializedDirectoryBeforeGitOrStateMutation() throws IOException {
    Files.createDirectory(target);
    Files.writeString(target.resolve("my-file.txt"), "learner work");
    assertInstanceOf(
        StartResult.Conflict.class,
        start(catalog, files, new ProcessGitRepository()).execute(new StartRequest(target, true)));
    assertEquals("learner work", Files.readString(target.resolve("my-file.txt")));
    assertTrue(Files.notExists(target.resolve(".git")));
    assertTrue(Files.notExists(target.resolve(".fruit-and-faults")));
  }

  @Test
  void resumesCompatibleWorkspaceWithoutRewritingLearnerEditsOrProgress() throws IOException {
    StartCourse useCase = start(catalog, files, new ProcessGitRepository());
    assertInstanceOf(StartResult.Created.class, useCase.execute(new StartRequest(target, true)));
    Files.writeString(target.resolve("src/Scaffold.txt"), "learner implementation");
    CourseProgress hinted =
        progress.load(target).orElseThrow().revealHint(course.lessonOrder().getFirst());
    progress.save(target, hinted);
    byte[] before = Files.readAllBytes(target.resolve(".fruit-and-faults/progress.json"));
    StartResult.Resumed resumed =
        assertInstanceOf(
            StartResult.Resumed.class,
            start(
                    catalog,
                    files,
                    initializationOnly(
                        root -> {
                          throw new IOException("Must not reinitialize Git");
                        }))
                .execute(new StartRequest(target, false)));
    assertEquals(hinted, resumed.progress());
    assertEquals("learner implementation", Files.readString(target.resolve("src/Scaffold.txt")));
    assertArrayEquals(
        before, Files.readAllBytes(target.resolve(".fruit-and-faults/progress.json")));
  }

  @Test
  void markerAloneCannotAuthorizeDisclosureOrGitRepair() throws IOException {
    Files.createDirectory(target);
    new SafeWorkspaceSetup()
        .createMetadata(
            target, new org.fruitandfaults.workspace.domain.WorkspaceMetadata(course.id(), 1));
    byte[] marker = Files.readAllBytes(target.resolve(".fruit-and-faults/workspace.properties"));
    assertInstanceOf(
        StartResult.Conflict.class,
        start(catalog, files, new ProcessGitRepository()).execute(new StartRequest(target, true)));
    assertTrue(Files.notExists(target.resolve(".git")));
    assertTrue(Files.notExists(target.resolve("src")));
    assertTrue(progress.load(target).isEmpty());
    assertTrue(manifests.load(target).isEmpty());
    assertTrue(journals.load(target).isEmpty());
    assertArrayEquals(
        marker, Files.readAllBytes(target.resolve(".fruit-and-faults/workspace.properties")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing",
        "file",
        "symlink",
        "empty",
        "config-symlink",
        "redirected-worktree",
        "bare",
        "commondir"
      })
  void rejectsUnsafeRepositoryWhenResumingCommittedWorkspaceWithoutStateMutation(String invalid)
      throws IOException {
    StartCourse useCase = start(catalog, files, new ProcessGitRepository());
    assertInstanceOf(StartResult.Created.class, useCase.execute(new StartRequest(target, true)));
    Files.writeString(target.resolve("src/Scaffold.txt"), "learner implementation");
    byte[] state = Files.readAllBytes(target.resolve(".fruit-and-faults/progress.json"));
    byte[] ownership = Files.readAllBytes(target.resolve(".fruit-and-faults/managed-files.json"));
    makeRepositoryUnsafe(invalid);
    assertInstanceOf(StartResult.Conflict.class, useCase.execute(new StartRequest(target, true)));
    assertArrayEquals(state, Files.readAllBytes(target.resolve(".fruit-and-faults/progress.json")));
    assertArrayEquals(
        ownership, Files.readAllBytes(target.resolve(".fruit-and-faults/managed-files.json")));
    assertEquals("learner implementation", Files.readString(target.resolve("src/Scaffold.txt")));
    assertTrue(journals.load(target).isEmpty());
    if (invalid.equals("missing")) {
      assertTrue(Files.notExists(target.resolve(".git")));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing",
        "file",
        "symlink",
        "empty",
        "config-symlink",
        "redirected-worktree",
        "bare",
        "commondir"
      })
  void rejectsUnsafeRepositoryBeforeRecoveringPendingDisclosure(String invalid) throws IOException {
    assertInstanceOf(
        StartResult.Failed.class,
        start(catalog, new FailingFiles(files), new ProcessGitRepository())
            .execute(new StartRequest(target, true)));
    byte[] pending = Files.readAllBytes(target.resolve(".fruit-and-faults/transition.json"));
    byte[] learnerFile = Files.readAllBytes(target.resolve("src/Raw.txt"));
    makeRepositoryUnsafe(invalid);
    assertInstanceOf(
        StartResult.Conflict.class,
        start(catalog, files, new ProcessGitRepository()).execute(new StartRequest(target, true)));
    assertArrayEquals(
        pending, Files.readAllBytes(target.resolve(".fruit-and-faults/transition.json")));
    assertArrayEquals(learnerFile, Files.readAllBytes(target.resolve("src/Raw.txt")));
    assertTrue(Files.notExists(target.resolve("src/Template.txt")));
    assertTrue(progress.load(target).isEmpty());
    assertTrue(manifests.load(target).isEmpty());
    if (invalid.equals("missing")) {
      assertTrue(Files.notExists(target.resolve(".git")));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "courseId=other-course\nlayoutVersion=1\n",
        "courseId=fixture\nlayoutVersion=2\n",
        "courseId=fixture\nlayoutVersion=1\nabsolutePath=/elsewhere\n",
        "courseId=fixture\ncourseId=fixture\nlayoutVersion=1\n"
      })
  void rejectsIncompatibleOrMalformedMarkerWithoutWrites(String metadata) throws IOException {
    Files.createDirectories(target.resolve(".fruit-and-faults"));
    Path marker = target.resolve(".fruit-and-faults/workspace.properties");
    Files.writeString(marker, metadata);
    assertInstanceOf(
        StartResult.Conflict.class,
        start(catalog, files, new ProcessGitRepository()).execute(new StartRequest(target, true)));
    assertEquals(metadata, Files.readString(marker));
    assertTrue(Files.notExists(target.resolve(".git")));
    assertTrue(Files.notExists(target.resolve("src")));
  }

  @Test
  void gitFailureLeavesNoCourseOwnedState() throws IOException {
    StartResult.Failed result =
        assertInstanceOf(
            StartResult.Failed.class,
            start(
                    catalog,
                    files,
                    initializationOnly(
                        root -> {
                          throw new IOException("Git unavailable");
                        }))
                .execute(new StartRequest(target, true)));
    assertEquals(StartResult.Stage.GIT_INITIALIZATION, result.stage());
    assertTrue(Files.isDirectory(target));
    assertTrue(Files.notExists(target.resolve(".fruit-and-faults")));
    assertTrue(Files.notExists(target.resolve("src")));
  }

  @Test
  void gitExitFailureReportsExitCodeWithoutExposingCapturedOutput() {
    StartResult.Failed result =
        assertInstanceOf(
            StartResult.Failed.class,
            start(
                    catalog,
                    files,
                    initializationOnly(
                        root -> {
                          throw new GitInitializationException(
                              GitInitializationException.Reason.EXIT_FAILURE,
                              OptionalInt.of(23),
                              "SECRET=token");
                        }))
                .execute(new StartRequest(target, true)));
    assertTrue(result.diagnostic().contains("exit code 23"));
    assertFalse(result.diagnostic().contains("SECRET"));
  }

  @Test
  void refusesNewNestedWorkspaceBeforeCreatingDestination() throws IOException {
    StartCourse useCase = start(catalog, files, new ProcessGitRepository());
    assertInstanceOf(StartResult.Created.class, useCase.execute(new StartRequest(target, true)));
    Path nested = target.resolve("nested");
    assertInstanceOf(StartResult.Conflict.class, useCase.execute(new StartRequest(nested, true)));
    assertTrue(Files.notExists(nested));
  }

  @Test
  void invalidInstalledBytesFailAfterGitInitializationWithoutCommittingProgress()
      throws IOException {
    CourseAssets invalid = asset -> new byte[] {0};
    StartResult.Failed result =
        assertInstanceOf(
            StartResult.Failed.class,
            start(invalid, files, new ProcessGitRepository())
                .execute(new StartRequest(target, true)));
    assertEquals(StartResult.Stage.DISCLOSURE, result.stage());
    assertTrue(Files.isDirectory(target.resolve(".git")));
    assertTrue(progress.load(target).isEmpty());
    assertTrue(manifests.load(target).isEmpty());
    assertTrue(journals.load(target).isEmpty());
    assertTrue(Files.notExists(target.resolve("src")));
    assertInstanceOf(
        StartResult.Resumed.class,
        start(catalog, files, new ProcessGitRepository()).execute(new StartRequest(target, true)));
  }

  @Test
  void missingInstalledResourceReturnsFailureWithoutCommittingInitialState() throws IOException {
    CourseAssets unavailable =
        asset -> {
          throw new IllegalArgumentException("Missing installed resource");
        };
    StartResult.Failed failure =
        assertInstanceOf(
            StartResult.Failed.class,
            start(unavailable, files, new ProcessGitRepository())
                .execute(new StartRequest(target, true)));
    assertEquals(StartResult.Stage.DISCLOSURE, failure.stage());
    assertTrue(Files.isDirectory(target.resolve(".git")));
    assertTrue(progress.load(target).isEmpty());
    assertTrue(journals.load(target).isEmpty());
  }

  @Test
  void missingInstalledResourceDuringRecoveryRetainsPendingTransaction() throws IOException {
    assertInstanceOf(
        StartResult.Failed.class,
        start(catalog, new FailingFiles(files), new ProcessGitRepository())
            .execute(new StartRequest(target, true)));
    byte[] pending = Files.readAllBytes(target.resolve(".fruit-and-faults/transition.json"));
    CourseAssets unavailable =
        asset -> {
          throw new IllegalArgumentException("Missing installed resource");
        };
    assertInstanceOf(
        StartResult.Failed.class,
        start(unavailable, files, new ProcessGitRepository())
            .execute(new StartRequest(target, true)));
    assertArrayEquals(
        pending, Files.readAllBytes(target.resolve(".fruit-and-faults/transition.json")));
    assertTrue(progress.load(target).isEmpty());
  }

  @Test
  void partialDisclosureRetainsJournalAndResumesAfterConfirmation() throws IOException {
    WorkspaceFiles interrupted = new FailingFiles(files);
    assertInstanceOf(
        StartResult.Failed.class,
        start(catalog, interrupted, new ProcessGitRepository())
            .execute(new StartRequest(target, true)));
    assertTrue(Files.isDirectory(target.resolve(".git")));
    assertTrue(progress.load(target).isEmpty());
    assertTrue(journals.load(target).isPresent());
    assertTrue(Files.exists(target.resolve("src/Raw.txt")));
    assertFalse(Files.exists(target.resolve("src/Template.txt")));
    StartCourse recovered = start(catalog, files, new ProcessGitRepository());
    assertInstanceOf(
        StartResult.PreviewRequired.class, recovered.execute(new StartRequest(target, false)));
    assertTrue(progress.load(target).isEmpty());
    assertInstanceOf(StartResult.Resumed.class, recovered.execute(new StartRequest(target, true)));
    assertTrue(journals.load(target).isEmpty());
    assertTrue(progress.load(target).isPresent());
  }

  @Test
  void rejectsSymlinkDestinationAndAncestorWithoutTouchingOutsideDirectory() throws IOException {
    Path outside = Files.createDirectory(temporary.toRealPath().resolve("outside"));
    Files.createSymbolicLink(target, outside);
    StartCourse useCase = start(catalog, files, new ProcessGitRepository());
    assertInstanceOf(StartResult.Conflict.class, useCase.execute(new StartRequest(target, true)));
    assertInstanceOf(
        StartResult.Conflict.class,
        useCase.execute(new StartRequest(target.resolve("child"), true)));
    try (var entries = Files.list(outside)) {
      assertEquals(0L, entries.count());
    }
  }

  private StartCourse start(CourseAssets assets, WorkspaceFiles workspaceFiles, GitRepository git) {
    return new StartCourse(
        course,
        new SafeWorkspaceSetup(),
        new DiscloseLesson(assets, workspaceFiles, manifests, progress, journals),
        progress,
        manifests,
        journals,
        git);
  }

  private static GitRepository initializationOnly(GitInitializer initialize) {
    return new GitRepository() {
      @Override
      public void initialize(Path root) throws IOException {
        initialize.initialize(root);
      }

      @Override
      public void requireInitialized(Path root) throws IOException {
        new ProcessGitRepository().requireInitialized(root);
      }
    };
  }

  @FunctionalInterface
  private interface GitInitializer {
    void initialize(Path root) throws IOException;
  }

  private void makeRepositoryUnsafe(String invalid) throws IOException {
    Path gitDirectory = target.resolve(".git");
    switch (invalid) {
      case "missing", "file", "symlink", "empty" -> {
        Path retained = temporary.toRealPath().resolve("retained-git");
        Files.move(gitDirectory, retained);
        switch (invalid) {
          case "file" -> Files.writeString(gitDirectory, "gitdir: " + retained + "\n");
          case "symlink" -> Files.createSymbolicLink(gitDirectory, retained);
          case "empty" -> Files.createDirectory(gitDirectory);
          default -> {}
        }
      }
      case "config-symlink" -> {
        Path retained = temporary.toRealPath().resolve("retained-config");
        Files.move(gitDirectory.resolve("config"), retained);
        Files.createSymbolicLink(gitDirectory.resolve("config"), retained);
      }
      case "redirected-worktree" ->
          Files.writeString(
              gitDirectory.resolve("config"),
              "\n[core]\n\tworktree = ../..\n",
              StandardOpenOption.APPEND);
      case "bare" ->
          Files.writeString(
              gitDirectory.resolve("config"),
              "\n[core]\n\tbare = true\n",
              StandardOpenOption.APPEND);
      case "commondir" ->
          Files.writeString(gitDirectory.resolve("commondir"), temporary.toRealPath().toString());
      default -> throw new IllegalArgumentException("Unknown repository fixture");
    }
  }

  private static final class FailingFiles implements WorkspaceFiles {
    private final WorkspaceFiles delegate;
    private int created;

    private FailingFiles(WorkspaceFiles delegate) {
      this.delegate = delegate;
    }

    @Override
    public DisclosurePlan.Observation inspect(Path root, WorkspacePath path) throws IOException {
      return delegate.inspect(root, path);
    }

    @Override
    public DisclosurePlan preflight(Path root, List<ManagedFile> requested, ManagedFiles managed)
        throws IOException {
      return delegate.preflight(root, requested, managed);
    }

    @Override
    public void writeNewSafely(Path root, WorkspacePath path, byte[] bytes) throws IOException {
      if (created++ == 1) {
        throw new IOException("Injected asset interruption");
      }
      delegate.writeNewSafely(root, path, bytes);
    }

    @Override
    public void writeNewSafely(
        Path root, DisclosurePlan plan, java.util.Map<WorkspacePath, byte[]> contents)
        throws IOException {
      delegate.writeNewSafely(root, plan, contents);
    }
  }
}
