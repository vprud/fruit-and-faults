package org.fruitandfaults.workspace.application;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.fruitandfaults.course.application.CourseAssets;
import org.fruitandfaults.course.domain.Lesson;
import org.fruitandfaults.progress.application.ProgressRepository;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.fruitandfaults.workspace.domain.DisclosureConflict;
import org.fruitandfaults.workspace.domain.DisclosurePlan;
import org.fruitandfaults.workspace.domain.ManagedFile;
import org.fruitandfaults.workspace.domain.ManagedFiles;
import org.fruitandfaults.workspace.domain.TransitionJournal;
import org.fruitandfaults.workspace.domain.TransitionStatus;
import org.fruitandfaults.workspace.domain.WorkspacePath;

/** Applies write-once disclosure plans, with progress as their final durable commit marker. */
public final class DiscloseLesson {
  private final CourseAssets assets;
  private final WorkspaceFiles files;
  private final ManagedFilesRepository manifests;
  private final ProgressRepository progress;
  private final TransitionJournalRepository journals;

  /**
   * Keeps resource and persistence access behind existing capability ports.
   *
   * @param assets installed raw course bytes
   * @param files exclusive learner-file access
   * @param manifests validated ownership persistence
   * @param progress validated progress persistence
   * @param journals immutable recovery-plan persistence
   */
  public DiscloseLesson(
      CourseAssets assets,
      WorkspaceFiles files,
      ManagedFilesRepository manifests,
      ProgressRepository progress,
      TransitionJournalRepository journals) {
    this.assets = Objects.requireNonNull(assets);
    this.files = Objects.requireNonNull(files);
    this.manifests = Objects.requireNonNull(manifests);
    this.progress = Objects.requireNonNull(progress);
    this.journals = Objects.requireNonNull(journals);
  }

  /**
   * Inspects every declaration without writing any file.
   *
   * @param root selected existing workspace
   * @param lesson lesson to disclose
   * @param managed current ownership
   * @return complete applicable or conflicted plan
   * @throws IOException if inspection fails
   */
  public DisclosurePlan plan(Path root, Lesson lesson, ManagedFiles managed) throws IOException {
    List<ManagedFile> requested = declarations(lesson);
    List<DisclosureConflict> reserved =
        requested.stream()
            .filter(file -> file.path().isToolMetadata())
            .map(
                file ->
                    new DisclosureConflict(file.path(), DisclosureConflict.Reason.TOOL_METADATA))
            .toList();
    return reserved.isEmpty()
        ? files.preflight(root, requested, managed)
        : new DisclosurePlan.Conflicted(reserved);
  }

  /**
   * Applies or resumes exactly the requested transaction.
   *
   * @param root selected existing workspace
   * @param lesson exact installed target lesson
   * @param managed exact prior manifest, empty only when absent
   * @param previous exact prior progress, empty only for initial disclosure
   * @param intended valid progress to commit after all disclosure facts
   * @return applied, recovered, conflict, or already applied
   * @throws IOException if persistence or safe file access fails; later writes do not run
   */
  public DisclosureResult apply(
      Path root,
      Lesson lesson,
      Optional<ManagedFiles> managed,
      Optional<CourseProgress> previous,
      CourseProgress intended)
      throws IOException {
    Optional<TransitionJournal> pending = journals.load(root);
    if (pending.isEmpty()
        && progress.load(root).equals(previous)
        && manifests.load(root).equals(managed)) {
      DisclosurePlan preflight = plan(root, lesson, managed.orElse(ManagedFiles.empty()));
      if (preflight instanceof DisclosurePlan.Conflicted rejected) {
        return new DisclosureResult.Conflict(
            DisclosureResult.Reason.ASSET_CONFLICT, rejected.conflicts());
      }
    }
    TransitionJournal journal =
        new TransitionJournal(
            1,
            previous.flatMap(CourseProgress::activeLessonId),
            lesson.id(),
            declarations(lesson),
            1,
            managed,
            previous,
            intended);
    if (pending.isPresent()) {
      return pending.orElseThrow().equals(journal)
          ? resume(root, journal)
          : conflict(DisclosureResult.Reason.PLAN_MISMATCH);
    }
    if (progress.load(root).equals(Optional.of(intended))
        && manifests.load(root).equals(Optional.of(journal.intendedManaged()))) {
      DisclosurePlan check = plan(root, lesson, journal.intendedManaged());
      if (check instanceof DisclosurePlan.Conflicted rejected) {
        return new DisclosureResult.Conflict(
            DisclosureResult.Reason.ASSET_CONFLICT, rejected.conflicts());
      }
      return ((DisclosurePlan.Applicable) check).filesToCreate().isEmpty()
          ? new DisclosureResult.Success(TransitionStatus.ALREADY_APPLIED)
          : conflict(DisclosureResult.Reason.STATE_MISMATCH);
    }
    if (!progress.load(root).equals(previous) || !manifests.load(root).equals(managed)) {
      return conflict(DisclosureResult.Reason.STATE_MISMATCH);
    }
    DisclosurePlan check = plan(root, lesson, managed.orElse(ManagedFiles.empty()));
    if (check instanceof DisclosurePlan.Conflicted rejected) {
      return new DisclosureResult.Conflict(
          DisclosureResult.Reason.ASSET_CONFLICT, rejected.conflicts());
    }
    Map<WorkspacePath, byte[]> contents = contents(journal);
    journals.create(root, journal);
    return finish(root, journal, contents, TransitionStatus.APPLIED);
  }

  /**
   * Recovers the installed plan without trusting edited asset bytes or changed metadata.
   *
   * @param root selected existing workspace
   * @return recovered, conflict, or already applied when no transaction remains
   * @throws IOException if journal validation, persistence, or safe access fails
   */
  public DisclosureResult recover(Path root) throws IOException {
    Optional<TransitionJournal> journal = journals.load(root);
    return journal.isPresent()
        ? resume(root, journal.orElseThrow())
        : new DisclosureResult.Success(TransitionStatus.ALREADY_APPLIED);
  }

  private DisclosureResult resume(Path root, TransitionJournal journal) throws IOException {
    return finish(root, journal, contents(journal), TransitionStatus.RECOVERED);
  }

  private DisclosureResult finish(
      Path root,
      TransitionJournal journal,
      Map<WorkspacePath, byte[]> contents,
      TransitionStatus status)
      throws IOException {
    Optional<CourseProgress> currentProgress = progress.load(root);
    Optional<ManagedFiles> currentManaged = manifests.load(root);
    boolean committed = currentProgress.equals(Optional.of(journal.intendedProgress()));
    boolean published = currentManaged.equals(Optional.of(journal.intendedManaged()));
    if ((!currentProgress.equals(journal.expectedProgress()) && !committed)
        || (!currentManaged.equals(journal.expectedManaged()) && !published)
        || (committed && !published)) {
      return conflict(DisclosureResult.Reason.STATE_MISMATCH);
    }
    if (!journals.load(root).equals(Optional.of(journal))) {
      return conflict(DisclosureResult.Reason.PLAN_MISMATCH);
    }
    // The durable journal attributes these exact bytes even before the manifest is published.
    DisclosurePlan check = files.preflight(root, journal.assets(), journal.intendedManaged());
    if (check instanceof DisclosurePlan.Conflicted rejected) {
      return new DisclosureResult.Conflict(
          DisclosureResult.Reason.ASSET_CONFLICT, rejected.conflicts());
    }
    for (ManagedFile file : ((DisclosurePlan.Applicable) check).filesToCreate()) {
      files.writeNewSafely(root, file.path(), Objects.requireNonNull(contents.get(file.path())));
    }
    DisclosurePlan verified = files.preflight(root, journal.assets(), journal.intendedManaged());
    if (verified instanceof DisclosurePlan.Conflicted rejected) {
      return new DisclosureResult.Conflict(
          DisclosureResult.Reason.ASSET_CONFLICT, rejected.conflicts());
    }
    if (!((DisclosurePlan.Applicable) verified).filesToCreate().isEmpty()) {
      return conflict(DisclosureResult.Reason.STATE_MISMATCH);
    }
    if (!progress.load(root).equals(currentProgress)
        || !manifests.load(root).equals(currentManaged)) {
      return conflict(DisclosureResult.Reason.STATE_MISMATCH);
    }
    if (!published) {
      manifests.save(root, journal.intendedManaged());
    }
    if (!manifests.load(root).equals(Optional.of(journal.intendedManaged()))) {
      return conflict(DisclosureResult.Reason.STATE_MISMATCH);
    }
    if (!progress.load(root).equals(currentProgress)) {
      return conflict(DisclosureResult.Reason.STATE_MISMATCH);
    }
    Optional<DisclosureResult> changedAssets = requireCompleteAssets(root, journal);
    if (changedAssets.isPresent()) {
      return changedAssets.orElseThrow();
    }
    if (!journals.load(root).equals(Optional.of(journal))) {
      return conflict(DisclosureResult.Reason.PLAN_MISMATCH);
    }
    if (!committed) {
      progress.save(root, journal.intendedProgress());
    }
    if (!progress.load(root).equals(Optional.of(journal.intendedProgress()))) {
      return conflict(DisclosureResult.Reason.STATE_MISMATCH);
    }
    if (!manifests.load(root).equals(Optional.of(journal.intendedManaged()))) {
      return conflict(DisclosureResult.Reason.STATE_MISMATCH);
    }
    changedAssets = requireCompleteAssets(root, journal);
    if (changedAssets.isPresent()) {
      return changedAssets.orElseThrow();
    }
    journals.remove(root, journal);
    return new DisclosureResult.Success(committed ? TransitionStatus.ALREADY_APPLIED : status);
  }

  private Optional<DisclosureResult> requireCompleteAssets(Path root, TransitionJournal journal)
      throws IOException {
    DisclosurePlan check = files.preflight(root, journal.assets(), journal.intendedManaged());
    if (check instanceof DisclosurePlan.Conflicted rejected) {
      return Optional.of(
          new DisclosureResult.Conflict(
              DisclosureResult.Reason.ASSET_CONFLICT, rejected.conflicts()));
    }
    return ((DisclosurePlan.Applicable) check).filesToCreate().isEmpty()
        ? Optional.empty()
        : Optional.of(conflict(DisclosureResult.Reason.STATE_MISMATCH));
  }

  private Map<WorkspacePath, byte[]> contents(TransitionJournal journal) throws IOException {
    Lesson lesson =
        journal.intendedProgress().course().lessons().stream()
            .filter(value -> value.id().equals(journal.toLessonId()))
            .findFirst()
            .orElseThrow();
    Map<WorkspacePath, byte[]> contents = new LinkedHashMap<>();
    for (var asset : lesson.assets()) {
      byte[] bytes = assets.load(asset).clone();
      if (bytes.length > 16_777_216 || !sha256(bytes).equals(asset.sha256())) {
        throw new IOException(
            "Expected matching installed asset bytes; install the original course content before recovery.");
      }
      contents.put(WorkspacePath.parse(asset.relativePath()), bytes);
    }
    return contents;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Java runtime lacks required SHA-256 support", impossible);
    }
  }

  private static List<ManagedFile> declarations(Lesson lesson) {
    return lesson.assets().stream()
        .map(
            asset ->
                new ManagedFile(
                    WorkspacePath.parse(asset.relativePath()),
                    asset.id(),
                    asset.sha256(),
                    lesson.id(),
                    asset.policy()))
        .toList();
  }

  private static DisclosureResult conflict(DisclosureResult.Reason reason) {
    return new DisclosureResult.Conflict(reason, List.of());
  }
}
