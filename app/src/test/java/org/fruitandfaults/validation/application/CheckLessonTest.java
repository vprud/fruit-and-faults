package org.fruitandfaults.validation.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.fruitandfaults.course.domain.Course;
import org.fruitandfaults.course.domain.Lesson;
import org.fruitandfaults.course.domain.LessonId;
import org.fruitandfaults.course.infra.ClasspathCourseCatalog;
import org.fruitandfaults.validation.domain.CheckOutcome;
import org.fruitandfaults.validation.domain.Diagnostic;
import org.fruitandfaults.validation.domain.FailureCategory;
import org.fruitandfaults.validation.infra.GradleCheckClassifier;
import org.fruitandfaults.validation.infra.ManifestArtifactInspector;
import org.fruitandfaults.workspace.domain.ManagedFiles;
import org.fruitandfaults.workspace.infra.SafeWorkspaceFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CheckLessonTest {
  private final ClasspathCourseCatalog catalog = new ClasspathCourseCatalog("course");
  private final Course course = catalog.load();
  private final List<String> events = new ArrayList<>();
  private final List<ProcessRequest> processes = new ArrayList<>();
  private CheckOutcome artifacts = new CheckOutcome.Passed(List.of());
  private ProcessResult process = new ProcessResult.Exited(0, ProcessResult.Output.empty());
  private final Map<String, BehaviorValidator> validators = new LinkedHashMap<>();
  @TempDir private Path root;
  private Path progress;

  @BeforeEach
  void recordReadOnlyValidationStages() throws Exception {
    root = root.toRealPath().resolve("моя игра");
    Files.createDirectory(root);
    progress = root.resolve("progress-sentinel.json");
    Files.writeString(progress, "last valid progress");
    for (Lesson lesson : course.lessons()) {
      for (var criterion : lesson.completionCriteria()) {
        validators.put(
            criterion.id(),
            workspace -> {
              events.add(criterion.id());
              assertEquals(root, workspace);
              return new CheckOutcome.Passed(
                  List.of(
                      new Diagnostic(
                          criterion.description(), "Public behavior verified.", "Continue.")));
            });
      }
    }
  }

  @Test
  void checksArtifactsThenAllVisibleTestsThenEveryOpenedCriterion() throws Exception {
    CheckOutcome.Passed result =
        assertInstanceOf(
            CheckOutcome.Passed.class,
            checker(CheckLesson.WrapperPlatform.UNIX).execute(request(3)));
    assertEquals(
        List.of(
            "artifacts",
            "gradle",
            "starter-public-result",
            "coordinate-direction",
            "field-valid-move",
            "game-state"),
        events);
    assertEquals(5, result.diagnostics().size());
    ProcessRequest build = processes.getFirst();
    assertEquals(root, build.workingDirectory());
    assertTrue(build.arguments().contains("test"));
    assertFalse(
        build.arguments().contains("--tests"),
        "The full test task keeps all prior visible tests selected");
    assertTrue(build.arguments().contains("--offline"));
    assertTrue(build.timeout().compareTo(Duration.ZERO) > 0);
    assertTrue(build.maxCapturedBytes() > 0);
    assertUnchangedProgress();
  }

  @ParameterizedTest
  @EnumSource(CheckLesson.WrapperPlatform.class)
  void platformWrapperCommandsUseSeparateArguments(CheckLesson.WrapperPlatform platform) {
    assertInstanceOf(CheckOutcome.Passed.class, checker(platform).execute(request(0)));
    List<String> arguments = processes.getFirst().arguments();
    assertEquals(
        platform == CheckLesson.WrapperPlatform.WINDOWS
            ? List.of("cmd", "/d", "/c", "gradlew.bat")
            : List.of("sh", "./gradlew"),
        arguments.subList(0, platform == CheckLesson.WrapperPlatform.WINDOWS ? 4 : 2));
  }

  @Test
  void artifactFailureStopsBeforeGradleOrBehaviorAndPreservesProgress() throws Exception {
    artifacts = failure(FailureCategory.WORKSPACE_CONFLICT);
    assertEquals(artifacts, checker(CheckLesson.WrapperPlatform.UNIX).execute(request(1)));
    assertEquals(List.of("artifacts"), events);
    assertTrue(processes.isEmpty());
    assertUnchangedProgress();
  }

  @Test
  void gradleFailureStopsEmbeddedChecksAndPreservesProgress() throws Exception {
    process =
        new ProcessResult.Exited(
            1,
            new ProcessResult.Output(
                "> Task :test FAILED\nStarterTest > message FAILED\n> There were failing tests.",
                "",
                false,
                false));
    CheckOutcome.Failed result =
        failed(checker(CheckLesson.WrapperPlatform.UNIX).execute(request(2)));
    assertEquals(FailureCategory.TEST_FAILURE, result.category());
    assertEquals(List.of("artifacts", "gradle"), events);
    assertUnchangedProgress();
  }

  @Test
  void timeoutAndInterruptionRemainTypedAndNeverChangeProgress() throws Exception {
    process =
        new ProcessResult.TimedOut(ProcessResult.Output.empty(), ProcessResult.Cleanup.COMPLETE);
    assertEquals(
        FailureCategory.TIMEOUT,
        failed(checker(CheckLesson.WrapperPlatform.UNIX).execute(request(0))).category());
    process =
        new ProcessResult.Interrupted(ProcessResult.Output.empty(), ProcessResult.Cleanup.COMPLETE);
    assertEquals(
        FailureCategory.INTERRUPTED,
        failed(checker(CheckLesson.WrapperPlatform.UNIX).execute(request(0))).category());
    assertEquals(List.of("artifacts", "gradle", "artifacts", "gradle"), events);
    assertUnchangedProgress();
  }

  @ParameterizedTest
  @EnumSource(
      value = FailureCategory.class,
      names = {"TIMEOUT", "INTERRUPTED", "WORKSPACE_CONFLICT"})
  void workerCancellationOrUnsafeClassesStopRemainingValidatorsAndRetainCategory(
      FailureCategory category) throws Exception {
    validators.put(
        "starter-public-result",
        workspace -> {
          events.add("starter-public-result");
          return failure(FailureCategory.INCOMPLETE_WORK);
        });
    validators.put(
        "coordinate-direction",
        workspace -> {
          events.add("coordinate-direction");
          return failure(category);
        });
    CheckOutcome.Failed result =
        failed(checker(CheckLesson.WrapperPlatform.UNIX).execute(request(3)));
    assertEquals(category, result.category());
    assertEquals(
        List.of("artifacts", "gradle", "starter-public-result", "coordinate-direction"), events);
    assertUnchangedProgress();
  }

  @Test
  void earlierBehaviorFailureDoesNotSkipRemainingOpenedCriteria() throws Exception {
    validators.put(
        "starter-public-result",
        workspace -> {
          events.add("starter-public-result");
          return failure(FailureCategory.INCOMPLETE_WORK);
        });
    CheckOutcome.Failed result =
        failed(checker(CheckLesson.WrapperPlatform.UNIX).execute(request(2)));
    assertEquals(FailureCategory.INCOMPLETE_WORK, result.category());
    assertEquals(
        List.of(
            "artifacts",
            "gradle",
            "starter-public-result",
            "coordinate-direction",
            "field-valid-move"),
        events);
    assertUnchangedProgress();
  }

  @Test
  void futureValidatorsAreNotRunAndUnknownOpenedCriteriaAreInternalErrors() throws Exception {
    validators.put(
        "game-state",
        workspace -> {
          throw new AssertionError("Future criterion ran");
        });
    assertInstanceOf(
        CheckOutcome.Passed.class, checker(CheckLesson.WrapperPlatform.UNIX).execute(request(1)));
    validators.remove("coordinate-direction");
    assertEquals(
        FailureCategory.INTERNAL_ERROR,
        failed(checker(CheckLesson.WrapperPlatform.UNIX).execute(request(1))).category());
    assertUnchangedProgress();
  }

  @Test
  void adapterExceptionsProduceSafeInternalDiagnostics() throws Exception {
    validators.put(
        "starter-public-result",
        workspace -> {
          throw new IllegalStateException("secret /outside/path\u001b[31m".repeat(1000));
        });
    CheckOutcome.Failed result =
        failed(checker(CheckLesson.WrapperPlatform.UNIX).execute(request(0)));
    assertEquals(FailureCategory.INTERNAL_ERROR, result.category());
    assertFalse(result.diagnostics().toString().contains("secret"));
    assertUnchangedProgress();
  }

  @Test
  void requestNormalizesRootAndRejectsALessonOutsideTheCourse() {
    CheckRequest normalized =
        new CheckRequest(
            root.resolve("absent/.."),
            course,
            course.lessonOrder().getFirst(),
            ManagedFiles.empty());
    assertEquals(root, normalized.workspaceRoot());
    assertThrows(
        IllegalArgumentException.class,
        () -> new CheckRequest(root, course, new LessonId("unrelated"), ManagedFiles.empty()));
  }

  @Test
  void unsafeOrMissingWorkspaceCannotReachTheProcessRunner() throws Exception {
    Path link = root.resolveSibling("linked-workspace");
    Files.createSymbolicLink(link, root);
    ArtifactInspector real = new ManifestArtifactInspector(new SafeWorkspaceFiles(), catalog);
    CheckLesson checker =
        new CheckLesson(
            real,
            request -> {
              throw new AssertionError("Unsafe workspace ran");
            },
            new GradleCheckClassifier()::classify,
            validators,
            CheckLesson.WrapperPlatform.UNIX);
    assertEquals(
        FailureCategory.WORKSPACE_CONFLICT,
        failed(
                checker.execute(
                    new CheckRequest(
                        link, course, course.lessonOrder().getFirst(), ManagedFiles.empty())))
            .category());
    assertUnchangedProgress();
  }

  private CheckLesson checker(CheckLesson.WrapperPlatform platform) {
    ArtifactInspector inspector =
        (workspace, lessons, manifest) -> {
          events.add("artifacts");
          assertEquals(root, workspace);
          assertEquals(course.lessons().subList(0, lessons.size()), lessons);
          return artifacts;
        };
    ProcessRunner runner =
        request -> {
          events.add("gradle");
          processes.add(request);
          return process;
        };
    return new CheckLesson(
        inspector, runner, new GradleCheckClassifier()::classify, validators, platform);
  }

  private CheckRequest request(int lesson) {
    return new CheckRequest(root, course, course.lessonOrder().get(lesson), ManagedFiles.empty());
  }

  private void assertUnchangedProgress() throws Exception {
    assertEquals("last valid progress", Files.readString(progress));
  }

  private static CheckOutcome.Failed failure(FailureCategory category) {
    return new CheckOutcome.Failed(
        category,
        List.of(
            new Diagnostic(
                "Expected valid lesson work.",
                "Observed incomplete validation.",
                "Inspect the lesson and retry.")));
  }

  private static CheckOutcome.Failed failed(CheckOutcome outcome) {
    return assertInstanceOf(CheckOutcome.Failed.class, outcome);
  }
}
