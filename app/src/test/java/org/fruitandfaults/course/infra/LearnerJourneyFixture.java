package org.fruitandfaults.course.infra;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.fruitandfaults.course.application.CourseAssets;
import org.fruitandfaults.course.domain.Lesson;

/** Materializes genuine course resources and applies solutions available only to tests. */
public final class LearnerJourneyFixture {
  private static final int MAX_OUTPUT_BYTES = 262_144;

  private LearnerJourneyFixture() {}

  /**
   * Discloses only the selected lesson, refusing to overwrite existing paths.
   *
   * @param workspace isolated test directory
   * @param lesson lesson being opened
   * @param assets installed course bytes
   * @throws IOException if fixture materialization fails
   */
  public static void disclose(Path workspace, Lesson lesson, CourseAssets assets)
      throws IOException {
    for (var asset : lesson.assets()) {
      Path target = workspace.resolve(asset.relativePath());
      Files.createDirectories(target.getParent());
      Files.write(target, assets.load(asset), java.nio.file.StandardOpenOption.CREATE_NEW);
    }
  }

  /**
   * Applies a passing learner edit from test resources, never the installed course.
   *
   * @param workspace isolated test directory
   * @param lessonId solved lesson ID
   * @param relativePath learner file to edit
   * @throws IOException if the fixture solution cannot be read or written
   */
  public static void applySolution(Path workspace, String lessonId, String relativePath)
      throws IOException {
    String resource = "journeys/phase-a/" + lessonId + "/" + relativePath;
    try (InputStream input =
        Objects.requireNonNull(
            LearnerJourneyFixture.class.getClassLoader().getResourceAsStream(resource), resource)) {
      Path target = workspace.resolve(relativePath);
      if (!Files.isRegularFile(target)) {
        throw new IOException(
            "The learner file must be disclosed before applying a solution: " + relativePath);
      }
      Files.write(target, input.readAllBytes());
    }
  }

  /**
   * Executes the learner wrapper offline using the test JVM's Java 26 installation.
   *
   * @param workspace isolated test directory
   * @return bounded process result
   * @throws Exception if the owned process fails to launch, is interrupted, or exceeds its deadline
   */
  public static BuildResult build(Path workspace) throws Exception {
    return build(workspace, cachedGradleHome());
  }

  static BuildResult build(Path workspace, Path cacheSource) throws Exception {
    Path gradleHome = prepareGradleHome(workspace, cacheSource);
    List<String> command = new ArrayList<>();
    if (System.getProperty("os.name").startsWith("Windows")) {
      command.addAll(List.of("cmd", "/d", "/c", "gradlew.bat"));
    } else {
      command.addAll(List.of("sh", "./gradlew"));
    }
    command.addAll(
        List.of(
            "test",
            "--offline",
            "--no-daemon",
            "--console=plain",
            "--max-workers=1",
            "--gradle-user-home=" + gradleHome,
            "-Dorg.gradle.java.installations.auto-download=false",
            "-Dorg.gradle.java.installations.paths=" + System.getProperty("java.home")));
    return run(
        workspace,
        command,
        Duration.ofSeconds(90),
        Map.of("GRADLE_USER_HOME", gradleHome.toString()));
  }

  /**
   * Runs a bounded local Git command in the test project.
   *
   * @param workspace isolated test directory
   * @param arguments Git arguments
   * @return successful command output
   * @throws IOException if Git cannot run or returns a failure
   */
  public static String runGit(Path workspace, String... arguments) throws IOException {
    List<String> command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(arguments));
    try {
      BuildResult result = run(workspace, command, Duration.ofSeconds(10), Map.of());
      if (result.exitCode() != 0) {
        throw new IOException(result.output());
      }
      return result.output();
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IOException("Fixture Git command interrupted", failure);
    } catch (Exception failure) {
      throw new IOException("Fixture Git command failed", failure);
    }
  }

  /**
   * Counts passing visible tests from actual JUnit XML output.
   *
   * @param workspace isolated test directory
   * @return total executed passing tests
   * @throws Exception if a report is invalid or contains failures
   */
  public static int passingTestCount(Path workspace) throws Exception {
    var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    int count = 0;
    try (var reports = Files.list(workspace.resolve("build/test-results/test"))) {
      for (Path report :
          reports.filter(path -> path.getFileName().toString().endsWith(".xml")).toList()) {
        var suite = factory.newDocumentBuilder().parse(report.toFile()).getDocumentElement();
        if (!suite.getAttribute("failures").equals("0")
            || !suite.getAttribute("errors").equals("0")
            || !suite.getAttribute("skipped").equals("0")) {
          throw new IOException(
              "Expected every cumulative visible test to pass: " + report.getFileName());
        }
        count += Integer.parseInt(suite.getAttribute("tests"));
      }
    }
    return count;
  }

  private static Path prepareGradleHome(Path workspace, Path source) throws IOException {
    Path isolated = workspace.toRealPath().resolve(".gradle/fixture-user-home");
    Properties wrapper = new Properties();
    try (InputStream input =
        Files.newInputStream(workspace.resolve("gradle/wrapper/gradle-wrapper.properties"))) {
      wrapper.load(input);
    }
    URI distributionUrl = URI.create(wrapper.getProperty("distributionUrl"));
    if (Files.isRegularFile(isolated.resolve("init.gradle"), LinkOption.NOFOLLOW_LINKS)) {
      cachedDistribution(isolated, distributionUrl);
      return isolated;
    }
    String archive =
        Path.of(URI.create(wrapper.getProperty("distributionUrl")).getPath())
            .getFileName()
            .toString();
    String distribution = archive.substring(0, archive.length() - ".zip".length());
    Path cachedDistribution = cachedDistribution(source, distributionUrl);
    Path targetDistribution =
        isolated
            .resolve("wrapper/dists")
            .resolve(distribution)
            .resolve(cachedDistribution.getFileName());
    Files.createDirectories(targetDistribution);
    String payload = distribution.replaceFirst("-(bin|all)$", "");
    copyPayload(cachedDistribution.resolve(payload), targetDistribution.resolve(payload));
    Files.copy(
        cachedDistribution.resolve(archive + ".ok"), targetDistribution.resolve(archive + ".ok"));
    Path repository = isolated.resolve("offline-repository");
    for (List<String> module :
        List.of(
            List.of("org.junit", "junit-bom", "6.0.1"),
            List.of("org.junit.jupiter", "junit-jupiter", "6.0.1"),
            List.of("org.junit.jupiter", "junit-jupiter-api", "6.0.1"),
            List.of("org.junit.jupiter", "junit-jupiter-params", "6.0.1"),
            List.of("org.junit.jupiter", "junit-jupiter-engine", "6.0.1"),
            List.of("org.junit.platform", "junit-platform-commons", "6.0.1"),
            List.of("org.junit.platform", "junit-platform-engine", "6.0.1"),
            List.of("org.junit.platform", "junit-platform-launcher", "6.0.1"),
            List.of("org.opentest4j", "opentest4j", "1.3.0"),
            List.of("org.apiguardian", "apiguardian-api", "1.1.2"),
            List.of("org.jspecify", "jspecify", "1.0.0"))) {
      String group = module.get(0);
      String artifact = module.get(1);
      String version = module.get(2);
      Path cached =
          source
              .resolve("caches/modules-2/files-2.1")
              .resolve(group)
              .resolve(artifact)
              .resolve(version);
      Path target = repository.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
      Files.createDirectories(target);
      Set<String> filenames =
          Set.of(
              artifact + "-" + version + ".pom",
              artifact + "-" + version + ".module",
              artifact + "-" + version + ".jar");
      try (var artifacts = Files.walk(cached)) {
        for (Path file :
            artifacts
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> filenames.contains(path.getFileName().toString()))
                .toList()) {
          Files.copy(file, target.resolve(file.getFileName()));
        }
      }
    }
    Files.writeString(
        isolated.resolve("init.gradle"),
        """
        def expected = new File(System.getenv('GRADLE_USER_HOME')).canonicalFile
        if (gradle.gradleUserHomeDir.canonicalFile != expected) {
            throw new GradleException('Fixture Gradle user home must remain isolated.')
        }
        println('FIXTURE_GRADLE_HOME=' + expected)
        gradle.beforeProject { project ->
            project.afterEvaluate {
                project.repositories.clear()
                project.repositories.maven {
                    url = new File(gradle.gradleUserHomeDir, 'offline-repository').toURI()
                }
            }
        }
        """);
    return isolated;
  }

  static Path cachedDistribution(Path source, URI distributionUrl) throws IOException {
    String archive = Path.of(distributionUrl.getPath()).getFileName().toString();
    String distribution = archive.substring(0, archive.length() - ".zip".length());
    String hash;
    try {
      // Match Gradle Download.safeUri and PathAssembler; MD5 identifies a cache, not trust.
      URI safe =
          new URI(
              distributionUrl.getScheme(),
              null,
              distributionUrl.getHost(),
              distributionUrl.getPort(),
              distributionUrl.getPath(),
              distributionUrl.getQuery(),
              distributionUrl.getFragment());
      byte[] digest =
          MessageDigest.getInstance("MD5")
              .digest(safe.toASCIIString().getBytes(StandardCharsets.UTF_8));
      hash = new BigInteger(1, digest).toString(36);
    } catch (URISyntaxException | NoSuchAlgorithmException failure) {
      throw new IOException(
          "Cannot derive the learner wrapper's distribution cache identity.", failure);
    }
    Path selected = source.resolve("wrapper/dists").resolve(distribution).resolve(hash);
    if (!Files.isDirectory(selected, LinkOption.NOFOLLOW_LINKS)
        || !Files.isRegularFile(selected.resolve(archive + ".ok"), LinkOption.NOFOLLOW_LINKS)
        || !Files.isDirectory(
            selected.resolve(distribution.replaceFirst("-(bin|all)$", "")),
            LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException(
          "Expected the exact cached Gradle distribution "
              + hash
              + " with its completion marker and payload before launching the offline learner wrapper.");
    }
    return selected;
  }

  private static Path cachedGradleHome() {
    String configured = System.getenv("GRADLE_USER_HOME");
    return configured == null
        ? Path.of(System.getProperty("user.home"), ".gradle")
        : Path.of(configured);
  }

  private static void copyPayload(Path source, Path target) throws IOException {
    try (var paths = Files.walk(source)) {
      for (Path path : paths.toList()) {
        if (Files.isSymbolicLink(path)) {
          throw new IOException("Offline fixture payload must not contain symbolic links.");
        }
        Path destination = target.resolve(source.relativize(path));
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
          Files.createDirectories(destination);
        } else {
          Files.copy(path, destination);
        }
      }
    }
  }

  private static BuildResult run(
      Path workspace, List<String> command, Duration timeout, Map<String, String> environment)
      throws Exception {
    ProcessBuilder builder =
        new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true);
    builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
    builder.environment().putAll(environment);
    if (environment.containsKey("GRADLE_USER_HOME")) {
      for (String option :
          List.of("JAVA_OPTS", "GRADLE_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS")) {
        builder.environment().remove(option);
      }
    }
    Process process = builder.start();
    CompletableFuture<String> output = new CompletableFuture<>();
    Thread reader =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (InputStream input = process.getInputStream();
                      ByteArrayOutputStream captured = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                      captured.write(buffer, 0, Math.min(read, MAX_OUTPUT_BYTES - captured.size()));
                    }
                    output.complete(captured.toString(StandardCharsets.UTF_8));
                  } catch (IOException failure) {
                    output.completeExceptionally(failure);
                  }
                });
    try {
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new TimeoutException("Learner fixture process exceeded " + timeout);
      }
      return new BuildResult(process.exitValue(), output.get(5, TimeUnit.SECONDS));
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw failure;
    } finally {
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      if (process.isAlive()) {
        process.destroyForcibly();
      }
      process.getInputStream().close();
      reader.interrupt();
    }
  }

  /**
   * Bounded output and termination code of a fixture-owned process.
   *
   * @param exitCode process exit code
   * @param output captured combined stdout/stderr
   */
  public record BuildResult(int exitCode, String output) {}
}
