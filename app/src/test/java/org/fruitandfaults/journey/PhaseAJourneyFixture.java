package org.fruitandfaults.journey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import org.fruitandfaults.cli.ApplicationFactory;
import org.fruitandfaults.cli.ConsoleTerminal;
import org.fruitandfaults.cli.FruitAndFaults;
import org.fruitandfaults.course.infra.ClasspathCourseCatalog;
import org.fruitandfaults.course.infra.LearnerJourneyFixture;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.fruitandfaults.progress.infra.AtomicProgressRepository;
import org.fruitandfaults.progress.infra.JacksonProgressCodec;
import org.jspecify.annotations.Nullable;

/** Real installed command runner and temporary learner edits, never a production solution. */
final class PhaseAJourneyFixture {
  static final String MAIN = "src/main/java/org/fruitandfaults/game/";
  static final String TEST = "src/test/java/org/fruitandfaults/game/";
  private final ClasspathCourseCatalog catalog = new ClasspathCourseCatalog("course");
  private final Path parent;
  private final Path root;

  PhaseAJourneyFixture(Path temporary) throws IOException {
    this(temporary, "Моя игра with spaces");
  }

  PhaseAJourneyFixture(Path temporary, String directory) throws IOException {
    parent = temporary.toRealPath();
    root = parent.resolve(directory);
  }

  ClasspathCourseCatalog catalog() {
    return catalog;
  }

  Path parent() {
    return parent;
  }

  Path root() {
    return root;
  }

  Transcript start() throws IOException {
    Transcript started = run(parent, "start", root.toString(), "--yes");
    assertEquals(0, started.code(), started.err());
    prepareCache(root);
    return started;
  }

  static void prepareCache(Path root) throws IOException {
    Path home = LearnerJourneyFixture.prepareOfflineGradleHome(root);
    Path init = home.resolve("init.gradle");
    Files.writeString(
        init,
        Files.readString(init)
            .replace(
                "new File(System.getenv('GRADLE_USER_HOME')).canonicalFile",
                "new File('"
                    + home.toString().replace("\\", "\\\\").replace("'", "\\'")
                    + "').canonicalFile"));
    // Only test-local Gradle settings; the disclosed learner project remains byte-identical.
    Files.writeString(
        home.resolve("gradle.properties"),
        "org.gradle.workers.max=1\norg.gradle.java.installations.auto-download=false\n"
            + "org.gradle.java.installations.paths="
            + System.getProperty("java.home").replace("\\", "\\\\")
            + "\n");
  }

  Transcript run(String... arguments) {
    return run(root, arguments);
  }

  Transcript run(Path current, String... arguments) {
    return run(current, ApplicationFactory::create, arguments);
  }

  Transcript run(
      Path current, Supplier<ApplicationFactory.Application> application, String... arguments) {
    @Nullable String previousGradle = System.getProperty("gradle.user.home");
    @Nullable String previousHome = System.getProperty("user.home");
    System.setProperty("gradle.user.home", ".gradle/fixture-user-home");
    System.setProperty("user.home", parent.resolve("isolated-user-home").toString());
    try (var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var output = new PrintStream(out, true, StandardCharsets.UTF_8);
        var error = new PrintStream(err, true, StandardCharsets.UTF_8)) {
      var terminal =
          new ConsoleTerminal(new ByteArrayInputStream(new byte[0]), output, error, false);
      int code = FruitAndFaults.run(arguments, terminal, current, application);
      return new Transcript(
          code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    } finally {
      restore("gradle.user.home", previousGradle);
      restore("user.home", previousHome);
    }
  }

  void solve(int lesson) throws IOException {
    var paths =
        switch (lesson) {
          case 0 -> List.of(MAIN + "Starter.java");
          case 1 -> List.of(MAIN + "Coordinate.java");
          case 2 ->
              List.of(
                  MAIN + "Board.java", MAIN + "Movement.java", TEST + "AnalogousBoundaryTest.java");
          case 3 -> List.of(MAIN + "GameState.java");
          default -> throw new IllegalArgumentException("Unknown Phase A lesson.");
        };
    for (String path : paths) {
      LearnerJourneyFixture.applySolution(
          root, catalog.load().lessons().get(lesson).id().value(), path);
    }
  }

  String commit(String message) throws IOException {
    git("add", ".");
    git(
        "-c",
        "user.name=Journey Learner",
        "-c",
        "user.email=journey@example.invalid",
        "commit",
        "-m",
        message);
    assertEquals("", git("status", "--porcelain=v1"));
    return git("rev-parse", "HEAD").strip();
  }

  String git(String... arguments) throws IOException {
    return LearnerJourneyFixture.runGit(root, arguments);
  }

  CourseProgress progress() throws IOException {
    return new AtomicProgressRepository(new JacksonProgressCodec(catalog)).load(root).orElseThrow();
  }

  byte[] progressBytes() throws IOException {
    return Files.readAllBytes(root.resolve(".fruit-and-faults/progress.json"));
  }

  void assertSafeFailure(Transcript result, int code) {
    assertEquals(code, result.code(), result.out() + result.err());
    assertTrue(result.err().contains("Ожидалось:"), result.err());
    assertTrue(result.err().contains("Получено:"), result.err());
    assertTrue(result.err().contains("Дальше:"), result.err());
    assertFalse(result.err().contains(parent.toString()), result.err());
    assertFalse(result.err().contains("\tat "), result.err());
    assertFalse(result.err().contains("Exception in thread"), result.err());
    assertFalse(result.err().contains("SECRET_JOURNEY"), result.err());
  }

  private static void restore(String property, @Nullable String previous) {
    if (previous == null) System.clearProperty(property);
    else System.setProperty(property, previous);
  }

  record Transcript(int code, String out, String err) {}
}
