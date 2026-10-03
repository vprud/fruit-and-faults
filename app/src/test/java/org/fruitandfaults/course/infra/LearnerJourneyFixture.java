package org.fruitandfaults.course.infra;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
            "-Dorg.gradle.java.installations.auto-download=false",
            "-Dorg.gradle.java.installations.paths=" + System.getProperty("java.home")));
    return run(workspace, command, Duration.ofSeconds(90));
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
      BuildResult result = run(workspace, command, Duration.ofSeconds(10));
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

  private static BuildResult run(Path workspace, List<String> command, Duration timeout)
      throws Exception {
    ProcessBuilder builder =
        new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true);
    builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
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
