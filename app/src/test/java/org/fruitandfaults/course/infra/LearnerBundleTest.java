package org.fruitandfaults.course.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

import org.fruitandfaults.course.domain.AssetPolicy;
import org.fruitandfaults.course.domain.Course;
import org.fruitandfaults.course.domain.Lesson;
import org.fruitandfaults.course.domain.LessonAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LearnerBundleTest {
  private static final String MAIN = "src/main/java/org/fruitandfaults/game/";
  private static final String TEST = "src/test/java/org/fruitandfaults/game/";
  private final ClasspathCourseCatalog catalog = new ClasspathCourseCatalog("course");
  private final Course course = catalog.load();
  @TempDir private Path workspace;

  @Test
  void everyAssetHasExplicitVerifiedBytesAndItsOwnDisclosureLesson() throws Exception {
    List<Set<String>> expected =
        List.of(
            Set.of(
                "settings.gradle.kts",
                "build.gradle.kts",
                "gradlew",
                "gradlew.bat",
                "gradle/wrapper/gradle-wrapper.jar",
                "gradle/wrapper/gradle-wrapper.properties",
                ".gitignore",
                "docs/publishing-to-github.md",
                MAIN + "Starter.java",
                TEST + "StarterTest.java"),
            Set.of(MAIN + "Direction.java", MAIN + "Coordinate.java", TEST + "CoordinateTest.java"),
            Set.of(
                MAIN + "Board.java",
                MAIN + "MoveStatus.java",
                MAIN + "MoveResult.java",
                MAIN + "Movement.java",
                TEST + "MovementTest.java",
                TEST + "AnalogousBoundaryTest.java"),
            Set.of(MAIN + "GameState.java", TEST + "GameStateTest.java"));
    Set<String> disclosed = new HashSet<>();
    for (int index = 0; index < course.lessons().size(); index++) {
      Lesson lesson = course.lessons().get(index);
      assertEquals(
          expected.get(index),
          new HashSet<>(lesson.assets().stream().map(LessonAsset::relativePath).toList()));
      Properties metadata = metadata(lesson);
      for (LessonAsset asset : lesson.assets()) {
        assertTrue(disclosed.add(asset.relativePath()), "An asset must be disclosed only once");
        byte[] bytes = catalog.load(asset);
        assertTrue(bytes.length > 0, asset.relativePath());
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(hash, asset.sha256());
        assertEquals(hash, metadata.getProperty("asset." + asset.id().value() + ".sha256"));
        assertTrue(asset.resourcePath().startsWith(directory(lesson) + "/assets/"));
        assertEquals(expectedPolicy(asset.relativePath()), asset.policy());
        assertFalse(asset.resourcePath().contains("journeys"));
      }
      assertTrue(disclosed.containsAll(lesson.expectedArtifacts()));
    }
    assertEquals(21, disclosed.size());
    Set<String> declaredResources = new HashSet<>();
    course
        .lessons()
        .forEach(
            lesson ->
                lesson.assets().forEach(asset -> declaredResources.add(asset.resourcePath())));
    Path resources = Path.of("src/main/resources");
    try (var files = Files.walk(resources.resolve("course/lessons"))) {
      assertEquals(
          declaredResources,
          new HashSet<>(
              files
                  .filter(Files::isRegularFile)
                  .map(resources::relativize)
                  .map(Path::toString)
                  .map(path -> path.replace('\\', '/'))
                  .filter(path -> path.contains("/assets/"))
                  .toList()),
          "No undeclared resources or solution source may enter the production bundle");
    }
  }

  @Test
  void reusedWrapperAndIgnoreRulesKeepTheProjectPortable() throws IOException {
    disclose(0);
    for (String wrapper :
        List.of(
            "gradlew",
            "gradlew.bat",
            "gradle/wrapper/gradle-wrapper.properties",
            "gradle/wrapper/gradle-wrapper.jar")) {
      Path repositoryWrapper = Path.of("..").toAbsolutePath().normalize().resolve(wrapper);
      assertEquals(
          HexFormat.of().formatHex(Files.readAllBytes(repositoryWrapper)),
          HexFormat.of().formatHex(Files.readAllBytes(workspace.resolve(wrapper))));
    }
    Files.createDirectories(workspace.resolve(".fruit-and-faults"));
    for (String path :
        List.of(
            ".fruit-and-faults/progress.json",
            ".fruit-and-faults/managed-files.json",
            ".fruit-and-faults/workspace.properties",
            ".fruit-and-faults/transition.json",
            ".gradle/cache",
            "build/classes/output.class")) {
      Path target = workspace.resolve(path);
      Files.createDirectories(target.getParent());
      Files.writeString(target, "fixture");
    }
    LearnerJourneyFixture.runGit(workspace, "init");
    String tracked =
        LearnerJourneyFixture.runGit(workspace, "ls-files", "--others", "--exclude-standard");
    assertTrue(tracked.contains(".fruit-and-faults/progress.json"), tracked);
    assertTrue(tracked.contains(".fruit-and-faults/managed-files.json"), tracked);
    assertTrue(tracked.contains(".fruit-and-faults/workspace.properties"), tracked);
    assertTrue(tracked.contains("gradle/wrapper/gradle-wrapper.jar"), tracked);
    assertFalse(tracked.contains("transition.json"), tracked);
    assertFalse(tracked.contains(".gradle/cache"), tracked);
    assertFalse(tracked.contains("output.class"), tracked);
  }

  @Test
  void distributionCacheSelectionUsesTheFullBundledUrlWithCompetingCompletedHashes()
      throws Exception {
    disclose(0);
    Properties wrapper = new Properties();
    try (InputStream input =
        Files.newInputStream(workspace.resolve("gradle/wrapper/gradle-wrapper.properties"))) {
      wrapper.load(input);
    }
    Path source = workspace.resolve("source-cache");
    Path services = completedDistribution(source, "1w1c7tv4s851m17nbqdsro2tv");
    Path mirror = completedDistribution(source, "c4u2luipyybyn2ofveo7ocla6");

    Path fromServices =
        LearnerJourneyFixture.cachedDistribution(
            source, URI.create(wrapper.getProperty("distributionUrl")));
    Path fromMirror =
        LearnerJourneyFixture.cachedDistribution(
            source, URI.create("https://mirror.example/distributions/gradle-9.7.1-bin.zip"));

    assertEquals(services, fromServices);
    assertEquals(mirror, fromMirror);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"missing-hash", "missing-marker", "missing-payload", "different-seeded-home"})
  void unavailableExactDistributionFailsBeforeLaunchingEvenWhenAnotherHashIsComplete(String missing)
      throws Exception {
    disclose(0);
    Files.writeString(workspace.resolve("gradlew"), "#!/bin/sh\ntouch wrapper-launched\n");
    Files.writeString(
        workspace.resolve("gradlew.bat"), "@echo off\r\necho launched> wrapper-launched\r\n");
    Path source = workspace.resolve("source-cache");
    completedDistribution(source, "c4u2luipyybyn2ofveo7ocla6");
    Path exact = source.resolve("wrapper/dists/gradle-9.7.1-bin/1w1c7tv4s851m17nbqdsro2tv");
    if (missing.equals("missing-marker")) {
      Files.createDirectories(exact.resolve("gradle-9.7.1"));
    } else if (missing.equals("missing-payload")) {
      Files.createDirectories(exact);
      Files.writeString(exact.resolve("gradle-9.7.1-bin.zip.ok"), "");
    } else if (missing.equals("different-seeded-home")) {
      Path isolated = workspace.resolve(".gradle/fixture-user-home");
      completedDistribution(isolated, "c4u2luipyybyn2ofveo7ocla6");
      Files.writeString(isolated.resolve("init.gradle"), "");
    }

    IOException failure =
        assertThrows(IOException.class, () -> LearnerJourneyFixture.build(workspace, source));

    String message = Objects.requireNonNull(failure.getMessage());
    assertTrue(
        message.contains("exact cached Gradle distribution 1w1c7tv4s851m17nbqdsro2tv"), message);
    assertFalse(Files.exists(workspace.resolve("wrapper-launched")));
    if (missing.equals("different-seeded-home")) {
      assertEquals(
          "", Files.readString(workspace.resolve(".gradle/fixture-user-home/init.gradle")));
    } else {
      assertFalse(Files.exists(workspace.resolve(".gradle/fixture-user-home")));
    }
  }

  private static Path completedDistribution(Path source, String hash) throws IOException {
    Path directory = source.resolve("wrapper/dists/gradle-9.7.1-bin").resolve(hash);
    Files.createDirectories(directory.resolve("gradle-9.7.1"));
    Files.writeString(directory.resolve("gradle-9.7.1-bin.zip.ok"), "");
    return directory;
  }

  @Test
  void wrapperUsesATemporaryGradleHomeInsteadOfTheDeveloperHome() throws Exception {
    disclose(0);
    Files.writeString(
        workspace.resolve("gradlew"), "#!/bin/sh\nprintf '%s\\n' \"$GRADLE_USER_HOME\"\n");
    Files.writeString(workspace.resolve("gradlew.bat"), "@echo off\r\necho %GRADLE_USER_HOME%\r\n");

    LearnerJourneyFixture.BuildResult probe = LearnerJourneyFixture.build(workspace);

    assertEquals(0, probe.exitCode(), probe.output());
    assertEquals(
        workspace.toRealPath().resolve(".gradle/fixture-user-home").toString(),
        probe.output().strip());
    assertNotEquals(
        Path.of(System.getProperty("user.home"), ".gradle").toString(), probe.output().strip());
    Path isolated = Path.of(probe.output().strip());
    assertTrue(isolated.startsWith(workspace.toRealPath()));
    assertFalse(Files.exists(isolated.resolve("daemon")));
    assertFalse(Files.exists(isolated.resolve("native")));
    assertFalse(Files.exists(isolated.resolve("caches")));
    try (var files = Files.walk(isolated)) {
      assertTrue(
          files.noneMatch(Files::isSymbolicLink),
          "Offline seed must contain copies, never home-linked files");
    }
  }

  @Test
  void cumulativeLearnerJourneyRequiresEachExerciseAndPreservesEarlierChecks() throws Exception {
    disclose(0);
    LearnerJourneyFixture.BuildResult initial = LearnerJourneyFixture.build(workspace);
    assertIsolatedGradleHome(initial);
    assertNotEquals(0, initial.exitCode(), initial.output());
    assertTrue(
        initial.output().replace('\\', '/').contains(MAIN + "Starter.java:9: error: ';' expected"),
        initial.output());
    assertEquals(
        1,
        initial
            .output()
            .lines()
            .map(String::strip)
            .filter(line -> line.matches(".*\\.java:[0-9]+: error:.*"))
            .distinct()
            .count(),
        initial.output());
    assertTrue(initial.output().contains("Task :compileJava FAILED"), initial.output());
    assertFalse(
        Files.exists(
            workspace.resolve(
                "build/test-results/test/TEST-org.fruitandfaults.game.StarterTest.xml")));
    assertFalse(Files.exists(workspace.resolve(MAIN + "Coordinate.java")));
    String brokenStarter = Files.readString(workspace.resolve(MAIN + "Starter.java"));
    LearnerJourneyFixture.applySolution(workspace, "first-run", MAIN + "Starter.java");
    assertEquals(
        brokenStarter.replace("return \"Ready to play.\"", "return \"Ready to play.\";"),
        Files.readString(workspace.resolve(MAIN + "Starter.java")));
    assertPassingBuild(1);

    disclose(1);
    assertUnsolvedBuild();
    assertFalse(Files.exists(workspace.resolve(MAIN + "Board.java")));
    LearnerJourneyFixture.applySolution(
        workspace, "coordinate-direction", MAIN + "Coordinate.java");
    assertPassingBuild(5);

    disclose(2);
    assertUnsolvedBuild();
    assertFalse(Files.exists(workspace.resolve(MAIN + "GameState.java")));
    for (String path :
        List.of(MAIN + "Board.java", MAIN + "Movement.java", TEST + "AnalogousBoundaryTest.java")) {
      LearnerJourneyFixture.applySolution(workspace, "field-valid-move", path);
    }
    assertPassingBuild(18);

    disclose(3);
    assertUnsolvedBuild();
    LearnerJourneyFixture.applySolution(workspace, "game-state", MAIN + "GameState.java");
    assertPassingBuild(21);
    for (Lesson lesson : course.lessons()) {
      for (LessonAsset asset : lesson.assets()) {
        if (asset.policy() == AssetPolicy.IMMUTABLE_CHECK) {
          assertEquals(
              new String(catalog.load(asset), StandardCharsets.UTF_8),
              Files.readString(workspace.resolve(asset.relativePath())));
        }
      }
    }
  }

  private void assertUnsolvedBuild() throws Exception {
    LearnerJourneyFixture.BuildResult result = LearnerJourneyFixture.build(workspace);
    assertIsolatedGradleHome(result);
    assertNotEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("Task :test FAILED"), result.output());
    assertFalse(result.output().contains("Task :compileJava FAILED"), result.output());
  }

  private void assertPassingBuild(int expectedTests) throws Exception {
    LearnerJourneyFixture.BuildResult result = LearnerJourneyFixture.build(workspace);
    assertIsolatedGradleHome(result);
    assertEquals(0, result.exitCode(), result.output());
    assertEquals(expectedTests, LearnerJourneyFixture.passingTestCount(workspace));
  }

  private void disclose(int index) throws IOException {
    LearnerJourneyFixture.disclose(workspace, course.lessons().get(index), catalog);
  }

  private void assertIsolatedGradleHome(LearnerJourneyFixture.BuildResult result)
      throws IOException {
    assertTrue(
        result
            .output()
            .contains(
                "FIXTURE_GRADLE_HOME="
                    + workspace.toRealPath().resolve(".gradle/fixture-user-home")),
        result.output());
  }

  private static AssetPolicy expectedPolicy(String path) {
    if (path.equals(TEST + "AnalogousBoundaryTest.java")) {
      return AssetPolicy.EDITABLE_TEMPLATE;
    }
    return path.startsWith(TEST) ? AssetPolicy.IMMUTABLE_CHECK : AssetPolicy.LEARNER_SCAFFOLD;
  }

  private static String directory(Lesson lesson) {
    return "course/lessons/"
        + Map.of(
                "first-run",
                "01-first-run",
                "coordinate-direction",
                "02-coordinate-direction",
                "field-valid-move",
                "03-field-valid-move",
                "game-state",
                "04-game-state")
            .get(lesson.id().value());
  }

  private static Properties metadata(Lesson lesson) throws IOException {
    try (InputStream input =
        Objects.requireNonNull(
            LearnerBundleTest.class
                .getClassLoader()
                .getResourceAsStream(directory(lesson) + "/lesson.properties"))) {
      Properties result = new Properties();
      result.load(input);
      return result;
    }
  }
}
