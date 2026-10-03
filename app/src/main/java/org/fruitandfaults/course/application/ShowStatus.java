package org.fruitandfaults.course.application;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.fruitandfaults.course.domain.Course;
import org.fruitandfaults.course.domain.Lesson;
import org.fruitandfaults.git.application.GitInitializationException;
import org.fruitandfaults.git.application.GitLessonGate;
import org.fruitandfaults.git.application.GitRepository;
import org.fruitandfaults.git.application.GitStatus;
import org.fruitandfaults.progress.application.ProgressRepository;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.fruitandfaults.validation.domain.Diagnostic;
import org.fruitandfaults.validation.domain.FailureCategory;
import org.fruitandfaults.workspace.application.ManagedFilesRepository;
import org.fruitandfaults.workspace.application.WorkspaceFiles;
import org.fruitandfaults.workspace.domain.ManagedFile;
import org.fruitandfaults.workspace.domain.ManagedFiles;
import org.fruitandfaults.workspace.domain.WorkspacePath;

/** Inspects progress, ownership, artifact metadata, and local Git without running a build. */
public final class ShowStatus {
  private final CourseCatalog catalog;
  private final ProgressRepository progress;
  private final ManagedFilesRepository manifests;
  private final WorkspaceFiles files;
  private final GitRepository git;

  /**
   * Composes read-only observations; no build runner, disclosure, or progress writing is used.
   *
   * @param catalog installed content
   * @param progress validated workspace progress
   * @param manifests validated ownership state
   * @param files cheap anchored metadata inspection
   * @param git validated read-only local Git facts
   */
  public ShowStatus(
      CourseCatalog catalog,
      ProgressRepository progress,
      ManagedFilesRepository manifests,
      WorkspaceFiles files,
      GitRepository git) {
    this.catalog = Objects.requireNonNull(catalog);
    this.progress = Objects.requireNonNull(progress);
    this.manifests = Objects.requireNonNull(manifests);
    this.files = Objects.requireNonNull(files);
    this.git = Objects.requireNonNull(git);
  }

  /**
   * Returns complete cheap observations or one safe actionable diagnostic.
   *
   * @param root selected learner workspace
   * @return status without persistence or future instruction/hint disclosure
   */
  public CourseStatus execute(Path root) {
    Course installed;
    try {
      installed = catalog.load();
    } catch (RuntimeException invalid) {
      return unavailable(FailureCategory.INTERNAL_ERROR);
    }
    try {
      CourseProgress current =
          progress.load(root).orElseThrow(() -> new IOException("Missing progress."));
      ListLessons.requireCompatible(installed, current);
      ManagedFiles managed =
          manifests.load(root).orElseThrow(() -> new IOException("Missing manifest."));
      int openedCount =
          current
              .activeLessonId()
              .map(id -> current.course().lessonOrder().indexOf(id) + 1)
              .orElse(current.lessons().size());
      List<Lesson> opened = current.course().lessons().subList(0, openedCount);
      requireOwnership(opened, managed);
      List<CourseStatus.Artifact> artifacts = observations(root, opened);
      GitStatus observedGit = git.status(root);
      Optional<CourseStatus.ActiveLesson> active =
          current
              .activeLessonId()
              .map(
                  id -> {
                    int index = installed.lessonOrder().indexOf(id);
                    Lesson lesson = installed.lessons().get(index);
                    return new CourseStatus.ActiveLesson(
                        id,
                        lesson.title(),
                        lesson.goal(),
                        current.lessons().get(index).hintLevel());
                  });
      Optional<GitLessonGate.Decision> gate =
          active.map(
              ignored ->
                  GitLessonGate.evaluate(
                      observedGit, current.activeLessonOpenedAtRevision().orElse(null)));
      List<String> advice = new ArrayList<>();
      if (!observedGit.originPresent()) {
        advice.add(
            "origin is absent; publishing to GitHub is optional and can be completed later.");
      }
      if (!observedGit.upstreamPresent()) {
        advice.add(
            "An upstream branch is absent; establishing tracking is optional and does not block the course.");
      }
      boolean continuation = installed.lessons().size() > current.lessons().size();
      boolean dirty = observedGit.trackedChanges() + observedGit.untrackedChanges() > 0;
      if (active.isEmpty() && !continuation && dirty) {
        advice.add("Make one final metadata commit so a clone also observes course completion.");
      }
      String next;
      if (active.isEmpty()) {
        next =
            continuation ? "fruit-and-faults next" : dirty ? "git status" : "fruit-and-faults list";
      } else if (artifacts.stream()
              .anyMatch(value -> value.presence() == WorkspaceFiles.Presence.MISSING)
          || observedGit.headRevision().isEmpty()) {
        next = "fruit-and-faults check";
      } else if (gate.orElseThrow() != GitLessonGate.Decision.READY) {
        next = "git status";
      } else {
        next = "fruit-and-faults next";
      }
      return new CourseStatus.Ready(
          active, artifacts, observedGit, gate, advice, continuation, next);
    } catch (GitInitializationException failed) {
      return unavailable(
          switch (failed.reason()) {
            case TIMEOUT -> FailureCategory.TIMEOUT;
            case INTERRUPTED -> FailureCategory.INTERRUPTED;
            case UNAVAILABLE, EXIT_FAILURE -> FailureCategory.WORKSPACE_CONFLICT;
          });
    } catch (IOException | IllegalArgumentException failed) {
      return unavailable(FailureCategory.WORKSPACE_CONFLICT);
    } catch (RuntimeException failed) {
      return unavailable(FailureCategory.INTERNAL_ERROR);
    }
  }

  private List<CourseStatus.Artifact> observations(Path root, List<Lesson> opened)
      throws IOException {
    Set<WorkspacePath> paths = new LinkedHashSet<>();
    opened.forEach(
        lesson ->
            lesson.expectedArtifacts().forEach(value -> paths.add(WorkspacePath.parse(value))));
    List<CourseStatus.Artifact> result = new ArrayList<>();
    for (WorkspacePath path : paths) {
      WorkspaceFiles.Presence presence = files.presence(root, path);
      if (path.isToolMetadata() || presence == WorkspaceFiles.Presence.UNSAFE) {
        throw new IOException("Unsafe artifact.");
      }
      result.add(new CourseStatus.Artifact(path, presence));
    }
    return List.copyOf(result);
  }

  private static void requireOwnership(List<Lesson> opened, ManagedFiles managed)
      throws IOException {
    Set<ManagedFile> expected = new LinkedHashSet<>();
    for (Lesson lesson : opened) {
      for (var asset : lesson.assets()) {
        expected.add(
            new ManagedFile(
                WorkspacePath.parse(asset.relativePath()),
                asset.id(),
                asset.sha256(),
                lesson.id(),
                asset.policy()));
      }
    }
    if (managed.files().size() != expected.size()
        || !expected.equals(Set.copyOf(managed.files()))) {
      throw new IOException("Ownership does not match disclosed course assets.");
    }
  }

  private static CourseStatus.Unavailable unavailable(FailureCategory category) {
    return new CourseStatus.Unavailable(
        category,
        new Diagnostic(
            "Valid course progress, disclosed ownership, safe artifacts, and a local workspace repository.",
            switch (category) {
              case TIMEOUT -> "Local Git inspection exceeded its deadline.";
              case INTERRUPTED -> "Local Git inspection was interrupted.";
              case INTERNAL_ERROR ->
                  "Installed course content or a status adapter could not be loaded.";
              default ->
                  "Workspace progress, ownership, artifact paths, or local Git state is absent, incompatible, or unsafe.";
            },
            "Preserve the workspace, inspect its local course metadata and Git state, then retry status."));
  }
}
