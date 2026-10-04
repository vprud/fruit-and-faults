package org.fruitandfaults.journey;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.fruitandfaults.cli.JourneyApplications;
import org.fruitandfaults.progress.infra.AtomicProgressRepository;
import org.fruitandfaults.progress.infra.JacksonProgressCodec;
import org.fruitandfaults.validation.application.CheckLesson;
import org.fruitandfaults.validation.application.ProcessResult;
import org.fruitandfaults.validation.application.ProcessRunner;
import org.fruitandfaults.validation.infra.BoundedProcessRunner;
import org.fruitandfaults.validation.infra.CachedGradlePreflight;
import org.fruitandfaults.validation.infra.CompiledGameLoader;
import org.fruitandfaults.validation.infra.FirstRunValidator;
import org.fruitandfaults.validation.infra.GradleCheckClassifier;
import org.fruitandfaults.validation.infra.ManifestArtifactInspector;
import org.fruitandfaults.workspace.application.DiscloseLesson;
import org.fruitandfaults.workspace.infra.JacksonManagedFilesRepository;
import org.fruitandfaults.workspace.infra.JacksonTransitionJournalRepository;
import org.fruitandfaults.workspace.infra.SafeWorkspaceFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ResourceLock("java.lang.System.properties")
class PhaseAErrorJourneyTest {
  @TempDir private Path temporary;

  @Test
  void visibleAssertionFailureDiffersFromCompilationAndPreservesProgress() throws Exception {
    var fixture = new PhaseAJourneyFixture(temporary);
    fixture.start();
    fixture.solve(0);
    Path source = fixture.root().resolve(PhaseAJourneyFixture.MAIN + "Starter.java");
    Files.writeString(
        source, Files.readString(source).replace("Ready to play.", "Wrong public result."));
    byte[] before = fixture.progressBytes();
    var failure = fixture.run("check");
    fixture.assertSafeFailure(failure, 4);
    assertTrue(failure.err().contains("Visible JUnit tests failed"), failure.err());
    assertFalse(failure.err().contains("compilation failed"));
    assertArrayEquals(before, fixture.progressBytes());
    assertTrue(
        Files.exists(
            fixture
                .root()
                .resolve("build/test-results/test/TEST-org.fruitandfaults.game.StarterTest.xml")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"deleted-check", "modified-check", "missing-source"})
  void requiredArtifactFailuresStopBeforeTheWrapperAndNeverRestoreLearnerFiles(String error)
      throws Exception {
    var fixture = new PhaseAJourneyFixture(temporary);
    assertEquals(
        0, fixture.run(fixture.parent(), "start", fixture.root().toString(), "--yes").code());
    Path path =
        fixture
            .root()
            .resolve(
                error.equals("missing-source")
                    ? PhaseAJourneyFixture.MAIN + "Starter.java"
                    : PhaseAJourneyFixture.TEST + "StarterTest.java");
    if (error.equals("modified-check")) Files.writeString(path, "SECRET_JOURNEY changed check");
    else Files.delete(path);
    byte[] before = fixture.progressBytes();
    var failed = fixture.run("check");
    fixture.assertSafeFailure(failed, error.equals("modified-check") ? 3 : 1);
    assertTrue(
        failed
            .err()
            .contains(
                error.equals("modified-check")
                    ? "immutable visible check was modified"
                    : "artifact is missing"));
    assertFalse(Files.exists(fixture.root().resolve("build")));
    assertArrayEquals(before, fixture.progressBytes());
    if (error.equals("modified-check"))
      assertEquals("SECRET_JOURNEY changed check", Files.readString(path));
    else assertFalse(Files.exists(path));
  }

  @Test
  void whitespaceOnlyTemplateEditIsReportedHonestlyAndStillFailsTheVisibleExercise()
      throws Exception {
    var fixture = new PhaseAJourneyFixture(temporary);
    fixture.start();
    var states = new AtomicProgressRepository(new JacksonProgressCodec(fixture.catalog()));
    var manifests = new JacksonManagedFilesRepository();
    var disclosure =
        new DiscloseLesson(
            fixture.catalog(),
            new SafeWorkspaceFiles(),
            manifests,
            states,
            new JacksonTransitionJournalRepository(new JacksonProgressCodec(fixture.catalog())));
    for (int index = 0; index < 2; index++) {
      fixture.solve(index);
      String head = fixture.commit("feat(game): prepare earlier fixture lesson");
      var before = fixture.progress();
      disclosure.apply(
          fixture.root(),
          fixture.catalog().load().lessons().get(index + 1),
          manifests.load(fixture.root()),
          Optional.of(before),
          before
              .advance(
                  before.activeLessonId().orElseThrow(),
                  fixture.catalog().load().lessons().get(index).question().correctOptionId(),
                  head)
              .progress());
    }
    Path template =
        fixture.root().resolve(PhaseAJourneyFixture.TEST + "AnalogousBoundaryTest.java");
    byte[] unchanged = Files.readAllBytes(template);
    fixture.solve(2);
    Files.write(template, unchanged);
    Files.writeString(template, Files.readString(template) + "\n   \n");
    byte[] before = fixture.progressBytes();
    var failure = fixture.run("check");
    fixture.assertSafeFailure(failure, 4);
    assertTrue(failure.err().contains("Only whitespace differs"));
    assertTrue(failure.err().contains("without evidence of test work"));
    assertTrue(failure.err().contains("Visible JUnit tests failed"));
    assertArrayEquals(before, fixture.progressBytes());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"missing-commit", "dirty-tracked", "dirty-untracked", "future-file-conflict"})
  void localGitGatesAndDisclosureConflictsPreserveWorkAndDoNotPersistReflection(String error)
      throws Exception {
    var fixture = new PhaseAJourneyFixture(temporary);
    fixture.start();
    fixture.solve(0);
    if (!error.equals("missing-commit")) fixture.commit("fix(game): completed starter");
    Path future = fixture.root().resolve(PhaseAJourneyFixture.MAIN + "Direction.java");
    if (error.equals("dirty-tracked"))
      Files.writeString(
          fixture.root().resolve(PhaseAJourneyFixture.MAIN + "Starter.java"),
          Files.readString(fixture.root().resolve(PhaseAJourneyFixture.MAIN + "Starter.java"))
              + "\n");
    if (error.equals("dirty-untracked"))
      Files.writeString(fixture.root().resolve("notes.txt"), "Learner notes to preserve.");
    if (error.equals("future-file-conflict")) {
      Files.writeString(future, "// Learner's independently committed future file.\n");
      fixture.commit("chore(game): save learner-owned future file");
    }
    String head = error.equals("missing-commit") ? "" : fixture.git("rev-parse", "HEAD");
    String gitState = fixture.git("status", "--porcelain=v1");
    byte[] before = fixture.progressBytes();
    var rejected = fixture.run("next", "--answer", "compile-before-tests", "--yes");
    fixture.assertSafeFailure(rejected, error.equals("future-file-conflict") ? 3 : 1);
    assertArrayEquals(before, fixture.progressBytes());
    assertTrue(fixture.progress().lessons().getFirst().completedOptionId().isEmpty());
    assertEquals(gitState, fixture.git("status", "--porcelain=v1"));
    if (!head.isEmpty()) assertEquals(head, fixture.git("rev-parse", "HEAD"));
    assertFalse(Files.exists(fixture.root().resolve(".fruit-and-faults/transition.json")));
    if (error.equals("future-file-conflict"))
      assertEquals("// Learner's independently committed future file.\n", Files.readString(future));
    else assertFalse(Files.exists(future));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"{ SECRET_JOURNEY", "{\"formatVersion\":2,\"secret\":\"SECRET_JOURNEY\"}"})
  void malformedAndFutureProgressRemainUntouchedAcrossEveryCommandIncludingAnswerlessNext(
      String content) throws Exception {
    var fixture = new PhaseAJourneyFixture(temporary);
    assertEquals(
        0, fixture.run(fixture.parent(), "start", fixture.root().toString(), "--yes").code());
    Path progress = fixture.root().resolve(".fruit-and-faults/progress.json");
    Files.writeString(progress, content);
    byte[] before = Files.readAllBytes(progress);
    for (String command : new String[] {"status", "check", "hint", "list"}) {
      fixture.assertSafeFailure(fixture.run(command), 3);
      assertArrayEquals(before, Files.readAllBytes(progress));
    }
    fixture.assertSafeFailure(fixture.run("next", "--yes"), 3);
    fixture.assertSafeFailure(fixture.run("next", "--answer", "compile-before-tests", "--yes"), 3);
    fixture.assertSafeFailure(fixture.run(fixture.parent(), "start", fixture.root().toString()), 3);
    assertArrayEquals(before, Files.readAllBytes(progress));
    assertFalse(Files.exists(fixture.root().resolve(".fruit-and-faults/transition.json")));
  }

  @Test
  void answerlessActiveNextRejectsUsageWithoutValidationOrMutation() throws Exception {
    var fixture = new PhaseAJourneyFixture(temporary);
    assertEquals(
        0, fixture.run(fixture.parent(), "start", fixture.root().toString(), "--yes").code());
    byte[] before = fixture.progressBytes();
    fixture.assertSafeFailure(fixture.run("next", "--yes"), 2);
    assertArrayEquals(before, fixture.progressBytes());
    assertFalse(Files.exists(fixture.root().resolve("build")));
    assertFalse(Files.exists(fixture.root().resolve(".fruit-and-faults/transition.json")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{ SECRET_JOURNEY", "{\"formatVersion\":2}"})
  void answerlessNextRejectsMalformedOrFutureJournalWithoutMutation(String content)
      throws Exception {
    var fixture = new PhaseAJourneyFixture(temporary);
    assertEquals(
        0, fixture.run(fixture.parent(), "start", fixture.root().toString(), "--yes").code());
    Path journal = fixture.root().resolve(".fruit-and-faults/transition.json");
    Path manifest = fixture.root().resolve(".fruit-and-faults/managed-files.json");
    Files.writeString(journal, content);
    byte[] pending = Files.readAllBytes(journal);
    byte[] progress = fixture.progressBytes();
    byte[] owned = Files.readAllBytes(manifest);
    fixture.assertSafeFailure(fixture.run("next", "--yes"), 3);
    assertArrayEquals(pending, Files.readAllBytes(journal));
    assertArrayEquals(progress, fixture.progressBytes());
    assertArrayEquals(owned, Files.readAllBytes(manifest));
    assertFalse(Files.exists(fixture.root().resolve("build")));
    assertFalse(Files.exists(fixture.root().resolve(PhaseAJourneyFixture.MAIN + "Direction.java")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"timeout", "interruption", "internal-validator"})
  void deterministicExternalFailuresRetainRealCompositionAndSafeCategories(String failure)
      throws Exception {
    var fixture = new PhaseAJourneyFixture(temporary);
    fixture.start();
    fixture.solve(0);
    var actual = new BoundedProcessRunner();
    ProcessRunner build =
        failure.equals("internal-validator")
            ? actual
            : request -> {
              assertTrue(request.arguments().contains("test"));
              assertTrue(request.arguments().contains("--offline"));
              assertTrue(
                  request
                      .arguments()
                      .contains(fixture.root().resolve(".gradle/fixture-user-home").toString()));
              if (failure.equals("interruption")) {
                Thread.currentThread().interrupt();
                return new ProcessResult.Interrupted(
                    ProcessResult.Output.empty(), ProcessResult.Cleanup.COMPLETE);
              }
              return new ProcessResult.TimedOut(
                  ProcessResult.Output.empty(), ProcessResult.Cleanup.COMPLETE);
            };
    var validator = new FirstRunValidator(new CompiledGameLoader(actual));
    var checks =
        new CheckLesson(
            new ManifestArtifactInspector(actual, fixture.catalog()),
            new CachedGradlePreflight(
                actual,
                fixture.root().resolve(".gradle/fixture-user-home").toString(),
                null,
                fixture.parent().toString()),
            build,
            new GradleCheckClassifier()::classify,
            Map.of(
                "starter-public-result",
                failure.equals("internal-validator")
                    ? root -> {
                      throw new IllegalStateException("SECRET_JOURNEY " + root);
                    }
                    : validator),
            System.getProperty("os.name").startsWith("Windows")
                ? CheckLesson.WrapperPlatform.WINDOWS
                : CheckLesson.WrapperPlatform.UNIX);
    byte[] before = fixture.progressBytes();
    try {
      // Only deadline/cancellation facts or an internal adapter exception are injected;
      // inspecting artifacts/cache, visible tests for the internal case, and state/Git remain real.
      var failed =
          fixture.run(
              fixture.root(),
              () ->
                  JourneyApplications.compose(
                      fixture.catalog(), fixture.catalog(), checks::execute),
              "check");
      fixture.assertSafeFailure(failed, failure.equals("internal-validator") ? 10 : 5);
      assertEquals(failure.equals("interruption"), Thread.currentThread().isInterrupted());
      assertArrayEquals(before, fixture.progressBytes());
    } finally {
      Thread.interrupted();
    }
  }
}
