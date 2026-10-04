package org.fruitandfaults.journey;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.fruitandfaults.progress.application.ProgressRepository;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.fruitandfaults.progress.infra.AtomicProgressRepository;
import org.fruitandfaults.progress.infra.JacksonProgressCodec;
import org.fruitandfaults.workspace.application.DiscloseLesson;
import org.fruitandfaults.workspace.application.ManagedFilesRepository;
import org.fruitandfaults.workspace.application.TransitionJournalRepository;
import org.fruitandfaults.workspace.application.WorkspaceFiles;
import org.fruitandfaults.workspace.domain.DisclosurePlan;
import org.fruitandfaults.workspace.domain.ManagedFile;
import org.fruitandfaults.workspace.domain.ManagedFiles;
import org.fruitandfaults.workspace.domain.TransitionJournal;
import org.fruitandfaults.workspace.domain.WorkspacePath;
import org.fruitandfaults.workspace.infra.JacksonManagedFilesRepository;
import org.fruitandfaults.workspace.infra.JacksonTransitionJournalRepository;
import org.fruitandfaults.workspace.infra.SafeWorkspaceFiles;

/** Injects IO failure at exact durable boundaries while every completed write remains real. */
final class JourneyCrashes {
  private JourneyCrashes() {}

  static DiscloseLesson disclosure(PhaseAJourneyFixture fixture, String boundary) {
    var actualFiles = new SafeWorkspaceFiles();
    var actualManaged = new JacksonManagedFilesRepository();
    var actualProgress = new AtomicProgressRepository(new JacksonProgressCodec(fixture.catalog()));
    var actualJournal =
        new JacksonTransitionJournalRepository(new JacksonProgressCodec(fixture.catalog()));
    var writes = new AtomicInteger();
    WorkspaceFiles files =
        new WorkspaceFiles() {
          @Override
          public Optional<byte[]> read(Path root, WorkspacePath path) throws IOException {
            return actualFiles.read(root, path);
          }

          @Override
          public DisclosurePlan.Observation inspect(Path root, WorkspacePath path)
              throws IOException {
            return actualFiles.inspect(root, path);
          }

          @Override
          public DisclosurePlan preflight(
              Path root, List<ManagedFile> requested, ManagedFiles managed) throws IOException {
            return actualFiles.preflight(root, requested, managed);
          }

          @Override
          public void writeNewSafely(Path root, WorkspacePath path, byte[] bytes)
              throws IOException {
            actualFiles.writeNewSafely(root, path, bytes);
            crash(boundary, "asset-" + writes.incrementAndGet());
          }

          @Override
          public void writeNewSafely(
              Path root, DisclosurePlan plan, Map<WorkspacePath, byte[]> contents)
              throws IOException {
            actualFiles.writeNewSafely(root, plan, contents);
          }
        };
    ManagedFilesRepository managed =
        new ManagedFilesRepository() {
          @Override
          public Optional<ManagedFiles> load(Path root) throws IOException {
            return actualManaged.load(root);
          }

          @Override
          public void save(Path root, ManagedFiles state) throws IOException {
            actualManaged.save(root, state);
            crash(boundary, "manifest");
          }
        };
    ProgressRepository progress =
        new ProgressRepository() {
          @Override
          public Optional<CourseProgress> load(Path root) throws IOException {
            return actualProgress.load(root);
          }

          @Override
          public void save(Path root, CourseProgress state) throws IOException {
            actualProgress.save(root, state);
            crash(boundary, "progress");
          }
        };
    TransitionJournalRepository journal =
        new TransitionJournalRepository() {
          @Override
          public Optional<TransitionJournal> load(Path root) throws IOException {
            return actualJournal.load(root);
          }

          @Override
          public void create(Path root, TransitionJournal state) throws IOException {
            actualJournal.create(root, state);
            crash(boundary, "journal");
          }

          @Override
          public void remove(Path root, TransitionJournal state) throws IOException {
            crash(boundary, "remove");
            actualJournal.remove(root, state);
            crash(boundary, "removed");
          }
        };
    return new DiscloseLesson(fixture.catalog(), files, managed, progress, journal);
  }

  private static void crash(String selected, String actual) throws IOException {
    if (selected.equals(actual)) throw new IOException("Injected disclosure crash.");
  }
}
