package org.fruitandfaults.workspace.application;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.fruitandfaults.course.domain.Course;
import org.fruitandfaults.course.domain.Lesson;
import org.fruitandfaults.git.application.GitInitializationException;
import org.fruitandfaults.git.application.GitRepository;
import org.fruitandfaults.progress.application.ProgressRepository;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.fruitandfaults.validation.domain.FailureCategory;
import org.fruitandfaults.workspace.domain.DisclosurePlan;
import org.fruitandfaults.workspace.domain.ManagedFiles;
import org.fruitandfaults.workspace.domain.WorkspaceMetadata;
import org.fruitandfaults.workspace.domain.WorkspacePath;

/** Previews and initializes lesson one, or resumes compatible recoverable workspace state. */
public final class StartCourse {
  private final Course course;
  private final WorkspaceMetadata expected;
  private final WorkspaceSetup setup;
  private final DiscloseLesson disclosure;
  private final ProgressRepository progress;
  private final ManagedFilesRepository manifests;
  private final TransitionJournalRepository journals;
  private final GitRepository git;

  /**
   * Keeps filesystem, process, and transaction boundaries explicit.
   *
   * @param course installed validated course
   * @param setup safe destination and identity access
   * @param disclosure existing recoverable disclosure transaction
   * @param progress validated committed progress
   * @param manifests safe ownership persistence
   * @param journals pending transaction access
   * @param git Git initialization only
   */
  public StartCourse(
      Course course,
      WorkspaceSetup setup,
      DiscloseLesson disclosure,
      ProgressRepository progress,
      ManagedFilesRepository manifests,
      TransitionJournalRepository journals,
      GitRepository git) {
    this.course = Objects.requireNonNull(course);
    expected = new WorkspaceMetadata(course.id(), 1);
    this.setup = Objects.requireNonNull(setup);
    this.disclosure = Objects.requireNonNull(disclosure);
    this.progress = Objects.requireNonNull(progress);
    this.manifests = Objects.requireNonNull(manifests);
    this.journals = Objects.requireNonNull(journals);
    this.git = Objects.requireNonNull(git);
  }

  /**
   * Validates all paths before mutation and commits progress only through disclosure.
   *
   * @param request chosen destination and confirmation
   * @return preview, created, resumed, conflict, or retained initialization failure
   */
  public StartResult execute(StartRequest request) {
    Path root = request.target();
    if (WorkspaceCancellation.restoreIfInterrupted(null))
      return cancelled(root, StartResult.Stage.WORKSPACE_INSPECTION);
    WorkspaceSetup.Destination destination;
    try {
      destination = setup.inspect(root);
      if (destination instanceof WorkspaceSetup.Occupied) {
        return conflict(
            root,
            "Destination is nonempty and uninitialized; choose an absent or empty directory.",
            List.of(root));
      }
      if (destination instanceof WorkspaceSetup.Initialized initialized) {
        if (!initialized.workspace().metadata().equals(expected)) {
          return conflict(
              root,
              "Workspace courseId or layoutVersion is incompatible; use the matching installation.",
              List.of(root.resolve(".fruit-and-faults/workspace.properties")));
        }
        try {
          git.requireInitialized(root);
        } catch (GitInitializationException unavailable) {
          return failure(root, StartResult.Stage.GIT_INSPECTION, unavailable);
        } catch (IOException invalidGit) {
          if (WorkspaceCancellation.restoreIfInterrupted(invalidGit))
            return cancelled(root, StartResult.Stage.GIT_INSPECTION);
          return invalidRepository(root);
        }
        return resume(request, initialized.workspace());
      }
      List<Path> invalid = invalidAssetPaths(root);
      if (!invalid.isEmpty()) {
        return conflict(
            root,
            "Course destinations overlap reserved or portable paths; restore valid course content.",
            invalid);
      }
      if (destination instanceof WorkspaceSetup.Empty) {
        DisclosurePlan plan = disclosure.plan(root, firstLesson(), ManagedFiles.empty());
        if (plan instanceof DisclosurePlan.Conflicted rejected) {
          return conflict(
              root,
              "Lesson disclosure has path conflicts; choose an empty safe destination.",
              rejected.conflicts().stream()
                  .map(value -> root.resolve(value.path().value()))
                  .toList());
        }
      }
    } catch (IOException invalid) {
      if (WorkspaceCancellation.restoreIfInterrupted(invalid))
        return cancelled(root, StartResult.Stage.WORKSPACE_INSPECTION);
      return conflict(
          root,
          "Destination or workspace state is unsafe or invalid; inspect its directories and metadata.",
          List.of(root));
    }
    if (WorkspaceCancellation.restoreIfInterrupted(null))
      return cancelled(root, StartResult.Stage.WORKSPACE_INSPECTION);
    if (!request.confirmed()) {
      return new StartResult.PreviewRequired(root, initialPaths(root));
    }
    StartResult.Stage stage = StartResult.Stage.DIRECTORY_CREATION;
    try {
      WorkspaceSetup.Destination refreshed = setup.inspect(root);
      if (!refreshed.equals(destination)) {
        return conflict(
            root,
            "Destination changed after preview; inspect it and confirm again.",
            List.of(root));
      }
      if (destination instanceof WorkspaceSetup.Missing) {
        setup.createDirectory(root);
      }
      stage = StartResult.Stage.GIT_INITIALIZATION;
      git.initialize(root);
      stage = StartResult.Stage.METADATA_CREATION;
      setup.createMetadata(root, expected);
      stage = StartResult.Stage.DISCLOSURE;
      CourseProgress intended = CourseProgress.opening(course, null);
      DisclosureResult applied =
          disclosure.apply(root, firstLesson(), Optional.empty(), Optional.empty(), intended);
      if (applied instanceof DisclosureResult.Conflict rejected) {
        return disclosureConflict(root, rejected);
      }
      return new StartResult.Created(new WorkspaceRoot(root, expected), intended);
    } catch (IOException | IllegalArgumentException failed) {
      return failure(root, stage, failed);
    }
  }

  private StartResult resume(StartRequest request, WorkspaceRoot workspace) throws IOException {
    Path root = workspace.path();
    var pending = journals.load(root);
    Optional<CourseProgress> current = progress.load(root);
    Optional<ManagedFiles> managed = manifests.load(root);
    if (WorkspaceCancellation.restoreIfInterrupted(null))
      return cancelled(root, StartResult.Stage.WORKSPACE_INSPECTION);
    if (pending.isEmpty() && current.isPresent()) {
      return managed.isPresent()
          ? new StartResult.Resumed(workspace, current.orElseThrow())
          : conflict(
              root,
              "Committed progress lacks ownership state; preserve the workspace and restore its manifest.",
              List.of(root.resolve(".fruit-and-faults/managed-files.json")));
    }
    if (pending.isEmpty() && managed.isPresent()) {
      return conflict(
          root,
          "Incomplete state has no recovery journal; preserve it and inspect the course metadata.",
          List.of(root.resolve(".fruit-and-faults")));
    }
    List<Path> invalid = invalidAssetPaths(root);
    if (!invalid.isEmpty()) {
      return conflict(
          root,
          "Course destinations overlap reserved or portable paths; restore valid course content.",
          invalid);
    }
    if (!request.confirmed()) {
      List<Path> paths =
          pending.isPresent()
              ? pending.orElseThrow().assets().stream()
                  .map(value -> root.resolve(value.path().value()))
                  .toList()
              : firstLesson().assets().stream()
                  .map(value -> root.resolve(value.relativePath()))
                  .toList();
      List<Path> preview =
          new ArrayList<>(List.of(root.resolve(".fruit-and-faults/transition.json")));
      preview.addAll(paths);
      preview.add(root.resolve(".fruit-and-faults/managed-files.json"));
      preview.add(root.resolve(".fruit-and-faults/progress.json"));
      return new StartResult.PreviewRequired(root, preview);
    }
    try {
      if (!setup.loadMetadata(root).equals(Optional.of(expected))) {
        return conflict(
            root,
            "Workspace identity changed after preview; inspect it before recovery.",
            List.of(root.resolve(".fruit-and-faults/workspace.properties")));
      }
      try {
        git.requireInitialized(root);
      } catch (GitInitializationException unavailable) {
        return failure(root, StartResult.Stage.GIT_INSPECTION, unavailable);
      } catch (IOException invalidGit) {
        if (WorkspaceCancellation.restoreIfInterrupted(invalidGit))
          return cancelled(root, StartResult.Stage.GIT_INSPECTION);
        return invalidRepository(root);
      }
      DisclosureResult applied =
          pending.isPresent()
              ? disclosure.recover(root)
              : disclosure.apply(
                  root,
                  firstLesson(),
                  Optional.empty(),
                  Optional.empty(),
                  CourseProgress.opening(course, null));
      if (applied instanceof DisclosureResult.Conflict rejected) {
        return disclosureConflict(root, rejected);
      }
      return new StartResult.Resumed(
          workspace,
          progress
              .load(root)
              .orElseThrow(() -> new IOException("Committed disclosure progress is absent.")));
    } catch (IOException | IllegalArgumentException failed) {
      return failure(root, StartResult.Stage.DISCLOSURE, failed);
    }
  }

  private Lesson firstLesson() {
    return course.lessons().getFirst();
  }

  private List<Path> invalidAssetPaths(Path root) {
    List<WorkspacePath> requested =
        firstLesson().assets().stream()
            .map(value -> WorkspacePath.parse(value.relativePath()))
            .toList();
    Set<String> aliases = new HashSet<>();
    List<Path> invalid = new ArrayList<>();
    for (WorkspacePath path : requested) {
      String key = path.aliasKey();
      if (path.isToolMetadata()
          || key.equals(".git")
          || key.startsWith(".git/")
          || !aliases.add(key)
          || requested.stream().anyMatch(other -> key.startsWith(other.aliasKey() + "/"))) {
        invalid.add(root.resolve(path.value()));
      }
    }
    return List.copyOf(invalid);
  }

  private List<Path> initialPaths(Path root) {
    List<Path> paths =
        new ArrayList<>(
            List.of(
                root,
                root.resolve(".git"),
                root.resolve(".fruit-and-faults/workspace.properties"),
                root.resolve(".fruit-and-faults/transition.json")));
    firstLesson().assets().forEach(value -> paths.add(root.resolve(value.relativePath())));
    paths.add(root.resolve(".fruit-and-faults/managed-files.json"));
    paths.add(root.resolve(".fruit-and-faults/progress.json"));
    return List.copyOf(paths);
  }

  private static StartResult.Conflict disclosureConflict(
      Path root, DisclosureResult.Conflict rejected) {
    return conflict(
        root,
        "Disclosure conflicts with existing state or learner files; preserve the journal and resolve the listed conflicts.",
        rejected.paths().stream().map(value -> root.resolve(value.path().value())).toList());
  }

  private static StartResult.Conflict conflict(Path root, String diagnostic, List<Path> paths) {
    return new StartResult.Conflict(root, diagnostic, paths);
  }

  private static StartResult.Conflict invalidRepository(Path root) {
    return conflict(
        root,
        "Expected a safe existing Git repository rooted at this workspace's .git directory; preserve the workspace and restore the correct repository before retrying.",
        List.of(root.resolve(".git")));
  }

  private static StartResult.Failed failure(Path root, StartResult.Stage stage, Exception failed) {
    if (WorkspaceCancellation.restoreIfInterrupted(failed)
        || (failed instanceof GitInitializationException gitFailure
            && gitFailure.reason() == GitInitializationException.Reason.INTERRUPTED)) {
      Thread.currentThread().interrupt();
      return cancelled(root, stage);
    }
    String diagnostic =
        switch (stage) {
          case WORKSPACE_INSPECTION ->
              "Workspace preparation could not be inspected; preserve existing state and retry start.";
          case DIRECTORY_CREATION ->
              "Directory creation failed; any created directories remain. Inspect the destination before retrying.";
          case GIT_INITIALIZATION ->
              "Git initialization failed before course state was written; the destination and any Git files remain. Check Git and choose an empty destination.";
          case GIT_INSPECTION ->
              "Local Git inspection failed; existing course state remains unchanged. Inspect the local repository and retry start.";
          case METADATA_CREATION ->
              "Workspace identity creation failed after Git init; retain the directory and inspect workspace.properties before retrying.";
          case DISCLOSURE ->
              "Lesson disclosure failed after Git init; progress was committed only if disclosure completed. Retain the files and use start to inspect or recover the pending transaction.";
        };
    if (failed instanceof GitInitializationException gitFailure
        && stage != StartResult.Stage.GIT_INSPECTION) {
      diagnostic += " " + Objects.requireNonNull(gitFailure.getMessage());
    } else if (stage == StartResult.Stage.DISCLOSURE
        && failed instanceof IllegalArgumentException) {
      diagnostic +=
          " Installed course content is invalid or unavailable; restore the original course installation before retrying.";
    }
    FailureCategory category =
        failed instanceof GitInitializationException gitFailure
            ? switch (gitFailure.reason()) {
              case TIMEOUT -> FailureCategory.TIMEOUT;
              case INTERRUPTED -> FailureCategory.INTERRUPTED;
              case UNAVAILABLE -> FailureCategory.INTERNAL_ERROR;
              case EXIT_FAILURE ->
                  stage == StartResult.Stage.GIT_INSPECTION
                      ? FailureCategory.WORKSPACE_CONFLICT
                      : FailureCategory.INTERNAL_ERROR;
            }
            : failed instanceof IllegalArgumentException
                ? FailureCategory.INTERNAL_ERROR
                : FailureCategory.WORKSPACE_CONFLICT;
    return new StartResult.Failed(root, stage, diagnostic, category);
  }

  private static StartResult.Failed cancelled(Path root, StartResult.Stage stage) {
    return new StartResult.Failed(
        root,
        stage,
        "Workspace preparation was interrupted; retain existing files and progress and retry start when ready.",
        FailureCategory.INTERRUPTED);
  }
}
