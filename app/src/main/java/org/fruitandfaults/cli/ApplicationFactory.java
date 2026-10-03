package org.fruitandfaults.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.fruitandfaults.course.application.AdvanceLesson;
import org.fruitandfaults.course.application.AdvanceRequest;
import org.fruitandfaults.course.application.AdvanceResult;
import org.fruitandfaults.course.application.CourseAssets;
import org.fruitandfaults.course.application.CourseCatalog;
import org.fruitandfaults.course.application.CourseStatus;
import org.fruitandfaults.course.application.HintResult;
import org.fruitandfaults.course.application.LessonSummary;
import org.fruitandfaults.course.application.ListLessons;
import org.fruitandfaults.course.application.ShowHint;
import org.fruitandfaults.course.application.ShowStatus;
import org.fruitandfaults.course.domain.CourseCompatibility;
import org.fruitandfaults.course.infra.ClasspathCourseCatalog;
import org.fruitandfaults.git.application.GitRepository;
import org.fruitandfaults.git.infra.ProcessGitRepository;
import org.fruitandfaults.progress.infra.AtomicProgressRepository;
import org.fruitandfaults.progress.infra.JacksonProgressCodec;
import org.fruitandfaults.validation.application.CheckLesson;
import org.fruitandfaults.validation.application.CheckRequest;
import org.fruitandfaults.validation.domain.CheckOutcome;
import org.fruitandfaults.validation.domain.Diagnostic;
import org.fruitandfaults.validation.infra.BoundedProcessRunner;
import org.fruitandfaults.validation.infra.CachedGradlePreflight;
import org.fruitandfaults.validation.infra.CompiledGameLoader;
import org.fruitandfaults.validation.infra.CoordinateDirectionValidator;
import org.fruitandfaults.validation.infra.FieldMovementValidator;
import org.fruitandfaults.validation.infra.FirstRunValidator;
import org.fruitandfaults.validation.infra.GameStateValidator;
import org.fruitandfaults.validation.infra.GradleCheckClassifier;
import org.fruitandfaults.validation.infra.ManifestArtifactInspector;
import org.fruitandfaults.workspace.application.DiscloseLesson;
import org.fruitandfaults.workspace.application.StartCourse;
import org.fruitandfaults.workspace.application.StartRequest;
import org.fruitandfaults.workspace.application.StartResult;
import org.fruitandfaults.workspace.application.WorkspaceLocator;
import org.fruitandfaults.workspace.domain.WorkspaceMetadata;
import org.fruitandfaults.workspace.infra.JacksonManagedFilesRepository;
import org.fruitandfaults.workspace.infra.JacksonTransitionJournalRepository;
import org.fruitandfaults.workspace.infra.SafeWorkspaceFiles;
import org.fruitandfaults.workspace.infra.SafeWorkspaceSetup;
import org.fruitandfaults.workspace.infra.WalkingWorkspaceLocator;

/** The single CLI composition root for installed course content and real safe adapters. */
public final class ApplicationFactory {
  private ApplicationFactory() {}

  /**
   * Composes offline course commands with bounded processes and platform-specific wrapper
   * arguments.
   *
   * @return complete command ports; construction never starts a learner build
   */
  public static Application create() {
    var catalog = new ClasspathCourseCatalog("course");
    var runner = new BoundedProcessRunner();
    var loader = new CompiledGameLoader(runner);
    var classifier = new GradleCheckClassifier();
    var checks =
        new CheckLesson(
            new ManifestArtifactInspector(runner, catalog),
            new CachedGradlePreflight(runner),
            runner,
            classifier::classify,
            Map.of(
                "starter-public-result",
                new FirstRunValidator(loader),
                "coordinate-direction",
                new CoordinateDirectionValidator(loader),
                "field-valid-move",
                new FieldMovementValidator(loader),
                "game-state",
                new GameStateValidator(loader)),
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
                ? CheckLesson.WrapperPlatform.WINDOWS
                : CheckLesson.WrapperPlatform.UNIX);
    return compose(catalog, catalog, checks::execute, new ProcessGitRepository());
  }

  static Application compose(
      CourseCatalog catalog,
      CourseAssets assets,
      Function<CheckRequest, CheckOutcome> check,
      GitRepository git) {
    var course = catalog.load();
    var codec = new JacksonProgressCodec(catalog);
    var progress = new AtomicProgressRepository(codec);
    var manifests = new JacksonManagedFilesRepository();
    var journals = new JacksonTransitionJournalRepository(codec);
    var files = new SafeWorkspaceFiles();
    var setup = new SafeWorkspaceSetup();
    var disclosure = new DiscloseLesson(assets, files, manifests, progress, journals);
    var start = new StartCourse(course, setup, disclosure, progress, manifests, journals, git);
    var status = new ShowStatus(catalog, progress, manifests, files, git);
    var hint = new ShowHint(catalog, progress);
    var next = new AdvanceLesson(catalog, progress, manifests, journals, check, git, disclosure);
    var list = new ListLessons();
    return new Application(
        new WalkingWorkspaceLocator(new WorkspaceMetadata(course.id(), 1), setup),
        start::execute,
        status::execute,
        root -> {
          var current = progress.load(root).orElseThrow(() -> new IOException("Missing progress."));
          CourseCompatibility.requirePrefix(course, current.course());
          if (current.activeLessonId().isEmpty())
            return new CheckOutcome.Passed(
                List.of(
                    new Diagnostic(
                        "Завершённое сохранённое состояние курса.",
                        "Активного урока нет; проверки не запускались.",
                        "Выполните fruit-and-faults list или status для просмотра завершённого курса.")));
          var managed =
              manifests.load(root).orElseThrow(() -> new IOException("Missing manifest."));
          return check.apply(
              new CheckRequest(
                  root, current.course(), current.activeLessonId().orElseThrow(), managed));
        },
        hint::execute,
        next::execute,
        root ->
            list.execute(
                course,
                progress.load(root).orElseThrow(() -> new IOException("Missing progress."))));
  }

  /**
   * Narrow command ports suitable for injected journeys without framework entry points.
   *
   * @param locator safe upward discovery
   * @param start workspace initialization
   * @param status inexpensive observations
   * @param check cumulative validation
   * @param hint atomic hint reveal
   * @param next transactional lesson transition
   * @param list route-only presentation
   */
  public record Application(
      WorkspaceLocator locator,
      Function<StartRequest, StartResult> start,
      Function<Path, CourseStatus> status,
      WorkspaceAction<CheckOutcome> check,
      Function<Path, HintResult> hint,
      Function<AdvanceRequest, AdvanceResult> next,
      WorkspaceAction<List<LessonSummary>> list) {}

  /**
   * Workspace-scoped delivery operation that can report state-loading failure.
   *
   * @param <T> typed application result
   */
  @FunctionalInterface
  public interface WorkspaceAction<T> {
    /**
     * Executes against the safely discovered root.
     *
     * @param root selected workspace
     * @return typed result
     * @throws IOException if validated workspace state cannot be read
     */
    T execute(Path root) throws IOException;
  }
}
