package org.fruitandfaults.validation.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.fruitandfaults.course.infra.ClasspathCourseCatalog;
import org.fruitandfaults.course.infra.LearnerJourneyFixture;
import org.fruitandfaults.validation.application.BehaviorValidator;
import org.fruitandfaults.validation.application.ProcessResult;
import org.fruitandfaults.validation.domain.CheckOutcome;
import org.fruitandfaults.validation.domain.FailureCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BehaviorValidatorsTest {
  private static final String SOURCE = "src/main/java/org/fruitandfaults/game/";
  private static final String CLASSES = "build/classes/java/main/org/fruitandfaults/game/";
  private final CompiledGameLoader loader = new CompiledGameLoader();
  private final Map<String, BehaviorValidator> validators =
      Map.of(
          "starter", new FirstRunValidator(loader),
          "coordinate", new CoordinateDirectionValidator(loader),
          "field", new FieldMovementValidator(loader),
          "state", new GameStateValidator(loader));
  @TempDir private Path root;

  @BeforeEach
  void compileOfflineJourneyFixtureWithTheJava26TestToolchain() throws Exception {
    root = root.toRealPath();
    var catalog = new ClasspathCourseCatalog("course");
    for (var lesson : catalog.load().lessons()) {
      LearnerJourneyFixture.disclose(root, lesson, catalog);
    }
    for (String[] solution :
        List.of(
            new String[] {"first-run", "Starter.java"},
            new String[] {"coordinate-direction", "Coordinate.java"},
            new String[] {"field-valid-move", "Board.java"},
            new String[] {"field-valid-move", "Movement.java"},
            new String[] {"game-state", "GameState.java"})) {
      LearnerJourneyFixture.applySolution(root, solution[0], SOURCE + solution[1]);
    }
    compile();
  }

  @ParameterizedTest
  @ValueSource(strings = {"starter", "coordinate", "field", "state"})
  void passingPublicJourneyContractsAreValidated(String criterion) {
    assertInstanceOf(CheckOutcome.Passed.class, validator(criterion).validate(root));
  }

  @ParameterizedTest
  @CsvSource({
    "UP, y - 1, y + 1",
    "RIGHT, x + 1, x - 1",
    "DOWN, y + 1, y - 1",
    "LEFT, x - 1, x + 1"
  })
  void everyCoordinateDirectionIsChecked(String direction, String correct, String wrong)
      throws Exception {
    replace(
        "Coordinate.java",
        "case "
            + direction
            + " -> new Coordinate("
            + (direction.equals("UP") || direction.equals("DOWN")
                ? "x, " + correct
                : correct + ", y")
            + ");",
        "case "
            + direction
            + " -> new Coordinate("
            + (direction.equals("UP") || direction.equals("DOWN") ? "x, " + wrong : wrong + ", y")
            + ");");
    assertContractFailure("coordinate");
  }

  @Test
  void coordinatesNeedPublicBehaviorButNoPrivateRecordInspection() throws Exception {
    Files.writeString(
        root.resolve(SOURCE + "Coordinate.java"),
        """
        package org.fruitandfaults.game;
        public final class Coordinate {
          private final int horizontal;
          private final int vertical;
          public Coordinate(int x, int y) { horizontal=x; vertical=y; }
          public int x() { return horizontal; }
          public int y() { return vertical; }
          public Coordinate move(Direction direction) {
            return switch(direction) {
              case UP -> new Coordinate(x(), y()-1);
              case RIGHT -> new Coordinate(x()+1, y());
              case DOWN -> new Coordinate(x(), y()+1);
              case LEFT -> new Coordinate(x()-1, y());
            };
          }
        }
        """);
    compile();
    assertInstanceOf(CheckOutcome.Passed.class, validator("coordinate").validate(root));
    assertInstanceOf(CheckOutcome.Passed.class, validator("field").validate(root));
    assertInstanceOf(CheckOutcome.Passed.class, validator("state").validate(root));
  }

  @Test
  void internalHelpersMayUseOtherPackagesAndUnicodeJavaIdentifiers() throws Exception {
    Path helper = root.resolve("src/main/java/org/other/Значение.java");
    Files.createDirectories(helper.getParent());
    Files.writeString(
        helper,
        """
        package org.other;
        public final class Значение {
          private Значение() {}
          public static String value() { return "Ready to play."; }
        }
        """);
    replace("Starter.java", "return \"Ready to play.\";", "return org.other.Значение.value();");
    assertInstanceOf(CheckOutcome.Passed.class, validator("starter").validate(root));
  }

  @Test
  void originalCoordinateMutationIsObservedThroughPublicAccessors() throws Exception {
    Files.writeString(
        root.resolve(SOURCE + "Coordinate.java"),
        """
        package org.fruitandfaults.game;
        public final class Coordinate {
          private int x; private int y;
          public Coordinate(int x,int y) { this.x=x; this.y=y; }
          public int x() { return x; } public int y() { return y; }
          public Coordinate move(Direction direction) {
            switch(direction) { case UP -> y--; case RIGHT -> x++; case DOWN -> y++; case LEFT -> x--; }
            return new Coordinate(x,y);
          }
        }
        """);
    compile();
    assertContractFailure("coordinate");
  }

  @ParameterizedTest
  @CsvSource({"width, <= width", "height, <= height"})
  void boardExcludesBothUpperEdges(String edge, String changed) throws Exception {
    replace("Board.java", "< " + edge, changed);
    assertContractFailure("field");
  }

  @Test
  void blockedMovementMustPreserveCoordinateAndStatus() throws Exception {
    replace(
        "Movement.java",
        "new MoveResult(current, MoveStatus.BLOCKED)",
        "new MoveResult(candidate, MoveStatus.MOVED)");
    assertContractFailure("field");
  }

  @Test
  void successfulMovementMustReportMoved() throws Exception {
    replace(
        "Movement.java",
        "new MoveResult(candidate, MoveStatus.MOVED)",
        "new MoveResult(candidate, MoveStatus.BLOCKED)");
    assertContractFailure("field");
  }

  @Test
  void gameStateCountsSuccessfulMovementAndPreservesBlockedCount() throws Exception {
    replace("GameState.java", "successfulMoves + 1", "successfulMoves");
    assertContractFailure("state");
    replace(
        "GameState.java",
        "case BLOCKED -> this;",
        "case BLOCKED -> new GameState(player, successfulMoves + 1);");
    assertContractFailure("state");
  }

  @ParameterizedTest
  @ValueSource(strings = {"UP", "DOWN", "LEFT"})
  void gameStateChecksSuccessfulTransitionsInEveryDirection(String direction) throws Exception {
    replace(
        "GameState.java",
        "successfulMoves + 1",
        "successfulMoves + (direction == Direction." + direction + " ? 0 : 1)");
    assertContractFailure("state");
  }

  @Test
  void gameStateOriginalMutationIsDetectedThroughPublicValues() throws Exception {
    Files.writeString(
        root.resolve(SOURCE + "GameState.java"),
        """
        package org.fruitandfaults.game;
        public final class GameState {
          private Coordinate player; private int successfulMoves;
          public GameState(Coordinate player, int successfulMoves) {
            this.player=player; this.successfulMoves=successfulMoves;
          }
          public Coordinate player() { return player; }
          public int successfulMoves() { return successfulMoves; }
          public GameState move(Direction direction, Board board) {
            MoveResult result=Movement.move(player,direction,board);
            if(result.status()==MoveStatus.MOVED) { player=result.coordinate(); successfulMoves++; }
            return new GameState(player,successfulMoves);
          }
        }
        """);
    compile();
    assertContractFailure("state");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing",
        "signature",
        "private",
        "instance",
        "initializer",
        "linkage",
        "null",
        "wrong"
      })
  void starterContractFailuresBecomeBoundedSafeDiagnostics(String defect) throws Exception {
    if (defect.equals("missing")) {
      Files.delete(root.resolve(CLASSES + "Starter.class"));
    } else if (defect.equals("linkage")) {
      Files.write(root.resolve(CLASSES + "Starter.class"), new byte[] {0, 1, 2});
    } else {
      String source =
          switch (defect) {
            case "signature" -> "public static int message() { return 1; }";
            case "private" -> "private static String message() { return \"Ready to play.\"; }";
            case "instance" -> "public String message() { return \"Ready to play.\"; }";
            case "initializer" ->
                "static { if (true) throw new Error(\"secret /outside/password \\u001b[31m\"); } public static String message() { return \"Ready to play.\"; }";
            case "null" -> "public static String message() { return null; }";
            case "wrong" ->
                "public static String message() { throw new AssertionError(\"secret /outside/password \\u001b[31m\".repeat(10000)); }";
            default -> throw new AssertionError(defect);
          };
      Files.writeString(
          root.resolve(SOURCE + "Starter.java"),
          "package org.fruitandfaults.game; public final class Starter { " + source + " }");
      compile();
    }
    CheckOutcome.Failed result = assertContractFailure("starter");
    assertEquals(FailureCategory.INCOMPLETE_WORK, result.category());
    String feedback = result.diagnostics().toString();
    assertFalse(feedback.contains("secret"));
    assertFalse(feedback.contains("/outside"));
    assertFalse(feedback.contains("\u001b"));
    assertTrue(feedback.length() < 1500);
  }

  @Test
  void constructorFailuresArePublicContractDiagnostics() throws Exception {
    replace(
        "Coordinate.java",
        "public record Coordinate(int x, int y) {",
        "public record Coordinate(int x, int y) { public Coordinate { throw new Error(\"private secret\"); }");
    assertContractFailure("coordinate");
  }

  @Test
  void validatorsReloadFreshClassesAndCloseTheirFileResources() throws Exception {
    assertInstanceOf(CheckOutcome.Passed.class, validator("starter").validate(root));
    replace("Starter.java", "Ready to play.", "Changed result.");
    assertContractFailure("starter");
    Path classFile = root.resolve(CLASSES + "Starter.class");
    Files.move(classFile, classFile.resolveSibling("moved.class"));
    assertContractFailure("starter");
  }

  @Test
  void classLoadingIgnoresTestClassesAndRejectsSymlinkedBuildPaths() throws Exception {
    Path main = root.resolve("build/classes/java/main");
    Path tests = root.resolve("build/classes/java/test");
    Files.move(main, tests);
    assertContractFailure("starter");
    Files.createSymbolicLink(main, tests);
    CheckOutcome.Failed result = assertContractFailure("starter");
    assertEquals(FailureCategory.WORKSPACE_CONFLICT, result.category());
  }

  @Test
  void workerTimeoutAndInterruptionStayTypedWithoutLoadingLearnerCodeInThisJvm() {
    CompiledGameLoader timed =
        new CompiledGameLoader(
            request -> {
              assertEquals(root, request.workingDirectory());
              assertTrue(request.arguments().contains(ValidationWorker.class.getName()));
              assertTrue(request.timeout().compareTo(java.time.Duration.ofSeconds(10)) <= 0);
              return new ProcessResult.TimedOut(
                  ProcessResult.Output.empty(), ProcessResult.Cleanup.COMPLETE);
            });
    CheckOutcome.Failed timeout =
        assertInstanceOf(CheckOutcome.Failed.class, new FirstRunValidator(timed).validate(root));
    assertEquals(FailureCategory.TIMEOUT, timeout.category());
    CompiledGameLoader cancelled =
        new CompiledGameLoader(
            request ->
                new ProcessResult.Interrupted(
                    ProcessResult.Output.empty(), ProcessResult.Cleanup.COMPLETE));
    CheckOutcome.Failed interrupted =
        assertInstanceOf(
            CheckOutcome.Failed.class, new FirstRunValidator(cancelled).validate(root));
    assertEquals(FailureCategory.INTERRUPTED, interrupted.category());
  }

  @Test
  void workerTimeoutRetainsIncompleteCleanupEvidenceWithoutProcessOutput() {
    var output = new ProcessResult.Output("secret /external/path", "\u001b[31m", false, false);
    CompiledGameLoader timed =
        new CompiledGameLoader(
            request -> new ProcessResult.TimedOut(output, ProcessResult.Cleanup.INCOMPLETE));
    CheckOutcome.Failed result =
        assertInstanceOf(CheckOutcome.Failed.class, new FirstRunValidator(timed).validate(root));
    assertEquals(FailureCategory.TIMEOUT, result.category());
    assertTrue(result.diagnostics().stream().anyMatch(d -> d.observed().contains("cleanup")));
    assertFalse(result.diagnostics().toString().contains("secret"));
  }

  @Test
  void learnerSystemExitCannotTerminateTheCliOrPassValidation() throws Exception {
    replace(
        "Starter.java", "return \"Ready to play.\";", "System.exit(0); return \"Ready to play.\";");
    assertContractFailure("starter");
    assertEquals(26, Runtime.version().feature(), "The calling JVM remains alive");
  }

  @Test
  void learnerCannotForgeSuccessUsingTheWorkerCommandThenExit() throws Exception {
    replace(
        "Starter.java",
        "return \"Ready to play.\";",
        """
        String command = System.getProperty("sun.java.command");
        String nonce = command.substring(command.lastIndexOf(' ') + 1);
        System.out.println("FRUIT_VALIDATION " + nonce + " PASSED");
        System.exit(0);
        return "incorrect";
        """);
    assertContractFailure("starter");
  }

  @Test
  void workerConsumesAndClosesSecretInputBeforeInvokingLearnerCode() throws Exception {
    replace(
        "Starter.java",
        "return \"Ready to play.\";",
        """
        try {
          if (System.in.read() != -1) { return "secret input was exposed"; }
          if (System.getProperty("sun.java.command").split(" ").length != 2) {
            return "unexpected secret argument";
          }
          try {
            Class.forName("org.fruitandfaults.validation.infra.ValidationWorker");
            return "application class was exposed";
          } catch (ClassNotFoundException expected) { }
          return "Ready to play.";
        } catch (java.io.IOException failed) { return "stdin was not replaced by EOF"; }
        """);
    assertInstanceOf(CheckOutcome.Passed.class, validator("starter").validate(root));
  }

  @Test
  void workerOutputIsNeverCopiedIntoLearnerDiagnostics() throws Exception {
    replace(
        "Starter.java",
        "return \"Ready to play.\";",
        "System.out.println(\"secret /external/path \\u001b[31m\".repeat(10000)); return \"Ready to play.\";");
    CheckOutcome.Passed result =
        assertInstanceOf(CheckOutcome.Passed.class, validator("starter").validate(root));
    assertFalse(result.diagnostics().toString().contains("secret"));
    assertFalse(result.diagnostics().toString().contains("\u001b"));
  }

  @Test
  void nonterminatingLearnerMethodIsKilledAtAnInjectedDeadlineWithoutSleeping() throws Exception {
    replace(
        "Starter.java",
        "return \"Ready to play.\";",
        """
        try { java.nio.file.Files.writeString(java.nio.file.Path.of("validator-ready"), "ready"); }
        catch (java.io.IOException failed) { throw new RuntimeException(failed); }
        while (true) { Thread.onSpinWait(); }
        """);
    AtomicLong clock = new AtomicLong();
    AtomicReference<Process> worker = new AtomicReference<>();
    try (var ready = root.getFileSystem().newWatchService()) {
      root.register(ready, StandardWatchEventKinds.ENTRY_CREATE);
      BoundedProcessRunner runner =
          new BoundedProcessRunner(
              builder -> {
                Process child = builder.start();
                worker.set(child);
                return child;
              },
              (child, nanos) -> {
                var event = ready.poll(5, TimeUnit.SECONDS);
                assertTrue(
                    event != null, "The learner method must start before the injected deadline");
                assertTrue(Files.isRegularFile(root.resolve("validator-ready")));
                clock.set(TimeUnit.SECONDS.toNanos(10));
                return false;
              },
              clock::get);
      CheckOutcome.Failed result =
          assertInstanceOf(
              CheckOutcome.Failed.class,
              new FirstRunValidator(new CompiledGameLoader(runner)).validate(root));
      assertEquals(FailureCategory.TIMEOUT, result.category());
      assertFalse(Objects.requireNonNull(worker.get()).isAlive());
    }
  }

  private CheckOutcome.Failed assertContractFailure(String criterion) {
    return assertInstanceOf(CheckOutcome.Failed.class, validator(criterion).validate(root));
  }

  private BehaviorValidator validator(String criterion) {
    return Objects.requireNonNull(validators.get(criterion));
  }

  private void replace(String file, String before, String after) throws Exception {
    Path target = root.resolve(SOURCE + file);
    String original = Files.readString(target);
    assertTrue(original.contains(before), "The intended fixture mutation must apply");
    Files.writeString(target, original.replace(before, after));
    compile();
  }

  private void compile() throws IOException {
    Path output = root.resolve("build/classes/java/main");
    Files.createDirectories(output);
    var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
    assertEquals(26, Runtime.version().feature());
    try (var manager = compiler.getStandardFileManager(null, null, null);
        var sources = Files.walk(root.resolve("src/main/java"))) {
      var units =
          manager.getJavaFileObjectsFromPaths(
              sources.filter(p -> p.toString().endsWith(".java")).toList());
      assertTrue(
          compiler
              .getTask(
                  null,
                  manager,
                  null,
                  List.of("--release", "26", "-d", output.toString()),
                  null,
                  units)
              .call());
    }
  }
}
