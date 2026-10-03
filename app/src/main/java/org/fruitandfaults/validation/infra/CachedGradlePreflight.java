package org.fruitandfaults.validation.infra;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.fruitandfaults.validation.application.GradlePreflight;
import org.fruitandfaults.validation.application.ProcessResult;
import org.fruitandfaults.validation.application.ProcessRunner;
import org.fruitandfaults.validation.domain.CheckOutcome;
import org.fruitandfaults.validation.domain.Diagnostic;
import org.fruitandfaults.validation.domain.FailureCategory;
import org.jspecify.annotations.Nullable;

/** Bounds wrapper metadata/cache inspection and pins the same effective home at wrapper launch. */
public final class CachedGradlePreflight implements GradlePreflight {
  private final ProcessRunner runner;
  private final @Nullable String propertyHome;
  private final @Nullable String environmentHome;
  private final String userHome;

  /**
   * Uses native path conventions and property, environment, then user.home/.gradle precedence.
   *
   * @param runner bounded worker owner
   */
  public CachedGradlePreflight(ProcessRunner runner) {
    this(
        runner,
        System.getProperty("gradle.user.home"),
        System.getenv("GRADLE_USER_HOME"),
        System.getProperty("user.home", ""));
  }

  /**
   * Selects explicit environment facts without modifying global process properties or environment.
   *
   * @param runner bounded worker owner
   * @param propertyHome optional gradle.user.home override
   * @param environmentHome optional GRADLE_USER_HOME override
   * @param userHome native user.home used only if neither override exists
   */
  public CachedGradlePreflight(
      ProcessRunner runner,
      @Nullable String propertyHome,
      @Nullable String environmentHome,
      String userHome) {
    this.runner = Objects.requireNonNull(runner);
    this.propertyHome = propertyHome;
    this.environmentHome = environmentHome;
    this.userHome = Objects.requireNonNull(userHome);
  }

  @Override
  public Preparation prepare(Path workspaceRoot) {
    Path home;
    try {
      String selected = propertyHome != null ? propertyHome : environmentHome;
      if (selected == null) {
        if (userHome.isBlank() || !Path.of(userHome).isAbsolute()) {
          return unavailable(FailureCategory.INTERNAL_ERROR);
        }
        home = Path.of(userHome).resolve(".gradle");
      } else {
        if (selected.isBlank()) {
          return unavailable(FailureCategory.INTERNAL_ERROR);
        }
        home = workspaceRoot.toAbsolutePath().normalize().resolve(Path.of(selected)).normalize();
      }
    } catch (RuntimeException invalidPath) {
      return unavailable(FailureCategory.INTERNAL_ERROR);
    }
    WorkerProcess.Reply reply =
        WorkerProcess.run(runner, workspaceRoot, GradleCacheWorker.class, List.of(home.toString()));
    return switch (reply.result()) {
      case ProcessResult.TimedOut timed ->
          new Unavailable(
              WorkerProcess.withCleanup(
                  unavailable(FailureCategory.TIMEOUT).outcome(), timed.cleanup()));
      case ProcessResult.Interrupted interrupted ->
          new Unavailable(
              WorkerProcess.withCleanup(
                  unavailable(FailureCategory.INTERRUPTED).outcome(), interrupted.cleanup()));
      case ProcessResult.Failed ignored -> unavailable(FailureCategory.INTERNAL_ERROR);
      case ProcessResult.Exited ignored ->
          switch (reply.payload().orElse("")) {
            case "READY" -> new Ready(home);
            case "MISSING_ARTIFACT" -> unavailable(FailureCategory.MISSING_ARTIFACT);
            default -> unavailable(FailureCategory.INTERNAL_ERROR);
          };
    };
  }

  private static Unavailable unavailable(FailureCategory category) {
    return new Unavailable(
        new CheckOutcome.Failed(
            category,
            List.of(
                new Diagnostic(
                    "The exact wrapper distribution, completion marker, and regular payload already installed locally.",
                    "The local Gradle distribution was missing, unsafe, malformed, or could not be verified; the wrapper was not launched.",
                    "Prepare the declared Gradle distribution in the selected Gradle user home explicitly, then retry offline check."))));
  }
}
