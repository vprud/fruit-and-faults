package org.fruitandfaults.course.application;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.fruitandfaults.course.domain.Course;
import org.fruitandfaults.course.infra.ClasspathCourseCatalog;
import org.fruitandfaults.course.infra.LearnerJourneyFixture;
import org.fruitandfaults.git.infra.ProcessGitRepository;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.fruitandfaults.progress.infra.AtomicProgressRepository;
import org.fruitandfaults.progress.infra.JacksonProgressCodec;
import org.fruitandfaults.workspace.domain.ManagedFile;
import org.fruitandfaults.workspace.domain.ManagedFiles;
import org.fruitandfaults.workspace.domain.WorkspacePath;
import org.fruitandfaults.workspace.infra.JacksonManagedFilesRepository;
import org.fruitandfaults.workspace.infra.SafeWorkspaceFiles;

final class CourseApplicationFixture {
  private final ClasspathCourseCatalog catalog = new ClasspathCourseCatalog("course");
  private final Course course = catalog.load();
  private final AtomicProgressRepository progress =
      new AtomicProgressRepository(new JacksonProgressCodec(course));
  private final JacksonManagedFilesRepository manifests = new JacksonManagedFilesRepository();
  private final SafeWorkspaceFiles files = new SafeWorkspaceFiles();
  private final ProcessGitRepository git = new ProcessGitRepository();
  private final Path root;

  CourseApplicationFixture(Path temporary) throws IOException {
    root = temporary.toRealPath();
    var first = course.lessons().getFirst();
    LearnerJourneyFixture.disclose(root, first, catalog);
    List<ManagedFile> managed =
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
    manifests.save(root, new ManagedFiles(managed));
    progress.save(root, CourseProgress.opening(course, null));
    git.initialize(root);
  }

  ShowStatus status() {
    return new ShowStatus(catalog, progress, manifests, files, git);
  }

  ClasspathCourseCatalog catalog() {
    return catalog;
  }

  Course course() {
    return course;
  }

  AtomicProgressRepository progress() {
    return progress;
  }

  JacksonManagedFilesRepository manifests() {
    return manifests;
  }

  SafeWorkspaceFiles files() {
    return files;
  }

  ProcessGitRepository git() {
    return git;
  }

  Path root() {
    return root;
  }
}
