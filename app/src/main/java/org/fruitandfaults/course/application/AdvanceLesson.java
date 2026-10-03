package org.fruitandfaults.course.application;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.fruitandfaults.course.domain.Course;
import org.fruitandfaults.course.domain.Lesson;
import org.fruitandfaults.git.application.GitInitializationException;
import org.fruitandfaults.git.application.GitLessonGate;
import org.fruitandfaults.git.application.GitRepository;
import org.fruitandfaults.git.application.GitStatus;
import org.fruitandfaults.lesson.EvaluateReflection;
import org.fruitandfaults.lesson.ReflectionResult;
import org.fruitandfaults.progress.application.ProgressRepository;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.fruitandfaults.validation.application.CheckRequest;
import org.fruitandfaults.validation.domain.CheckOutcome;
import org.fruitandfaults.validation.domain.Diagnostic;
import org.fruitandfaults.validation.domain.FailureCategory;
import org.fruitandfaults.workspace.application.DiscloseLesson;
import org.fruitandfaults.workspace.application.DisclosureResult;
import org.fruitandfaults.workspace.application.ManagedFilesRepository;
import org.fruitandfaults.workspace.application.TransitionJournalRepository;
import org.fruitandfaults.workspace.domain.DisclosureConflict;
import org.fruitandfaults.workspace.domain.DisclosurePlan;
import org.fruitandfaults.workspace.domain.ManagedFiles;
import org.fruitandfaults.workspace.domain.TransitionJournal;

/** Checks, recognizes, gates, previews, and commits one lesson transition in that order. */
public final class AdvanceLesson {
  private final CourseCatalog catalog;
  private final ProgressRepository progress;
  private final ManagedFilesRepository manifests;
  private final TransitionJournalRepository journals;
  private final Function<CheckRequest, CheckOutcome> check;
  private final GitRepository git;
  private final DiscloseLesson disclosure;

  /**
   * Composes existing boundaries without writing answers before the final transition commit.
   *
   * @param catalog installed content
   * @param progress validated atomic progress
   * @param manifests validated ownership
   * @param journals exact pending disclosure plans
   * @param check current lesson validation, normally CheckLesson::execute
   * @param git read-only local facts
   * @param disclosure transactional write-once disclosure
   */
  public AdvanceLesson(
      CourseCatalog catalog,
      ProgressRepository progress,
      ManagedFilesRepository manifests,
      TransitionJournalRepository journals,
      Function<CheckRequest, CheckOutcome> check,
      GitRepository git,
      DiscloseLesson disclosure) {
    this.catalog = Objects.requireNonNull(catalog);
    this.progress = Objects.requireNonNull(progress);
    this.manifests = Objects.requireNonNull(manifests);
    this.journals = Objects.requireNonNull(journals);
    this.check = Objects.requireNonNull(check);
    this.git = Objects.requireNonNull(git);
    this.disclosure = Objects.requireNonNull(disclosure);
  }

  /**
   * Attempts exactly one transition, returning safe failures while retaining prior valid progress.
   *
   * @param request selected workspace, stable answer, and explicit confirmation
   * @return prompt, failed gate, preview, durable transition, or safe conflict
   */
  public AdvanceResult execute(AdvanceRequest request) {
    if (Thread.currentThread().isInterrupted()) return unavailable(FailureCategory.INTERRUPTED);
    Course installed;
    try {
      installed = catalog.load();
    } catch (RuntimeException invalid) {
      return unavailable(FailureCategory.INTERNAL_ERROR);
    }
    try {
      CourseProgress current =
          progress.load(request.root()).orElseThrow(() -> new IOException("Missing progress."));
      ListLessons.requireCompatible(installed, current);
      ManagedFiles managed =
          manifests.load(request.root()).orElseThrow(() -> new IOException("Missing ownership."));
      Optional<TransitionJournal> pending = journals.load(request.root());
      if (pending.isPresent()) {
        return recover(request, installed, current, managed, pending.orElseThrow());
      }
      ShowStatus.requireOwnership(opened(current), managed);
      if (current.activeLessonId().isEmpty()
          && current.lessons().size() == installed.lessons().size()) {
        return complete(current);
      }
      if (current.activeLessonId().isEmpty()) {
        GitStatus status = git.status(request.root());
        GitLessonGate.Decision gate = GitLessonGate.evaluate(status, null);
        if (gate != GitLessonGate.Decision.READY)
          return new AdvanceResult.GitBlocked(gate, advice(status));
        return disclose(
            request,
            current,
            managed,
            status,
            current.continueWith(installed, status.headRevision().orElseThrow()));
      }
      Lesson active =
          current
              .course()
              .lessons()
              .get(current.course().lessonOrder().indexOf(current.activeLessonId().orElseThrow()));
      CheckOutcome outcome =
          check.apply(new CheckRequest(request.root(), current.course(), active.id(), managed));
      if (outcome instanceof CheckOutcome.Failed failed) {
        return new AdvanceResult.CheckFailed(failed);
      }
      if (request.answer().isEmpty()) {
        return new AdvanceResult.NeedsAnswer(
            active.id(),
            active.question().id(),
            active.question().prompt(),
            active.question().options().stream()
                .map(option -> new AdvanceResult.Option(option.id(), option.text()))
                .toList());
      }
      ReflectionResult reflection =
          EvaluateReflection.evaluate(active.question(), request.answer().orElseThrow());
      if (reflection instanceof ReflectionResult.Incorrect wrong) {
        return new AdvanceResult.Incorrect(wrong.feedback());
      }
      if (reflection instanceof ReflectionResult.UnknownOption unknown) {
        return new AdvanceResult.Incorrect(unknown.feedback());
      }
      GitStatus status = git.status(request.root());
      List<String> advice = advice(status);
      GitLessonGate.Decision gate =
          GitLessonGate.evaluate(
              status,
              active.id().equals(current.course().lessonOrder().getFirst())
                  ? null
                  : current.activeLessonOpenedAtRevision().orElse(null));
      if (gate != GitLessonGate.Decision.READY) {
        return new AdvanceResult.GitBlocked(gate, advice);
      }
      CourseProgress intended =
          current
              .continueWith(installed, null)
              .advance(
                  active.id(),
                  ((ReflectionResult.Correct) reflection).answer().optionId(),
                  status.headRevision().orElseThrow())
              .progress();
      if (intended.activeLessonId().isEmpty()) {
        if (!request.confirmed()) {
          return new AdvanceResult.PreviewRequired(
              active.id(), new DisclosurePlan.Applicable(List.of(), List.of()), intended, advice);
        }
        if (!unchanged(request.root(), current, managed, status)) return conflict(List.of());
        progress.save(request.root(), intended);
        if (!progress.load(request.root()).equals(Optional.of(intended)))
          return conflict(List.of());
        return complete(intended);
      }
      return disclose(request, current, managed, status, intended);
    } catch (GitInitializationException failed) {
      return unavailable(
          switch (failed.reason()) {
            case TIMEOUT -> FailureCategory.TIMEOUT;
            case INTERRUPTED -> FailureCategory.INTERRUPTED;
            case EXIT_FAILURE, UNAVAILABLE -> FailureCategory.WORKSPACE_CONFLICT;
          });
    } catch (IOException | IllegalArgumentException failed) {
      return unavailable(FailureCategory.WORKSPACE_CONFLICT);
    } catch (RuntimeException failed) {
      return unavailable(FailureCategory.INTERNAL_ERROR);
    }
  }

  private AdvanceResult disclose(
      AdvanceRequest request,
      CourseProgress current,
      ManagedFiles managed,
      GitStatus status,
      CourseProgress intended)
      throws IOException {
    List<String> advice = advice(status);
    Lesson target =
        intended
            .course()
            .lessons()
            .get(intended.course().lessonOrder().indexOf(intended.activeLessonId().orElseThrow()));
    DisclosurePlan plan = disclosure.plan(request.root(), target, managed);
    if (plan instanceof DisclosurePlan.Conflicted rejected) return conflict(rejected.conflicts());
    if (!request.confirmed())
      return new AdvanceResult.PreviewRequired(
          target.id(), (DisclosurePlan.Applicable) plan, intended, advice);
    if (!unchanged(request.root(), current, managed, status)) return conflict(List.of());
    DisclosureResult applied =
        disclosure.apply(
            request.root(), target, Optional.of(managed), Optional.of(current), intended);
    if (applied instanceof DisclosureResult.Conflict rejected) return conflict(rejected.paths());
    return new AdvanceResult.Advanced(intended, target, advice);
  }

  private AdvanceResult recover(
      AdvanceRequest request,
      Course installed,
      CourseProgress current,
      ManagedFiles managed,
      TransitionJournal journal)
      throws IOException {
    ListLessons.requireCompatible(installed, journal.intendedProgress());
    if (journal.expectedProgress().isEmpty() || journal.expectedManaged().isEmpty())
      return conflict(List.of());
    try {
      ShowStatus.requireOwnership(
          opened(journal.expectedProgress().orElseThrow()),
          journal.expectedManaged().orElseThrow());
    } catch (IOException invalidOwnership) {
      return conflict(List.of());
    }
    boolean committed = current.equals(journal.intendedProgress());
    boolean published = managed.equals(journal.intendedManaged());
    if ((!current.equals(journal.expectedProgress().orElseThrow()) && !committed)
        || (!managed.equals(journal.expectedManaged().orElseThrow()) && !published)
        || (committed && !published)) return conflict(List.of());
    if (journal.fromLessonId().isPresent() && request.answer().isPresent()) {
      int index =
          journal
              .intendedProgress()
              .course()
              .lessonOrder()
              .indexOf(journal.fromLessonId().orElseThrow());
      if (!journal
          .intendedProgress()
          .lessons()
          .get(index)
          .completedOptionId()
          .equals(Optional.of(request.answer().orElseThrow().optionId())))
        return conflict(List.of());
    } else if (journal.fromLessonId().isEmpty() && request.answer().isPresent()) {
      return conflict(List.of());
    }
    GitStatus status = git.status(request.root());
    if (!status.headRevision().equals(journal.intendedProgress().activeLessonOpenedAtRevision()))
      return conflict(List.of());
    Lesson target =
        journal
            .intendedProgress()
            .course()
            .lessons()
            .get(journal.intendedProgress().course().lessonOrder().indexOf(journal.toLessonId()));
    DisclosurePlan plan = disclosure.plan(request.root(), target, journal.intendedManaged());
    if (plan instanceof DisclosurePlan.Conflicted rejected) return conflict(rejected.conflicts());
    if (!request.confirmed())
      return new AdvanceResult.PreviewRequired(
          target.id(),
          (DisclosurePlan.Applicable) plan,
          journal.intendedProgress(),
          advice(status));
    if (!git.status(request.root()).headRevision().equals(status.headRevision())
        || !journals.load(request.root()).equals(Optional.of(journal))) return conflict(List.of());
    DisclosureResult applied = disclosure.recover(request.root(), journal);
    if (applied instanceof DisclosureResult.Conflict rejected) return conflict(rejected.paths());
    return new AdvanceResult.Recovered(journal.intendedProgress(), target, advice(status));
  }

  private boolean unchanged(
      Path root, CourseProgress expected, ManagedFiles managed, GitStatus status)
      throws IOException {
    return git.status(root).equals(status)
        && progress.load(root).equals(Optional.of(expected))
        && manifests.load(root).equals(Optional.of(managed))
        && journals.load(root).isEmpty();
  }

  private static List<Lesson> opened(CourseProgress state) {
    int count =
        state
            .activeLessonId()
            .map(id -> state.course().lessonOrder().indexOf(id) + 1)
            .orElse(state.lessons().size());
    return state.course().lessons().subList(0, count);
  }

  private static List<String> advice(GitStatus status) {
    List<String> result = new ArrayList<>();
    if (!status.originPresent())
      result.add("origin is absent; publishing to GitHub is optional and can be completed later.");
    if (!status.upstreamPresent())
      result.add(
          "An upstream branch is absent; publishing is optional and does not block the course.");
    return List.copyOf(result);
  }

  private static AdvanceResult.CourseComplete complete(CourseProgress state) {
    return new AdvanceResult.CourseComplete(
        state,
        List.of("Make one final metadata commit so a clone also observes course completion."));
  }

  private static AdvanceResult.Conflict conflict(List<DisclosureConflict> paths) {
    return new AdvanceResult.Conflict(
        new Diagnostic(
            "An unchanged exact disclosure plan and prior course state.",
            "Course state, a pending transition, or target files conflict with this next invocation.",
            "Preserve learner files and inspect the listed paths and pending metadata before retrying next."),
        paths);
  }

  private static AdvanceResult.Unavailable unavailable(FailureCategory category) {
    return new AdvanceResult.Unavailable(
        category,
        new Diagnostic(
            "Valid compatible course state and safely completed local transition operations.",
            switch (category) {
              case TIMEOUT -> "Local Git inspection exceeded its deadline.";
              case INTERRUPTED -> "Local transition inspection was interrupted.";
              case INTERNAL_ERROR ->
                  "Installed content or a transition adapter failed unexpectedly.";
              default ->
                  "Progress, ownership, Git, preview, or disclosure state was unavailable or unsafe.";
            },
            "Preserve the workspace and pending journal, inspect local state, then retry next."));
  }
}
