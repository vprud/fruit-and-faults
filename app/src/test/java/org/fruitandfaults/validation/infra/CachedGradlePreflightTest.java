package org.fruitandfaults.validation.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.fruitandfaults.validation.application.GradlePreflight;
import org.fruitandfaults.validation.application.ProcessResult;
import org.fruitandfaults.validation.domain.FailureCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CachedGradlePreflightTest {
  private static final String URL =
      "https://services.gradle.org/distributions/gradle-9.7.1-bin.zip";
  private static final String KEY = "1w1c7tv4s851m17nbqdsro2tv";
  private static final String MIRROR_KEY = "c4u2luipyybyn2ofveo7ocla6";
  @TempDir private Path temporary;
  private Path root;
  private Path home;

  @BeforeEach
  void selectTemporaryWorkspaceAndGradleUserHome() throws Exception {
    temporary = temporary.toRealPath();
    root = Files.createDirectory(temporary.resolve("learner"));
    home = Files.createDirectory(temporary.resolve("selected-cache"));
    wrapper(URL);
  }

  @Test
  void coldCacheFailsWithoutLaunchingTheWrapperOrCreatingCacheFiles() throws Exception {
    GradlePreflight.Unavailable result = failed(preflight().prepare(root));
    assertEquals(FailureCategory.MISSING_ARTIFACT, result.outcome().category());
    assertFalse(Files.exists(home.resolve("wrapper")));
    assertFalse(result.outcome().diagnostics().toString().contains(temporary.toString()));
    assertThrows(
        java.nio.file.NoSuchFileException.class, () -> GradleCacheWorker.verify(root, home));
  }

  @Test
  void exactUrlCacheKeyMarkerAndPayloadAreRequiredNotJustASimilarVersion() throws Exception {
    candidate(MIRROR_KEY);
    assertEquals(
        FailureCategory.MISSING_ARTIFACT, failed(preflight().prepare(root)).outcome().category());
    Path selected = candidate(KEY);
    assertEquals(
        home,
        assertInstanceOf(GradlePreflight.Ready.class, preflight().prepare(root)).gradleUserHome());
    GradleCacheWorker.verify(root, home);
    Files.delete(selected.resolve("gradle-9.7.1-bin.zip.ok"));
    assertEquals(
        FailureCategory.MISSING_ARTIFACT, failed(preflight().prepare(root)).outcome().category());
    assertTrue(Files.exists(home.resolve("wrapper/dists/gradle-9.7.1-bin/" + MIRROR_KEY)));
    assertThrows(
        java.nio.file.NoSuchFileException.class, () -> GradleCacheWorker.verify(root, home));
  }

  @Test
  void matchingMirrorUrlSelectsItsOwnExactCompetingCache() throws Exception {
    candidate(KEY);
    wrapper("https://mirror.example/distributions/gradle-9.7.1-bin.zip");
    assertEquals(
        FailureCategory.MISSING_ARTIFACT, failed(preflight().prepare(root)).outcome().category());
    candidate(MIRROR_KEY);
    assertInstanceOf(GradlePreflight.Ready.class, preflight().prepare(root));
    GradleCacheWorker.verify(root, home);
  }

  @Test
  void ambiguousPayloadOrLauncherNeverPermitsWrapperBootstrap() throws Exception {
    Path selected = candidate(KEY);
    Files.createDirectory(selected.resolve("second-distribution"));
    assertEquals(
        FailureCategory.INTERNAL_ERROR, failed(preflight().prepare(root)).outcome().category());
    assertThrows(java.io.IOException.class, () -> GradleCacheWorker.verify(root, home));
    Files.delete(selected.resolve("second-distribution"));
    Files.writeString(selected.resolve("gradle-9.7.1/lib/gradle-launcher-other.jar"), "extra");
    assertEquals(
        FailureCategory.INTERNAL_ERROR, failed(preflight().prepare(root)).outcome().category());
    assertThrows(java.io.IOException.class, () -> GradleCacheWorker.verify(root, home));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "gradle-9.7.1-bin.zip.ok",
        "gradle-9.7.1/lib/gradle-launcher-9.7.1.jar",
        "gradle-9.7.1/bin/gradle"
      })
  void symlinkedMarkerOrPayloadFailsWithoutReadingExternalBytes(String relative) throws Exception {
    Path selected = candidate(KEY);
    Path external = Files.writeString(temporary.resolve("sensitive-file"), "external secret");
    Path target = selected.resolve(relative);
    Files.delete(target);
    Files.createSymbolicLink(target, external);
    var result = failed(preflight().prepare(root));
    assertEquals(FailureCategory.INTERNAL_ERROR, result.outcome().category());
    assertFalse(result.outcome().diagnostics().toString().contains("external secret"));
    assertEquals("external secret", Files.readString(external));
    assertThrows(java.io.IOException.class, () -> GradleCacheWorker.verify(root, home));
  }

  @Test
  void symlinkedCacheHomeAndKeyDirectoryAreRejected() throws Exception {
    Path selected = candidate(KEY);
    Path moved = selected.resolveSibling("moved");
    Files.move(selected, moved);
    Files.createSymbolicLink(selected, moved);
    assertEquals(
        FailureCategory.INTERNAL_ERROR, failed(preflight().prepare(root)).outcome().category());
    Path link = temporary.resolve("linked-home");
    Files.createSymbolicLink(link, home);
    assertEquals(
        FailureCategory.INTERNAL_ERROR,
        failed(
                new CachedGradlePreflight(
                        new BoundedProcessRunner(), null, link.toString(), temporary.toString())
                    .prepare(root))
            .outcome()
            .category());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "distributionUrl=not a URI",
        "distributionUrl=https\\://host.invalid/../gradle.zip",
        "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\ndistributionUrl=https\\://other.invalid/gradle-9.7.1-bin.zip",
        "distributionBase=PROJECT\ndistributionUrl=https\\://host.invalid/gradle-9.7.1-bin.zip",
        "distributionUrl=https\\://host.invalid/gradle-9.7.1-bin.zip\ndistributionPath=../escape"
      })
  void malformedAmbiguousOrUnsupportedWrapperMetadataFailsSafely(String properties)
      throws Exception {
    Files.writeString(root.resolve("gradle/wrapper/gradle-wrapper.properties"), properties);
    assertEquals(
        FailureCategory.INTERNAL_ERROR, failed(preflight().prepare(root)).outcome().category());
    assertThrows(Exception.class, () -> GradleCacheWorker.verify(root, home));
  }

  @Test
  void markerAndLauncherMustBeWellFormedAndInspectionPreservesAllFixtureBytes() throws Exception {
    Path selected = candidate(KEY);
    Path marker = selected.resolve("gradle-9.7.1-bin.zip.ok");
    Files.writeString(marker, "invalid marker");
    assertThrows(java.io.IOException.class, () -> GradleCacheWorker.verify(root, home));
    assertEquals("invalid marker", Files.readString(marker));
    Files.write(marker, new byte[0]);
    Path launcher = selected.resolve("gradle-9.7.1/lib/gradle-launcher-9.7.1.jar");
    Files.writeString(launcher, "invalid archive");
    assertThrows(java.io.IOException.class, () -> GradleCacheWorker.verify(root, home));
    assertEquals("invalid archive", Files.readString(launcher));
    assertEquals(
        FailureCategory.INTERNAL_ERROR, failed(preflight().prepare(root)).outcome().category());
    candidate(KEY);
    byte[] original = Files.readAllBytes(launcher);
    GradleCacheWorker.verify(root, home);
    org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(launcher));
    assertEquals(0, Files.size(marker));
  }

  @Test
  void modernLauncherManifestSelectsOnlyItsExactRegularCliMainJar() throws Exception {
    Path selected = candidate(KEY);
    Path lib = selected.resolve("gradle-9.7.1/lib");
    try (var zip =
        new ZipOutputStream(Files.newOutputStream(lib.resolve("gradle-launcher-9.7.1.jar")))) {
      zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
      zip.write(
          "Manifest-Version: 1.0\nClass-Path: gradle-gradle-cli-main-9.7.1.jar\n\n"
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    try (var zip =
        new ZipOutputStream(
            Files.newOutputStream(lib.resolve("gradle-gradle-cli-main-9.7.1.jar")))) {
      zip.putNextEntry(new ZipEntry("org/gradle/launcher/GradleMain.class"));
      zip.write(new byte[] {1, 2, 3});
      zip.closeEntry();
    }
    assertInstanceOf(GradlePreflight.Ready.class, preflight().prepare(root));
    GradleCacheWorker.verify(root, home);
    Files.delete(lib.resolve("gradle-gradle-cli-main-9.7.1.jar"));
    assertEquals(
        FailureCategory.MISSING_ARTIFACT, failed(preflight().prepare(root)).outcome().category());
  }

  @Test
  void effectiveHomeUsesPropertyThenEnvironmentThenUserHomeAndPinsRelativeEnvironmentToWorkspace()
      throws Exception {
    candidate(KEY);
    assertEquals(
        home,
        assertInstanceOf(
                GradlePreflight.Ready.class,
                new CachedGradlePreflight(
                        new BoundedProcessRunner(), home.toString(), "ignored", "ignored")
                    .prepare(root))
            .gradleUserHome());
    assertEquals(
        home,
        assertInstanceOf(
                GradlePreflight.Ready.class,
                new CachedGradlePreflight(
                        new BoundedProcessRunner(), null, "../selected-cache", "ignored")
                    .prepare(root))
            .gradleUserHome());
    Path user = Files.createDirectory(temporary.resolve("user"));
    Files.move(home, user.resolve(".gradle"));
    assertEquals(
        user.resolve(".gradle"),
        assertInstanceOf(
                GradlePreflight.Ready.class,
                new CachedGradlePreflight(new BoundedProcessRunner(), null, null, user.toString())
                    .prepare(root))
            .gradleUserHome());
  }

  @Test
  void boundedCacheWorkerTimeoutInterruptionAndFailureStayTypedAndNeverExposeOutput() {
    var output = new ProcessResult.Output("secret /outside/path", "\u001b[31m", false, false);
    assertEquals(
        FailureCategory.TIMEOUT,
        failed(
                new CachedGradlePreflight(
                        request ->
                            new ProcessResult.TimedOut(output, ProcessResult.Cleanup.COMPLETE),
                        null,
                        home.toString(),
                        temporary.toString())
                    .prepare(root))
            .outcome()
            .category());
    var interrupted =
        failed(
            new CachedGradlePreflight(
                    request ->
                        new ProcessResult.Interrupted(output, ProcessResult.Cleanup.COMPLETE),
                    null,
                    home.toString(),
                    temporary.toString())
                .prepare(root));
    assertEquals(FailureCategory.INTERRUPTED, interrupted.outcome().category());
    assertFalse(interrupted.outcome().diagnostics().toString().contains("secret"));
  }

  @Test
  void incompleteCacheWorkerCleanupRemainsActionableWithoutRawOutput() {
    var result =
        failed(
            new CachedGradlePreflight(
                    request ->
                        new ProcessResult.TimedOut(
                            new ProcessResult.Output(
                                "secret /outside/path", "\u001b", false, false),
                            ProcessResult.Cleanup.INCOMPLETE),
                    null,
                    home.toString(),
                    temporary.toString())
                .prepare(root));
    assertEquals(FailureCategory.TIMEOUT, result.outcome().category());
    assertTrue(
        result.outcome().diagnostics().stream().anyMatch(d -> d.observed().contains("cleanup")));
    assertFalse(result.outcome().diagnostics().toString().contains("secret"));
  }

  @Test
  void bundledWrapperRecognizesTheRealInstalledOfflineGradlePayload() throws Exception {
    Path journey = Files.createDirectory(temporary.resolve("bundled-journey"));
    var catalog = new org.fruitandfaults.course.infra.ClasspathCourseCatalog("course");
    org.fruitandfaults.course.infra.LearnerJourneyFixture.disclose(
        journey, catalog.load().lessons().getFirst(), catalog);
    org.fruitandfaults.course.infra.LearnerJourneyFixture.applySolution(
        journey, "first-run", "src/main/java/org/fruitandfaults/game/Starter.java");
    assertEquals(
        0, org.fruitandfaults.course.infra.LearnerJourneyFixture.build(journey).exitCode());
    Path installed = journey.resolve(".gradle/fixture-user-home");
    GradleCacheWorker.verify(journey, installed);
    assertEquals(
        installed,
        assertInstanceOf(
                GradlePreflight.Ready.class,
                new CachedGradlePreflight(
                        new BoundedProcessRunner(),
                        null,
                        installed.toString(),
                        temporary.toString())
                    .prepare(journey))
            .gradleUserHome());
  }

  private CachedGradlePreflight preflight() {
    return new CachedGradlePreflight(
        new BoundedProcessRunner(), null, home.toString(), temporary.toString());
  }

  private static GradlePreflight.Unavailable failed(GradlePreflight.Preparation result) {
    return assertInstanceOf(GradlePreflight.Unavailable.class, result);
  }

  private void wrapper(String url) throws Exception {
    Path file = root.resolve("gradle/wrapper/gradle-wrapper.properties");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "distributionUrl=" + url.replace(":", "\\:") + "\n");
  }

  private Path candidate(String key) throws Exception {
    Path selected = home.resolve("wrapper/dists/gradle-9.7.1-bin/" + key);
    Path payload = selected.resolve("gradle-9.7.1");
    Files.createDirectories(payload.resolve("lib"));
    Files.createDirectories(payload.resolve("bin"));
    Files.writeString(payload.resolve("bin/gradle"), "installed script");
    Files.writeString(payload.resolve("bin/gradle.bat"), "installed batch script");
    Files.write(selected.resolve("gradle-9.7.1-bin.zip.ok"), new byte[0]);
    try (var zip =
        new ZipOutputStream(
            Files.newOutputStream(payload.resolve("lib/gradle-launcher-9.7.1.jar")))) {
      zip.putNextEntry(new ZipEntry("org/gradle/launcher/GradleMain.class"));
      zip.write(new byte[] {1, 2, 3});
      zip.closeEntry();
    }
    return selected;
  }
}
