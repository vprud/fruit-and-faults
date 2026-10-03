package org.fruitandfaults.workspace.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.fruitandfaults.course.domain.Course;
import org.fruitandfaults.course.domain.Lesson;
import org.fruitandfaults.course.domain.LessonAsset;
import org.fruitandfaults.course.infra.ClasspathCourseCatalog;
import org.fruitandfaults.progress.application.ProgressRepository;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.fruitandfaults.workspace.domain.DisclosureConflict;
import org.fruitandfaults.workspace.domain.DisclosurePlan;
import org.fruitandfaults.workspace.domain.ManagedFile;
import org.fruitandfaults.workspace.domain.ManagedFiles;
import org.fruitandfaults.workspace.domain.TransitionJournal;
import org.fruitandfaults.workspace.domain.TransitionStatus;
import org.fruitandfaults.workspace.domain.WorkspacePath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DiscloseLessonTest {
  private static final Path ROOT = Path.of("workspace");
  private final ClasspathCourseCatalog catalog = new ClasspathCourseCatalog("course-valid");
  private final CourseProgress previous = CourseProgress.opening(course(), "before");
  private final Lesson lesson = previous.course().lessons().getLast();
  private final CourseProgress intended =
      previous.advance(previous.activeLessonId().orElseThrow(), "yes", "after").progress();

  @Test
  void writesJournalThenAssetsManifestProgressAndRemovesJournalLast() throws IOException {
    Stores stores = new Stores();
    DisclosureResult result =
        useCase(stores).apply(ROOT, lesson, Optional.empty(), Optional.of(previous), intended);
    assertEquals(TransitionStatus.APPLIED, result.status());
    assertEquals(
        List.of(
            "journal",
            "asset:src/Raw.txt",
            "asset:src/Template.txt",
            "asset:src/Scaffold.txt",
            "manifest",
            "progress",
            "remove"),
        stores.events);
    assertEquals(Optional.of(intended), stores.progress);
    assertEquals(3, stores.managed.orElseThrow().files().size());
    assertTrue(stores.journal.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "journal",
        "asset:src/Raw.txt",
        "asset:src/Template.txt",
        "asset:src/Scaffold.txt",
        "manifest",
        "progress",
        "remove"
      })
  void stopsAtFirstFailureAndCommitsProgressOnlyAfterAssetsAndManifest(String failure) {
    Stores stores = new Stores();
    stores.failure = failure;
    assertThrows(
        IOException.class,
        () ->
            useCase(stores).apply(ROOT, lesson, Optional.empty(), Optional.of(previous), intended));
    List<String> order =
        List.of(
            "journal",
            "asset:src/Raw.txt",
            "asset:src/Template.txt",
            "asset:src/Scaffold.txt",
            "manifest",
            "progress",
            "remove");
    assertEquals(order.subList(0, order.indexOf(failure) + 1), stores.events);
    assertEquals(Optional.of(failure.equals("remove") ? intended : previous), stores.progress);
  }

  @Test
  void preflightsAllTargetsBeforeCreatingJournal() throws IOException {
    Stores stores = new Stores();
    stores.observations.put(
        WorkspacePath.parse("src/Template.txt"), new DisclosurePlan.RegularFile("a".repeat(64)));
    DisclosureResult result =
        useCase(stores).apply(ROOT, lesson, Optional.empty(), Optional.of(previous), intended);
    assertEquals(TransitionStatus.CONFLICT, result.status());
    assertEquals(List.of(), stores.events);
    assertEquals(Optional.of(previous), stores.progress);
  }

  @Test
  void recoveryRejectsDifferentProgressBeforeCreatingMissingAssets() throws IOException {
    Stores stores = new Stores();
    stores.failure = "asset:src/Raw.txt";
    assertThrows(
        IOException.class,
        () ->
            useCase(stores).apply(ROOT, lesson, Optional.empty(), Optional.of(previous), intended));
    stores.failure = "";
    stores.events.clear();
    stores.progress = Optional.of(previous.revealHint(previous.activeLessonId().orElseThrow()));
    DisclosureResult result = useCase(stores).recover(ROOT);
    assertEquals(TransitionStatus.CONFLICT, result.status());
    assertEquals(List.of(), stores.events);
    assertTrue(stores.journal.isPresent());
  }

  @ParameterizedTest
  @ValueSource(strings = {"revision", "ownership"})
  void exactRecoveryRejectsReplacedJournalBeforeMutation(String changed) throws IOException {
    Stores stores = pending();
    TransitionJournal expected = stores.journal.orElseThrow();
    TransitionJournal replacement = replacement(expected, changed);
    stores.journal = Optional.of(replacement);

    DisclosureResult.Conflict result =
        assertInstanceOf(DisclosureResult.Conflict.class, useCase(stores).recover(ROOT, expected));

    assertEquals(DisclosureResult.Reason.PLAN_MISMATCH, result.reason());
    assertEquals(List.of(), stores.events);
    assertEquals(Optional.of(previous), stores.progress);
    assertEquals(Optional.empty(), stores.managed);
    assertEquals(Optional.of(replacement), stores.journal);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "before-assets",
        "between-assets",
        "before-manifest",
        "before-progress",
        "before-remove"
      })
  void exactRecoveryRechecksJournalBeforeEveryMutation(String boundary) throws IOException {
    Stores stores = pending();
    TransitionJournal expected = stores.journal.orElseThrow();
    TransitionJournal replacement = replacement(expected, "revision");
    int replacementPreflight =
        switch (boundary) {
          case "before-assets" -> 1;
          case "before-manifest" -> 2;
          case "before-progress" -> 3;
          case "before-remove" -> 4;
          default -> 0;
        };
    AtomicInteger preflights = new AtomicInteger();
    stores.afterPreflight =
        () -> {
          if (preflights.incrementAndGet() == replacementPreflight)
            stores.journal = Optional.of(replacement);
        };
    if (boundary.equals("between-assets")) {
      stores.afterAssetWrite = () -> stores.journal = Optional.of(replacement);
    }

    DisclosureResult.Conflict result =
        assertInstanceOf(DisclosureResult.Conflict.class, useCase(stores).recover(ROOT, expected));

    assertEquals(DisclosureResult.Reason.PLAN_MISMATCH, result.reason());
    List<String> events =
        List.of(
            "asset:src/Raw.txt",
            "asset:src/Template.txt",
            "asset:src/Scaffold.txt",
            "manifest",
            "progress");
    int completedWrites =
        switch (boundary) {
          case "before-assets" -> 0;
          case "between-assets" -> 1;
          case "before-manifest" -> 3;
          case "before-progress" -> 4;
          case "before-remove" -> 5;
          default -> throw new AssertionError(boundary);
        };
    assertEquals(events.subList(0, completedWrites), stores.events);
    assertEquals(
        Optional.of(boundary.equals("before-remove") ? intended : previous), stores.progress);
    assertEquals(Optional.of(replacement), stores.journal);
  }

  @Test
  void repeatedApplyDoesNotRewriteProgressOrCreateAnotherJournal() throws IOException {
    Stores stores = new Stores();
    DiscloseLesson disclosure = useCase(stores);
    disclosure.apply(ROOT, lesson, Optional.empty(), Optional.of(previous), intended);
    stores.events.clear();
    assertEquals(
        TransitionStatus.ALREADY_APPLIED,
        disclosure.apply(ROOT, lesson, Optional.empty(), Optional.of(previous), intended).status());
    assertEquals(List.of(), stores.events);
    assertInstanceOf(
        DisclosurePlan.Applicable.class,
        disclosure.plan(ROOT, lesson, stores.managed.orElseThrow()));
  }

  @Test
  void learnerEditDuringManifestPublicationPreventsProgressCommit() throws IOException {
    Stores stores = new Stores();
    stores.editDuringManifest = true;
    assertEquals(
        TransitionStatus.CONFLICT,
        useCase(stores)
            .apply(ROOT, lesson, Optional.empty(), Optional.of(previous), intended)
            .status());
    assertEquals(Optional.of(previous), stores.progress);
    assertTrue(stores.journal.isPresent());
    assertTrue(!stores.events.contains("progress"));
  }

  @Test
  void changedProgressDuringManifestPublicationIsNeverOverwritten() throws IOException {
    Stores stores = new Stores();
    stores.changeProgressDuringManifest = true;
    assertEquals(
        TransitionStatus.CONFLICT,
        useCase(stores)
            .apply(ROOT, lesson, Optional.empty(), Optional.of(previous), intended)
            .status());
    assertEquals(
        Optional.of(previous.revealHint(previous.activeLessonId().orElseThrow())), stores.progress);
    assertTrue(stores.journal.isPresent());
    assertTrue(!stores.events.contains("progress"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        ".fruit-and-faults",
        ".fruit-and-faults/progress.json",
        ".FRUIT-AND-FAULTS/managed-files.json"
      })
  void toolMetadataDestinationsAreRejectedBeforeTransactionCreation(String path)
      throws IOException {
    Stores stores = new Stores();
    var asset = lesson.assets().getFirst();
    Lesson invalid =
        new Lesson(
            lesson.id(),
            lesson.title(),
            lesson.goal(),
            lesson.prerequisites(),
            List.of(
                new LessonAsset(
                    asset.id(), path, asset.resourcePath(), asset.sha256(), asset.policy())),
            lesson.expectedArtifacts(),
            lesson.completionCriteria(),
            lesson.instructions(),
            lesson.hints(),
            lesson.question(),
            lesson.recommendedCommitMessage());
    DisclosurePlan.Conflicted result =
        assertInstanceOf(
            DisclosurePlan.Conflicted.class,
            useCase(stores).plan(ROOT, invalid, ManagedFiles.empty()));
    assertEquals(WorkspacePath.parse(path), result.conflicts().getFirst().path());
    assertEquals("TOOL_METADATA", result.conflicts().getFirst().reason().name());
    assertEquals(List.of(), stores.events);
  }

  @Test
  void existingOwnershipMismatchReturnsConflictBeforeCreatingJournal() throws IOException {
    Stores stores = new Stores();
    var asset = lesson.assets().getFirst();
    ManagedFile owned =
        new ManagedFile(
            WorkspacePath.parse(asset.relativePath()),
            asset.id(),
            asset.sha256(),
            previous.activeLessonId().orElseThrow(),
            asset.policy());
    stores.managed = Optional.of(new ManagedFiles(List.of(owned)));
    DisclosureResult.Conflict result =
        assertInstanceOf(
            DisclosureResult.Conflict.class,
            useCase(stores).apply(ROOT, lesson, stores.managed, Optional.of(previous), intended));
    assertEquals(
        List.of(new DisclosureConflict(owned.path(), DisclosureConflict.Reason.OWNERSHIP_MISMATCH)),
        result.paths());
    assertEquals(List.of(), stores.events);
    assertEquals(Optional.of(previous), stores.progress);
  }

  private DiscloseLesson useCase(Stores stores) {
    return new DiscloseLesson(
        catalog, stores, stores.manifests, stores.progresses, stores.journals);
  }

  private Stores pending() {
    Stores stores = new Stores();
    stores.failure = "asset:src/Raw.txt";
    assertThrows(
        IOException.class,
        () ->
            useCase(stores).apply(ROOT, lesson, Optional.empty(), Optional.of(previous), intended));
    stores.failure = "";
    stores.events.clear();
    return stores;
  }

  private TransitionJournal replacement(TransitionJournal expected, String changed) {
    return new TransitionJournal(
        expected.formatVersion(),
        expected.fromLessonId(),
        expected.toLessonId(),
        expected.assets(),
        expected.expectedManifestVersion(),
        changed.equals("ownership")
            ? Optional.of(ManagedFiles.empty())
            : expected.expectedManaged(),
        expected.expectedProgress(),
        changed.equals("revision")
            ? previous.advance(previous.activeLessonId().orElseThrow(), "yes", "other").progress()
            : expected.intendedProgress());
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

  private final class Stores implements WorkspaceFiles {
    private final List<String> events = new ArrayList<>();
    private final Map<WorkspacePath, DisclosurePlan.Observation> observations =
        new LinkedHashMap<>();
    private Optional<ManagedFiles> managed = Optional.empty();
    private Optional<CourseProgress> progress = Optional.of(previous);
    private Optional<TransitionJournal> journal = Optional.empty();
    private String failure = "";
    private boolean editDuringManifest;
    private boolean changeProgressDuringManifest;
    private Runnable afterPreflight = () -> {};
    private Runnable afterAssetWrite = () -> {};
    private final ManagedFilesRepository manifests =
        new ManagedFilesRepository() {
          @Override
          public Optional<ManagedFiles> load(Path root) {
            return managed;
          }

          @Override
          public void save(Path root, ManagedFiles value) throws IOException {
            event("manifest");
            managed = Optional.of(value);
            if (editDuringManifest) {
              observations.put(
                  WorkspacePath.parse("src/Raw.txt"),
                  new DisclosurePlan.RegularFile("a".repeat(64)));
            }
            if (changeProgressDuringManifest) {
              progress = Optional.of(previous.revealHint(previous.activeLessonId().orElseThrow()));
            }
          }
        };
    private final ProgressRepository progresses =
        new ProgressRepository() {
          @Override
          public Optional<CourseProgress> load(Path root) {
            return progress;
          }

          @Override
          public void save(Path root, CourseProgress value) throws IOException {
            event("progress");
            progress = Optional.of(value);
          }
        };
    private final TransitionJournalRepository journals =
        new TransitionJournalRepository() {
          @Override
          public Optional<TransitionJournal> load(Path root) {
            return journal;
          }

          @Override
          public void create(Path root, TransitionJournal value) throws IOException {
            event("journal");
            journal = Optional.of(value);
          }

          @Override
          public void remove(Path root, TransitionJournal value) throws IOException {
            event("remove");
            assertEquals(Optional.of(value), journal);
            journal = Optional.empty();
          }
        };

    private void event(String value) throws IOException {
      events.add(value);
      if (failure.equals(value)) {
        throw new IOException("Injected failure");
      }
    }

    @Override
    public DisclosurePlan.Observation inspect(Path root, WorkspacePath path) {
      return observations.getOrDefault(path, new DisclosurePlan.Missing());
    }

    @Override
    public Optional<byte[]> read(Path root, WorkspacePath path) throws IOException {
      throw new IOException("Disclosure does not read learner source bytes.");
    }

    @Override
    public DisclosurePlan preflight(Path root, List<ManagedFile> requested, ManagedFiles known) {
      Map<WorkspacePath, DisclosurePlan.Observation> facts = new LinkedHashMap<>();
      requested.forEach(file -> facts.put(file.path(), inspect(root, file.path())));
      DisclosurePlan plan = DisclosurePlan.evaluate(requested, known, facts);
      afterPreflight.run();
      return plan;
    }

    @Override
    public void writeNewSafely(Path root, WorkspacePath path, byte[] bytes) throws IOException {
      event("asset:" + path.value());
      observations.put(path, new DisclosurePlan.RegularFile(lesson.assets().getFirst().sha256()));
      afterAssetWrite.run();
    }

    @Override
    public void writeNewSafely(Path root, DisclosurePlan plan, Map<WorkspacePath, byte[]> bytes) {
      throw new AssertionError("Each transaction asset must be applied separately.");
    }
  }
}
