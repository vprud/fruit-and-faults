package org.fruitandfaults.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;

import org.fruitandfaults.course.application.AdvanceResult;
import org.fruitandfaults.course.application.CourseStatus;
import org.fruitandfaults.course.application.HintResult;
import org.fruitandfaults.course.domain.Course;
import org.fruitandfaults.course.domain.Lesson;
import org.fruitandfaults.course.domain.ReflectionOption;
import org.fruitandfaults.course.domain.ReflectionQuestion;
import org.fruitandfaults.course.infra.ClasspathCourseCatalog;
import org.fruitandfaults.git.application.GitInitializationException;
import org.fruitandfaults.git.application.GitRepository;
import org.fruitandfaults.git.application.GitStatus;
import org.fruitandfaults.git.infra.ProcessGitRepository;
import org.fruitandfaults.validation.application.CheckRequest;
import org.fruitandfaults.validation.domain.CheckOutcome;
import org.fruitandfaults.validation.domain.Diagnostic;
import org.fruitandfaults.validation.domain.FailureCategory;
import org.fruitandfaults.workspace.application.StartRequest;
import org.fruitandfaults.workspace.application.StartResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CommandJourneyTest {
  @TempDir private Path temporary;

  @Test
  void installedCommandsStartResumeDiscoverNestedWorkspaceAndNeverReadRedirectedInput()
      throws IOException {
    Path parent = temporary.toRealPath();
    Path root = parent.resolve("Моя игра with spaces");
    Supplier<ApplicationFactory.Application> factory = ApplicationFactory::create;
    var preview = run(factory, parent, false, "", "start", root.toString());
    assertEquals(2, preview.code());
    assertTrue(preview.out().contains(".fruit-and-faults/progress.json"));
    assertTrue(preview.err().contains("--yes"));
    assertFalse(Files.exists(root));
    var created = run(factory, parent, false, "", "--no-color", "start", root.toString(), "--yes");
    assertEquals(0, created.code());
    assertTrue(created.out().contains("First Run and Diagnostics"));
    assertTrue(created.out().contains("Next:             fruit-and-faults lesson"));
    assertTrue(created.out().contains("Suggested commit: fix: repair starter compilation"));
    assertTrue(created.out().contains("docs/publishing-to-github.md"));
    assertEquals("", created.err());
    byte[] state = Files.readAllBytes(root.resolve(".fruit-and-faults/progress.json"));
    assertEquals(0, run(factory, parent, false, "", "start", root.toString()).code());
    assertArrayEquals(state, Files.readAllBytes(root.resolve(".fruit-and-faults/progress.json")));
    Path nested = root.resolve("src/main/java");
    var status = run(factory, nested, false, "", "status");
    var lesson = run(factory, nested, false, "", "lesson");
    assertEquals(0, lesson.code());
    assertTrue(lesson.out().contains("Compilation must succeed before tests can execute."));
    assertTrue(lesson.out().contains("Suggested commit: fix: repair starter compilation"));
    assertFalse(lesson.out().matches("(?s).*[А-Яа-яЁё].*"));
    assertArrayEquals(state, Files.readAllBytes(root.resolve(".fruit-and-faults/progress.json")));
    assertEquals(0, status.code());
    assertEquals(1, status.out().split("Next command:", -1).length - 1);
    assertTrue(status.out().contains("fruit-and-faults check"));
    assertFalse(status.out().contains(root.toString()));
    assertFalse(status.out().contains("\u001b"));
    assertEquals(0, run(factory, nested, false, "", "list").code());
    assertEquals(0, run(factory, nested, false, "", "hint").code());
  }

  @Test
  void lessonRejectsMalformedProgressWithoutChangingIt() throws IOException {
    var fixture = new Fixture(temporary.toRealPath());
    assertEquals(0, fixture.run("start", fixture.root.toString(), "--yes").code());
    Path progress = fixture.root.resolve(".fruit-and-faults/progress.json");
    Files.writeString(progress, "{SECRET}");

    var result = fixture.run("lesson");

    assertEquals(3, result.code());
    assertEquals("", result.out());
    assertTrue(
        result.err().contains("Observed: Saved progress is missing, malformed, or incompatible."));
    assertFalse(result.err().contains("SECRET"));
    assertEquals("{SECRET}", Files.readString(progress));
  }

  @ParameterizedTest
  @ValueSource(strings = {"да", "д"})
  void englishConfirmationPromptStillAcceptsLegacyRussianYesInput(String answer)
      throws IOException {
    Path parent = temporary.toRealPath();
    Path root = parent.resolve("legacy-confirmation");

    var result =
        run(ApplicationFactory::create, parent, true, answer + "\n", "start", root.toString());

    assertEquals(0, result.code(), result.err());
    assertTrue(result.out().contains("Create workspace? [yes/no]: "));
    assertTrue(result.out().contains("Workspace created."));
  }

  @Test
  void completeInjectedJourneyPreservesWorkAtFailedGatesAndFinishesAllFourLessons()
      throws IOException {
    var fixture = new Fixture(temporary.toRealPath());
    assertEquals(0, fixture.run("start", fixture.root.toString(), "--yes").code());
    fixture.outcome = failure(FailureCategory.COMPILATION_ERROR);
    assertEquals(4, fixture.run("check").code());
    assertEquals(4, fixture.run("next", "--answer", "compile-before-tests", "--yes").code());
    fixture.outcome = new CheckOutcome.Passed(List.of());
    assertEquals(0, fixture.run("check").code());
    for (int level = 1; level <= 3; level++) {
      assertTrue(fixture.run("hint").out().contains("Hint " + level + "/3"));
    }
    byte[] hints = fixture.state();
    assertTrue(fixture.run("hint").out().contains("Hint 3/3"));
    assertArrayEquals(hints, fixture.state());
    var incorrect = fixture.run("next", "--answer", "assertion-first", "--yes");
    assertEquals(1, incorrect.code());
    assertTrue(incorrect.err().contains("An assertion executes compiled code"));
    assertArrayEquals(hints, fixture.state());
    fixture.git.observed = new GitStatus(Optional.of("a".repeat(40)), 1, 0, false, false);
    var dirty = fixture.run("next", "--answer", "compile-before-tests", "--yes");
    assertEquals(1, dirty.code());
    assertTrue(dirty.err().contains("git status"));
    assertArrayEquals(hints, fixture.state());
    for (int index = 0; index < 4; index++) {
      fixture.git.observed =
          new GitStatus(
              Optional.of(Character.toString('a' + index).repeat(40)), 0, 0, false, false);
      String answer = fixture.catalog.load().lessons().get(index).question().correctOptionId();
      var advanced = fixture.run("next", "--answer", answer, "--yes");
      assertEquals(0, advanced.code(), advanced.err());
      assertTrue(advanced.out().contains("Preview:"));
      if (index < 3) assertTrue(advanced.out().contains("LESSON ·"));
      else assertTrue(advanced.out().contains("Course complete"));
    }
    byte[] complete = fixture.state();
    assertEquals(0, fixture.run("next", "--answer", "moves-only", "--yes").code());
    assertArrayEquals(complete, fixture.state());
    var route = fixture.run("list");
    assertEquals(4, route.out().split("completed", -1).length - 1);
    assertEquals("Course complete. No lesson is active.\n", fixture.run("lesson").out());
    assertEquals(0, fixture.run("hint").code());
    assertTrue(fixture.run("check").out().contains("No active lesson; checks were not run"));
  }

  @Test
  void interactiveWrongNumberedAnswerOffersRetryAndRequiresDisclosureConfirmation()
      throws IOException {
    var fixture = new Fixture(temporary.toRealPath());
    fixture.run("start", fixture.root.toString(), "--yes");
    var options = fixture.catalog.load().lessons().getFirst().question().options();
    int correct = 0;
    int wrong = 0;
    for (int index = 0; index < options.size(); index++) {
      if (options.get(index).id().equals("compile-before-tests")) correct = index + 1;
      else wrong = index + 1;
    }
    var result =
        run(
            () -> fixture.application,
            fixture.root,
            true,
            wrong + "\nyes\n" + correct + "\nyes\n",
            "next",
            "--no-color");
    assertEquals(0, result.code(), result.err());
    assertTrue(result.out().contains("Try again? [yes/no]: "));
    assertTrue(result.out().contains("Apply changes? [yes/no]: "));
    assertTrue(result.out().contains("  1. "));
    assertFalse(result.out().contains("\u001b"));
  }

  @Test
  void malformedNumberCannotAccidentallySelectALegitimateStableOptionId() throws IOException {
    var catalog = new ClasspathCourseCatalog("course");
    var original = catalog.load();
    var first = original.lessons().getFirst();
    var question =
        new ReflectionQuestion(
            first.question().id(),
            first.question().prompt(),
            List.of(
                new ReflectionOption("invalid-selection", "Верный вариант", "Верно"),
                new ReflectionOption("other", "Другой вариант", "Подумайте ещё")),
            "invalid-selection");
    var changed =
        new Lesson(
            first.id(),
            first.title(),
            first.goal(),
            first.prerequisites(),
            first.assets(),
            first.expectedArtifacts(),
            first.completionCriteria(),
            first.instructions(),
            first.hints(),
            question,
            first.recommendedCommitMessage());
    var lessons = new java.util.ArrayList<>(original.lessons());
    lessons.set(0, changed);
    var course =
        new Course(
            original.id(),
            original.contentVersion(),
            original.title(),
            original.lessonOrder(),
            lessons);
    var application =
        ApplicationFactory.compose(
            () -> course,
            catalog,
            ignored -> new CheckOutcome.Passed(List.of()),
            new ControlledGit());
    Path root = temporary.toRealPath().resolve("learner");
    assertEquals(
        0,
        run(() -> application, temporary.toRealPath(), false, "", "start", root.toString(), "--yes")
            .code());
    byte[] before = Files.readAllBytes(root.resolve(".fruit-and-faults/progress.json"));
    var result = run(() -> application, root, true, "malformed\nнет\n", "next", "--no-color");
    assertEquals(1, result.code());
    assertTrue(result.out().contains("Try again? [yes/no]: "));
    assertFalse(result.out().contains("Preview:"));
    assertArrayEquals(before, Files.readAllBytes(root.resolve(".fruit-and-faults/progress.json")));
  }

  @Test
  void decliningOrEndingInteractiveConfirmationLeavesProgressAndAssetsUntouched()
      throws IOException {
    var fixture = new Fixture(temporary.toRealPath());
    fixture.run("start", fixture.root.toString(), "--yes");
    byte[] before = fixture.state();
    var declined =
        run(
            () -> fixture.application,
            fixture.root,
            true,
            "нет\n",
            "next",
            "--answer",
            "compile-before-tests");
    assertEquals(1, declined.code());
    assertArrayEquals(before, fixture.state());
    var eof = run(() -> fixture.application, fixture.root, true, "", "next");
    assertEquals(1, eof.code());
    assertArrayEquals(before, fixture.state());
    assertFalse(
        Files.exists(
            fixture.root.resolve("src/main/java/org/fruitandfaults/game/Coordinate.java")));
  }

  @Test
  void noninteractiveNextRejectsMissingConfirmationBeforeCompositionOrValidation() {
    Supplier<ApplicationFactory.Application> forbidden =
        () -> {
          throw new AssertionError("Missing flags must be rejected before validation");
        };
    for (String[] args :
        List.of(new String[] {"next"}, new String[] {"next", "--answer", "compile-before-tests"})) {
      var result = run(forbidden, temporary, false, "", args);
      assertEquals(2, result.code());
      assertTrue(result.err().contains("--answer"));
      assertTrue(result.err().contains("--yes"));
    }
  }

  @Test
  void workspaceDiscoveryAndMalformedStateUseSafeStableFailures() throws IOException {
    Path parent = temporary.toRealPath();
    var outside = run(ApplicationFactory::create, parent, false, "", "status");
    assertEquals(2, outside.code());
    var fixture = new Fixture(parent);
    fixture.run("start", fixture.root.toString(), "--yes");
    Files.writeString(
        fixture.root.resolve(".fruit-and-faults/progress.json"),
        "not-json PRIVATE",
        StandardCharsets.UTF_8);
    byte[] malformed = fixture.state();
    for (String command : List.of("status", "check", "hint", "list")) {
      var result = fixture.run(command);
      assertEquals(3, result.code());
      assertFalse(result.err().contains("PRIVATE"));
      assertFalse(result.err().contains(fixture.root.toString()));
      assertArrayEquals(malformed, fixture.state());
    }
  }

  @Test
  void internalCauseIsHiddenNormallyAndSanitizedWhenVerbose() {
    Supplier<ApplicationFactory.Application> unavailable =
        () -> {
          throw new IllegalStateException("password=PRIVATE /Users/private/path");
        };
    assertEquals(10, run(unavailable, temporary, false, "", "list").code());
    var verbose = run(unavailable, temporary, false, "", "list", "--verbose");
    assertEquals(10, verbose.code());
    assertTrue(verbose.err().contains("IllegalStateException"));
    assertFalse(verbose.err().contains("PRIVATE"));
    assertFalse(verbose.err().contains("/Users"));
    var invalidBundle =
        run(
            () -> {
              throw new IllegalArgumentException("PRIVATE missing installed asset");
            },
            temporary,
            false,
            "",
            "list");
    assertEquals(10, invalidBundle.code());
    assertFalse(invalidBundle.err().contains("PRIVATE"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "start",
        "status",
        "check",
        "check-interrupted",
        "hint",
        "next",
        "list",
        "locator"
      })
  void cancellationAtCommandBoundaryIsNeverRenderedAsLearnerOrUnsafeFailure(String command)
      throws IOException {
    var fixture = new Fixture(temporary.toRealPath());
    fixture.run("start", fixture.root.toString(), "--yes");
    byte[] before = fixture.state();
    var diagnostic = new Diagnostic("Valid state", "Unsafe workspace", "Inspect unsafe paths");
    var original = fixture.application;
    var interrupted =
        new ApplicationFactory.Application(
            current -> {
              if (command.equals("locator")) {
                throw new IOException(new java.io.InterruptedIOException("PRIVATE"));
              }
              return original.locator().locate(current);
            },
            request -> {
              Thread.currentThread().interrupt();
              return new StartResult.Conflict(request.target(), "unsafe", List.of());
            },
            root -> {
              Thread.currentThread().interrupt();
              return new CourseStatus.Unavailable(FailureCategory.WORKSPACE_CONFLICT, diagnostic);
            },
            root -> {
              Thread.currentThread().interrupt();
              return failure(
                  command.equals("check-interrupted")
                      ? FailureCategory.INTERRUPTED
                      : FailureCategory.INCOMPLETE_WORK);
            },
            root -> {
              Thread.currentThread().interrupt();
              return new HintResult.Unavailable(FailureCategory.WORKSPACE_CONFLICT, diagnostic);
            },
            original.lesson(),
            request -> {
              Thread.currentThread().interrupt();
              return new AdvanceResult.Incorrect("unsafe");
            },
            root -> {
              throw new java.nio.channels.ClosedByInterruptException();
            });
    String[] args =
        switch (command) {
          case "start" -> new String[] {"start", fixture.root.toString(), "--yes"};
          case "next" -> new String[] {"next", "--answer", "compile-before-tests", "--yes"};
          case "locator" -> new String[] {"status"};
          case "check-interrupted" -> new String[] {"check"};
          default -> new String[] {command};
        };
    try {
      var result = run(() -> interrupted, fixture.root, command.equals("next"), "", args);
      assertEquals(5, result.code());
      assertEquals("", result.out());
      assertTrue(result.err().contains("interrupted"));
      assertTrue(result.err().contains("retry"));
      assertFalse(result.err().contains("unsafe"));
      assertFalse(result.err().contains("unsafe workspace"));
      assertFalse(result.err().contains("PRIVATE"));
      assertTrue(Thread.currentThread().isInterrupted());
      Thread.interrupted();
      assertArrayEquals(before, fixture.state());
      assertFalse(Files.exists(fixture.root.resolve(".fruit-and-faults/transition.json")));
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void legitimatelyCompletedSuccessIsNotOverriddenByALateInterrupt() throws IOException {
    var fixture = new Fixture(temporary.toRealPath());
    fixture.run("start", fixture.root.toString(), "--yes");
    var original = fixture.application;
    var completed =
        new ApplicationFactory.Application(
            original.locator(),
            original.start(),
            original.status(),
            root -> {
              Thread.currentThread().interrupt();
              return new CheckOutcome.Passed(List.of());
            },
            original.hint(),
            original.lesson(),
            original.next(),
            original.list());
    try {
      var result = run(() -> completed, fixture.root, false, "", "check");
      assertEquals(0, result.code());
      assertEquals("", result.err());
      assertTrue(result.out().contains("Check passed"));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @ParameterizedTest
  @CsvSource({
    "TIMEOUT,TIMEOUT,5",
    "INTERRUPTED,INTERRUPTED,5",
    "UNAVAILABLE,INTERNAL_ERROR,10",
    "EXIT_FAILURE,INTERNAL_ERROR,10"
  })
  void startRetainsTypedGitFailuresWithoutParsingDiagnostics(
      GitInitializationException.Reason reason, FailureCategory category, int code)
      throws IOException {
    var fixture = new Fixture(temporary.toRealPath());
    fixture.git.failure = Optional.of(reason);
    var result = fixture.run("start", fixture.root.toString(), "--yes", "--verbose");
    assertEquals(code, result.code());
    assertFalse(result.err().contains("SECRET"));
    assertFalse(Files.exists(fixture.root.resolve(".fruit-and-faults/progress.json")));
    Thread.interrupted();
    Path other = temporary.toRealPath().resolve("other");
    var failed =
        (StartResult.Failed) fixture.application.start().apply(new StartRequest(other, true));
    assertEquals(category, failed.category());
    Thread.interrupted();
  }

  @ParameterizedTest
  @CsvSource({
    "INCOMPLETE_WORK,1",
    "MISSING_ARTIFACT,1",
    "WORKSPACE_CONFLICT,3",
    "COMPILATION_ERROR,4",
    "TEST_FAILURE,4",
    "TIMEOUT,5",
    "INTERRUPTED,5",
    "INTERNAL_ERROR,10"
  })
  void failedCheckAndNextNeverPersistAnswersOrProgress(FailureCategory category, int code)
      throws IOException {
    var fixture = new Fixture(temporary.toRealPath());
    fixture.run("start", fixture.root.toString(), "--yes");
    byte[] before = fixture.state();
    fixture.outcome = failure(category);
    assertEquals(code, fixture.run("check", "--verbose").code());
    assertEquals(code, fixture.run("next", "--answer", "compile-before-tests", "--yes").code());
    assertArrayEquals(before, fixture.state());
  }

  @Test
  void conflictingStartPreservesForeignFilesAndDoesNotLeakParentPaths() throws IOException {
    Path root = Files.createDirectory(temporary.toRealPath().resolve("occupied"));
    Path file = root.resolve("learner.txt");
    Files.writeString(file, "keep", StandardCharsets.UTF_8);
    var result =
        run(
            ApplicationFactory::create,
            temporary.toRealPath(),
            false,
            "",
            "start",
            root.toString(),
            "--yes");
    assertEquals(3, result.code());
    assertEquals("keep", Files.readString(file));
    assertFalse(Files.exists(root.resolve(".git")));
    assertFalse(result.err().contains(temporary.toRealPath().toString()));
  }

  @Test
  void resumedStartRetainsTypedGitTimeoutAndExistingState() throws IOException {
    var fixture = new Fixture(temporary.toRealPath());
    fixture.run("start", fixture.root.toString(), "--yes");
    byte[] before = fixture.state();
    fixture.git.validationFailure = true;
    var result = fixture.run("start", fixture.root.toString());
    assertEquals(5, result.code());
    assertTrue(result.err().contains("existing course state remains unchanged"));
    assertEquals(
        StartResult.Stage.GIT_INSPECTION,
        ((StartResult.Failed)
                fixture.application.start().apply(new StartRequest(fixture.root, false)))
            .stage());
    assertArrayEquals(before, fixture.state());
  }

  private static CheckOutcome.Failed failure(FailureCategory category) {
    return new CheckOutcome.Failed(
        category,
        List.of(
            new Diagnostic(
                "Работающий код",
                "Starter.java:7",
                "Исправьте код и выполните fruit-and-faults check")));
  }

  private static Invocation run(
      Supplier<ApplicationFactory.Application> factory,
      Path current,
      boolean interactive,
      String input,
      String... args) {
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();
    InputStream in =
        interactive
            ? new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
            : new InputStream() {
              @Override
              public int read() {
                throw new AssertionError("Input must never be read");
              }

              @Override
              public int read(byte[] buffer, int offset, int length) {
                return read();
              }
            };
    try (var out = new PrintStream(output, true, StandardCharsets.UTF_8);
        var err = new PrintStream(error, true, StandardCharsets.UTF_8)) {
      int code =
          FruitAndFaults.run(
              args, new ConsoleTerminal(in, out, err, interactive), current, factory);
      return new Invocation(
          code, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
    }
  }

  private record Invocation(int code, String out, String err) {}

  private static final class Fixture {
    private final ClasspathCourseCatalog catalog = new ClasspathCourseCatalog("course");
    private final ControlledGit git = new ControlledGit();
    private final Path root;
    private final ApplicationFactory.Application application;
    private CheckOutcome outcome = new CheckOutcome.Passed(List.of());

    Fixture(Path parent) {
      root = parent.resolve("learner");
      application = ApplicationFactory.compose(catalog, catalog, this::check, git);
    }

    private CheckOutcome check(CheckRequest ignored) {
      return outcome;
    }

    Invocation run(String... args) {
      return CommandJourneyTest.run(
          () -> application,
          Files.exists(root) ? root : java.util.Objects.requireNonNull(root.getParent()),
          false,
          "",
          args);
    }

    byte[] state() throws IOException {
      return Files.readAllBytes(root.resolve(".fruit-and-faults/progress.json"));
    }
  }

  private static final class ControlledGit implements GitRepository {
    private final GitRepository local = new ProcessGitRepository();
    private GitStatus observed = new GitStatus(Optional.of("a".repeat(40)), 0, 0, false, false);
    private Optional<GitInitializationException.Reason> failure = Optional.empty();
    private boolean validationFailure;

    @Override
    public void initialize(Path root) throws IOException {
      if (failure.isPresent()) {
        if (failure.orElseThrow() == GitInitializationException.Reason.INTERRUPTED)
          Thread.currentThread().interrupt();
        throw new GitInitializationException(failure.orElseThrow(), OptionalInt.of(1), "SECRET");
      }
      local.initialize(root);
    }

    @Override
    public void requireInitialized(Path root) throws IOException {
      if (validationFailure)
        throw new GitInitializationException(
            GitInitializationException.Reason.TIMEOUT, OptionalInt.empty(), "SECRET");
      local.requireInitialized(root);
    }

    @Override
    public GitStatus status(Path root) {
      return observed;
    }
  }
}
