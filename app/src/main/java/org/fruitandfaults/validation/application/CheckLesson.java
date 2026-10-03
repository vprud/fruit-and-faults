package org.fruitandfaults.validation.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.fruitandfaults.course.domain.Lesson;
import org.fruitandfaults.validation.domain.CheckOutcome;
import org.fruitandfaults.validation.domain.Diagnostic;
import org.fruitandfaults.validation.domain.FailureCategory;

/** Runs cumulative artifact, visible-test, and public-behavior checks without writing progress. */
public final class CheckLesson {
  private final ArtifactInspector artifacts;
  private final GradlePreflight preflight;
  private final ProcessRunner runner;
  private final Function<ProcessResult, CheckOutcome> classifier;
  private final Map<String, BehaviorValidator> validators;
  private final WrapperPlatform platform;

  /**
   * Composes read-only validation boundaries; no progress-writing port is available.
   *
   * @param artifacts cumulative safe artifact inspection
   * @param preflight exact local wrapper-cache verification before any Gradle launch
   * @param runner bounded process execution
   * @param classifier conservative classification of build facts
   * @param validators public-behavior validators keyed by stable criterion ID
   * @param platform explicit wrapper execution platform
   */
  public CheckLesson(
      ArtifactInspector artifacts,
      GradlePreflight preflight,
      ProcessRunner runner,
      Function<ProcessResult, CheckOutcome> classifier,
      Map<String, BehaviorValidator> validators,
      WrapperPlatform platform) {
    this.artifacts = Objects.requireNonNull(artifacts);
    this.preflight = Objects.requireNonNull(preflight);
    this.runner = Objects.requireNonNull(runner);
    this.classifier = Objects.requireNonNull(classifier);
    this.validators = Map.copyOf(validators);
    this.platform = Objects.requireNonNull(platform);
  }

  /**
   * Checks artifacts before Gradle and every opened criterion after the complete test task passes.
   *
   * @param request selected lesson and immutable workspace/course facts
   * @return typed result without persisting hints, answers, or completion
   */
  public CheckOutcome execute(CheckRequest request) {
    List<Lesson> opened =
        request
            .course()
            .lessons()
            .subList(0, request.course().lessonOrder().indexOf(request.activeLessonId()) + 1);
    List<Diagnostic> observations = new ArrayList<>();
    try {
      CheckOutcome artifactResult =
          artifacts.inspect(request.workspaceRoot(), opened, request.manifest());
      observations.addAll(artifactResult.diagnostics());
      if (artifactResult instanceof CheckOutcome.Failed) {
        return artifactResult;
      }
      GradlePreflight.Preparation preparation = preflight.prepare(request.workspaceRoot());
      if (preparation instanceof GradlePreflight.Unavailable unavailable) {
        observations.addAll(unavailable.outcome().diagnostics());
        return new CheckOutcome.Failed(unavailable.outcome().category(), observations);
      }
      GradlePreflight.Ready ready = (GradlePreflight.Ready) preparation;
      List<String> command =
          new ArrayList<>(
              switch (platform) {
                case WINDOWS -> List.of("cmd", "/d", "/c", "gradlew.bat");
                case UNIX -> List.of("sh", "./gradlew");
              });
      command.addAll(List.of("test", "--offline", "--no-daemon", "--console=plain"));
      command.addAll(List.of("--gradle-user-home", ready.gradleUserHome().toString()));
      CheckOutcome build =
          classifier.apply(
              runner.run(
                  new ProcessRequest(
                      command, request.workspaceRoot(), Duration.ofSeconds(90), 262_144)));
      observations.addAll(build.diagnostics());
      if (build instanceof CheckOutcome.Failed failed) {
        return new CheckOutcome.Failed(failed.category(), observations);
      }
      FailureCategory category = null;
      for (Lesson lesson : opened) {
        for (var criterion : lesson.completionCriteria()) {
          BehaviorValidator validator = validators.get(criterion.id());
          CheckOutcome behavior;
          if (validator == null) {
            behavior = internalFailure();
          } else {
            try {
              behavior = validator.validate(request.workspaceRoot());
            } catch (RuntimeException adapterFailure) {
              behavior = internalFailure();
            }
          }
          observations.addAll(behavior.diagnostics());
          if (behavior instanceof CheckOutcome.Failed failed
              && (failed.category() == FailureCategory.TIMEOUT
                  || failed.category() == FailureCategory.INTERRUPTED
                  || failed.category() == FailureCategory.WORKSPACE_CONFLICT)) {
            return new CheckOutcome.Failed(failed.category(), observations);
          }
          if (behavior instanceof CheckOutcome.Failed failed
              && (category == null || failed.category() == FailureCategory.INTERNAL_ERROR)) {
            category = failed.category();
          }
        }
      }
      return category == null
          ? new CheckOutcome.Passed(observations)
          : new CheckOutcome.Failed(category, observations);
    } catch (RuntimeException adapterFailure) {
      observations.addAll(internalFailure().diagnostics());
      return new CheckOutcome.Failed(FailureCategory.INTERNAL_ERROR, observations);
    }
  }

  private static CheckOutcome.Failed internalFailure() {
    return new CheckOutcome.Failed(
        FailureCategory.INTERNAL_ERROR,
        List.of(
            new Diagnostic(
                "Usable installed validation adapters for every opened criterion.",
                "A validation adapter was unavailable or failed unexpectedly.",
                "Check the installed course and CLI, then retry check.")));
  }

  /** Explicit supported wrapper invocation alternatives, selected at the composition root. */
  public enum WrapperPlatform {
    /** Uses cmd with autorun disabled to execute the Windows batch wrapper. */
    WINDOWS,
    /** Uses sh to execute the disclosed Unix wrapper without requiring executable mode. */
    UNIX
  }
}
