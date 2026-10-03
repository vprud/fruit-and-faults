package org.fruitandfaults.validation.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.stream.Stream;

import org.fruitandfaults.validation.application.ProcessResult;
import org.fruitandfaults.validation.domain.CheckOutcome;
import org.fruitandfaults.validation.domain.Diagnostic;
import org.fruitandfaults.validation.domain.FailureCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GradleCheckClassifierTest {
  private final GradleCheckClassifier classifier = new GradleCheckClassifier();

  @ParameterizedTest
  @MethodSource("failures")
  void classifiesOnlyRecognizableTaskFailuresAndProvidesUsefulNextAction(
      String fixture, FailureCategory category, String observed, String action) throws IOException {
    ProcessResult result = new ProcessResult.Exited(1, output("", fixture(fixture)));
    CheckOutcome.Failed outcome =
        assertInstanceOf(CheckOutcome.Failed.class, classifier.classify(result));

    assertEquals(category, outcome.category());
    Diagnostic diagnostic = outcome.diagnostics().getFirst();
    assertFalse(diagnostic.expected().isBlank());
    assertTrue(diagnostic.observed().contains(observed), diagnostic.observed());
    assertTrue(diagnostic.nextAction().contains(action), diagnostic.nextAction());
    assertFalse(diagnostic.observed().contains("/workspace"));
    assertFalse(diagnostic.observed().contains("SECRET_TOKEN"));
    assertFalse(diagnostic.observed().contains("Caused by:"));
  }

  private static Stream<Arguments> failures() {
    return Stream.of(
        Arguments.of(
            "compile.txt", FailureCategory.COMPILATION_ERROR, "Starter.java:7", "source location"),
        Arguments.of(
            "compile-tests.txt",
            FailureCategory.COMPILATION_ERROR,
            "MovementTest.java:12",
            "source location"),
        Arguments.of(
            "test-failure.txt",
            FailureCategory.TEST_FAILURE,
            "Visible JUnit tests failed",
            "test report"),
        Arguments.of(
            "configuration.txt", FailureCategory.INTERNAL_ERROR, "exit code 1", "toolchain"),
        Arguments.of(
            "missing-wrapper.txt",
            FailureCategory.MISSING_ARTIFACT,
            "Gradle wrapper",
            "wrapper files"),
        Arguments.of(
            "missing-wrapper-windows.txt",
            FailureCategory.MISSING_ARTIFACT,
            "Gradle wrapper",
            "wrapper files"),
        Arguments.of(
            "missing-wrapper-jar.txt",
            FailureCategory.MISSING_ARTIFACT,
            "Gradle wrapper",
            "wrapper files"),
        Arguments.of("unknown.txt", FailureCategory.INTERNAL_ERROR, "exit code 1", "toolchain"));
  }

  @Test
  void zeroExitIsSuccessfulEvenWhenOutputContainsDiagnosticWords() throws IOException {
    CheckOutcome.Passed outcome =
        assertInstanceOf(
            CheckOutcome.Passed.class,
            classifier.classify(new ProcessResult.Exited(0, output(fixture("compile.txt"), ""))));
    assertFalse(outcome.diagnostics().isEmpty());
  }

  @Test
  void missingTaskOrErrorEvidenceDoesNotBlameLearnerSource() {
    for (String incomplete :
        new String[] {
          "Starter.java:7: error: cannot find symbol\n",
          "> Task :compileJava FAILED\nPlugin cannot be loaded\n",
          "> Task :test FAILED\nTest worker could not start\n",
          "There were failing tests.\n",
          "> Task :test FAILED\nGradle Test Executor 1 > failed to execute tests FAILED\n> There were failing tests.\n",
          "> Task :compileJava FAILED\nStarter.java:7: error: cannot find symbol\n> Task :test FAILED\nThere were failing tests.\n"
        }) {
      CheckOutcome.Failed outcome =
          assertInstanceOf(
              CheckOutcome.Failed.class,
              classifier.classify(new ProcessResult.Exited(7, output(incomplete, ""))));
      assertEquals(FailureCategory.INTERNAL_ERROR, outcome.category());
    }
  }

  @Test
  void longUntrustedLineWithoutSourceEvidenceIsClassifiedWithinBoundedTime() {
    String captured = "> Task :compileJava FAILED\n" + "x".repeat(100_000) + "\n";
    CheckOutcome.Failed outcome =
        assertInstanceOf(
            CheckOutcome.Failed.class,
            assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> classifier.classify(new ProcessResult.Exited(1, output(captured, "")))));
    assertEquals(FailureCategory.INTERNAL_ERROR, outcome.category());
  }

  @Test
  void sanitizesTerminalControlsAndPreservesUsefulRelativeSourceLocation() throws IOException {
    String captured = "\u001b[31m" + fixture("compile.txt") + "\u001b[0m";
    captured = captured.replace("cannot find symbol", "cannot\u0007 find symbol");
    CheckOutcome.Failed outcome =
        assertInstanceOf(
            CheckOutcome.Failed.class,
            classifier.classify(new ProcessResult.Exited(1, output(captured, ""))));
    assertEquals(FailureCategory.COMPILATION_ERROR, outcome.category());
    assertTrue(
        outcome
            .diagnostics()
            .getFirst()
            .observed()
            .contains("Starter.java:7: error: cannot find symbol"));
    assertFalse(outcome.diagnostics().toString().contains("\u001b"));
    assertFalse(outcome.diagnostics().toString().contains("\u0007"));
  }

  @Test
  void explainsRetainedTailWhenOutputIsTruncated() {
    ProcessResult.Output output = new ProcessResult.Output("tail", "", true, false);
    CheckOutcome.Failed outcome =
        assertInstanceOf(
            CheckOutcome.Failed.class, classifier.classify(new ProcessResult.Exited(1, output)));
    assertEquals(FailureCategory.INTERNAL_ERROR, outcome.category());
    assertTrue(
        outcome.diagnostics().stream()
            .anyMatch(diagnostic -> diagnostic.observed().contains("truncated")));
  }

  @Test
  void distinguishesTimeoutInterruptionAndLaunchFailureFromLearnerFailures() {
    assertCategory(
        FailureCategory.TIMEOUT,
        new ProcessResult.TimedOut(ProcessResult.Output.empty(), ProcessResult.Cleanup.COMPLETE));
    assertCategory(
        FailureCategory.INTERRUPTED,
        new ProcessResult.Interrupted(
            ProcessResult.Output.empty(), ProcessResult.Cleanup.COMPLETE));
    for (ProcessResult.FailureReason reason : ProcessResult.FailureReason.values()) {
      CheckOutcome.Failed outcome =
          assertCategory(
              FailureCategory.INTERNAL_ERROR,
              new ProcessResult.Failed(
                  reason, "SECRET_TOKEN=untrusted\u001b", ProcessResult.Output.empty()));
      assertFalse(outcome.diagnostics().toString().contains("SECRET_TOKEN"));
    }
  }

  @Test
  void reportsIncompleteAndUnsupportedCleanupInsteadOfImplyingTreeStopped() {
    for (ProcessResult.Cleanup cleanup :
        new ProcessResult.Cleanup[] {
          ProcessResult.Cleanup.INCOMPLETE, ProcessResult.Cleanup.UNSUPPORTED
        }) {
      CheckOutcome.Failed outcome =
          assertCategory(
              FailureCategory.TIMEOUT,
              new ProcessResult.TimedOut(ProcessResult.Output.empty(), cleanup));
      assertTrue(
          outcome.diagnostics().stream()
              .anyMatch(
                  diagnostic -> diagnostic.nextAction().contains("remaining local processes")));
    }
  }

  private CheckOutcome.Failed assertCategory(FailureCategory category, ProcessResult result) {
    CheckOutcome.Failed outcome =
        assertInstanceOf(CheckOutcome.Failed.class, classifier.classify(result));
    assertEquals(category, outcome.category());
    assertFalse(outcome.diagnostics().getFirst().expected().isBlank());
    assertFalse(outcome.diagnostics().getFirst().observed().isBlank());
    assertFalse(outcome.diagnostics().getFirst().nextAction().isBlank());
    return outcome;
  }

  private static ProcessResult.Output output(String stdout, String stderr) {
    return new ProcessResult.Output(stdout, stderr, false, false);
  }

  private static String fixture(String name) throws IOException {
    try (var input =
        Objects.requireNonNull(
            GradleCheckClassifierTest.class.getResourceAsStream("/process-fixtures/" + name))) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
