package org.fruitandfaults.validation.infra;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.fruitandfaults.validation.application.ProcessResult;
import org.fruitandfaults.validation.domain.CheckOutcome;
import org.fruitandfaults.validation.domain.Diagnostic;
import org.fruitandfaults.validation.domain.FailureCategory;

/**
 * Classifies exit facts before output. Learner failures require a failed Gradle task plus compiler
 * location or failing-test evidence. Ambiguous, truncated-away, localized, or unfamiliar failures
 * remain infrastructure errors. Source markers are located with a linear scan before parsing a
 * bounded location snippet. Output can describe evidence but is never evaluated as commands.
 */
public final class GradleCheckClassifier {
  private static final Pattern ANSI = Pattern.compile("\u001b\\[[0-?]*[ -/]*[@-~]");
  private static final Pattern COMPILE_TASK =
      Pattern.compile("^> Task :(?:[\\w.-]+:)*compile(?:Test)?Java FAILED$");
  private static final Pattern TEST_TASK = Pattern.compile("^> Task :(?:[\\w.-]+:)*test FAILED$");
  private static final Pattern JUNIT_FAILURE = Pattern.compile("^[\\p{L}\\p{N}_.$]+ > .+ FAILED$");
  private static final Pattern JAVA_ERROR =
      Pattern.compile("([\\p{L}\\p{N}_$.-]+\\.java):([0-9]+): error: ([^\\r\\n]+)");

  /**
   * Converts process facts into conservative, actionable validation outcomes.
   *
   * @param result bounded untrusted process output and typed lifecycle facts
   * @return no learner blame without recognizable build evidence
   */
  public CheckOutcome classify(ProcessResult result) {
    Objects.requireNonNull(result);
    CheckOutcome outcome =
        switch (result) {
          case ProcessResult.Exited exited -> classifyExit(exited);
          case ProcessResult.TimedOut ignored ->
              failure(
                  FailureCategory.TIMEOUT,
                  "Validation to finish within its configured deadline.",
                  "The validation deadline expired.",
                  "Inspect the local build for blocked work, then run check again.");
          case ProcessResult.Interrupted ignored ->
              failure(
                  FailureCategory.INTERRUPTED,
                  "Validation to finish without cancellation.",
                  "Validation was interrupted.",
                  "Run check again when ready to continue.");
          case ProcessResult.Failed failed -> classifyFailure(failed.reason());
        };
    List<Diagnostic> diagnostics = new ArrayList<>(outcome.diagnostics());
    if (result.output().stdoutTruncated() || result.output().stderrTruncated()) {
      diagnostics.add(
          new Diagnostic(
              "Complete build output within the capture budget.",
              "Build output was truncated; only bounded stream tails were retained.",
              "Use check --verbose to inspect the retained output, or rerun the wrapper locally."));
    }
    switch (result) {
      case ProcessResult.TimedOut timeout -> addCleanup(diagnostics, timeout.cleanup());
      case ProcessResult.Interrupted interrupted -> addCleanup(diagnostics, interrupted.cleanup());
      default -> {}
    }
    return switch (outcome) {
      case CheckOutcome.Passed ignored -> new CheckOutcome.Passed(diagnostics);
      case CheckOutcome.Failed failed -> new CheckOutcome.Failed(failed.category(), diagnostics);
    };
  }

  private static CheckOutcome classifyExit(ProcessResult.Exited result) {
    if (result.exitCode() == 0) {
      return new CheckOutcome.Passed(
          List.of(
              new Diagnostic(
                  "Successful compilation and visible tests.",
                  "Gradle exited with code 0.",
                  "Continue with public-behavior validation.")));
    }
    List<String> lines =
        clean(result.output().stdout() + "\n" + result.output().stderr()).lines().toList();
    boolean compilation = lines.stream().anyMatch(line -> COMPILE_TASK.matcher(line).matches());
    boolean tests = lines.stream().anyMatch(line -> TEST_TASK.matcher(line).matches());
    Optional<String> sourceError =
        lines.stream()
            .map(GradleCheckClassifier::sourceError)
            .flatMap(Optional::stream)
            .findFirst();
    if (compilation && !tests && sourceError.isPresent()) {
      return failure(
          FailureCategory.COMPILATION_ERROR,
          "Java source and visible tests to compile.",
          sourceError.orElseThrow(),
          "Read that source location, fix the Java compilation error, then run check again.");
    }
    if (tests
        && !compilation
        && lines.stream().anyMatch(line -> JUNIT_FAILURE.matcher(line).matches())
        && lines.stream().anyMatch(line -> line.startsWith("> There were failing tests."))) {
      return failure(
          FailureCategory.TEST_FAILURE,
          "All visible JUnit tests to pass.",
          "Visible JUnit tests failed.",
          "Read the failing test and the local test report, compare expected and actual behavior, then run check again.");
    }
    if (!compilation && !tests && lines.stream().anyMatch(GradleCheckClassifier::missingWrapper)) {
      return failure(
          FailureCategory.MISSING_ARTIFACT,
          "The complete workspace Gradle wrapper.",
          "The Gradle wrapper script or launcher classes were unavailable.",
          "Inspect the disclosed wrapper files and restore missing course assets explicitly before running check again.");
    }
    return failure(
        FailureCategory.INTERNAL_ERROR,
        "A usable local Gradle wrapper and toolchain.",
        "Gradle failed with exit code "
            + result.exitCode()
            + " without recognizable learner-failure evidence.",
        "Inspect the local wrapper and Java toolchain, then use check --verbose for retained diagnostics.");
  }

  private static CheckOutcome classifyFailure(ProcessResult.FailureReason reason) {
    return switch (reason) {
      case INVALID_WORKING_DIRECTORY ->
          failure(
              FailureCategory.INTERNAL_ERROR,
              "An existing real workspace directory without symlink ancestors.",
              "The process working directory was unavailable or unsafe.",
              "Select a real workspace directory and run check again.");
      case UNAVAILABLE ->
          failure(
              FailureCategory.INTERNAL_ERROR,
              "A runnable local executable and Java toolchain.",
              "The validation process could not be started.",
              "Inspect the wrapper, executable permissions, and Java toolchain, then run check again.");
      case OUTPUT_FAILURE ->
          failure(
              FailureCategory.INTERNAL_ERROR,
              "Reliable process output.",
              "The validation process output could not be collected.",
              "Inspect the local toolchain and run check again with --verbose.");
      case CLEANUP_FAILURE ->
          failure(
              FailureCategory.INTERNAL_ERROR,
              "All owned processes and stream drains to terminate.",
              "Process cleanup could not be verified.",
              "Inspect remaining local processes before running check again.");
    };
  }

  private static Optional<String> sourceError(String line) {
    int marker = line.indexOf(".java:");
    if (marker < 0) {
      return Optional.empty();
    }
    int start = marker;
    while (start > 0 && marker - start < 256) {
      int character = line.codePointBefore(start);
      if (!Character.isLetterOrDigit(character)
          && character != '_'
          && character != '$'
          && character != '.'
          && character != '-') {
        break;
      }
      start -= Character.charCount(character);
    }
    Matcher matcher =
        JAVA_ERROR.matcher(line.substring(start, Math.min(line.length(), marker + 512)));
    if (!matcher.lookingAt()) {
      return Optional.empty();
    }
    String location =
        Objects.requireNonNull(matcher.group(1))
            + ":"
            + Objects.requireNonNull(matcher.group(2))
            + ": error: "
            + Objects.requireNonNull(matcher.group(3));
    return Optional.of(location.length() <= 400 ? location : location.substring(0, 397) + "...");
  }

  private static String clean(String output) {
    String withoutAnsi =
        ANSI.matcher(output).replaceAll("").replace("\r\n", "\n").replace('\r', '\n');
    StringBuilder safe = new StringBuilder(withoutAnsi.length());
    withoutAnsi
        .codePoints()
        .filter(character -> !Character.isISOControl(character) || character == '\n')
        .forEach(safe::appendCodePoint);
    return safe.toString();
  }

  private static boolean missingWrapper(String line) {
    return line.contains("cannot open ./gradlew: No such file")
        || line.equals("'gradlew.bat' is not recognized as an internal or external command,")
        || line.equals(
            "Error: Could not find or load main class org.gradle.wrapper.GradleWrapperMain");
  }

  private static void addCleanup(List<Diagnostic> diagnostics, ProcessResult.Cleanup cleanup) {
    if (cleanup != ProcessResult.Cleanup.COMPLETE) {
      diagnostics.add(
          new Diagnostic(
              "All owned processes and stream drains to terminate.",
              cleanup == ProcessResult.Cleanup.UNSUPPORTED
                  ? "The platform could not verify descendant cleanup."
                  : "Owned process or stream cleanup remained incomplete after its deadline.",
              "Inspect remaining local processes before running check again."));
    }
  }

  private static CheckOutcome.Failed failure(
      FailureCategory category, String expected, String observed, String nextAction) {
    return new CheckOutcome.Failed(
        category, List.of(new Diagnostic(expected, observed, nextAction)));
  }
}
