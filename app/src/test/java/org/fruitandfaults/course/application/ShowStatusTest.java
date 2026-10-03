package org.fruitandfaults.course.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.fruitandfaults.course.domain.Course;
import org.fruitandfaults.course.infra.LearnerJourneyFixture;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.fruitandfaults.progress.domain.ProgressTransition;
import org.fruitandfaults.progress.infra.AtomicProgressRepository;
import org.fruitandfaults.progress.infra.JacksonProgressCodec;
import org.fruitandfaults.validation.domain.FailureCategory;
import org.fruitandfaults.workspace.application.WorkspaceFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShowStatusTest {
  @TempDir private Path temporary;

  @Test
  void reportsActiveFactsAndOnlyOneNextCommandWithoutFutureLessonDetails() throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    fixture
        .progress()
        .save(
            fixture.root(),
            CourseProgress.opening(fixture.course(), null)
                .revealHint(fixture.course().lessonOrder().getFirst()));
    byte[] before = Files.readAllBytes(fixture.root().resolve(".fruit-and-faults/progress.json"));
    CourseStatus.Ready result =
        assertInstanceOf(CourseStatus.Ready.class, fixture.status().execute(fixture.root()));
    assertEquals("first-run", result.activeLesson().orElseThrow().id().value());
    assertEquals(
        fixture.course().lessons().getFirst().goal(), result.activeLesson().orElseThrow().goal());
    assertEquals(1, result.activeLesson().orElseThrow().hintLevel());
    assertEquals(10, result.artifacts().size());
    assertTrue(
        result.artifacts().stream()
            .allMatch(artifact -> artifact.presence() == WorkspaceFiles.Presence.PRESENT));
    assertTrue(result.git().headRevision().isEmpty());
    assertTrue(result.advice().stream().anyMatch(value -> value.contains("origin")));
    assertTrue(result.advice().stream().anyMatch(value -> value.contains("upstream")));
    assertEquals("fruit-and-faults check", result.nextCommand());
    assertFalse(result.toString().contains(fixture.course().lessons().get(1).goal()));
    assertFalse(result.toString().contains(fixture.course().lessons().get(1).hints().getFirst()));
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        before, Files.readAllBytes(fixture.root().resolve(".fruit-and-faults/progress.json")));
  }

  @Test
  void reportsMissingArtifactWithoutExecutingBuildOrReadingSourceContents() throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    Files.delete(fixture.root().resolve("src/main/java/org/fruitandfaults/game/Starter.java"));
    // A file far above the hashing/read limit is still a cheap presence observation.
    try (var channel =
        java.nio.channels.FileChannel.open(
            fixture.root().resolve("build.gradle.kts"), java.nio.file.StandardOpenOption.WRITE)) {
      channel.position(32_000_000);
      channel.write(java.nio.ByteBuffer.wrap(new byte[] {1}));
    }
    CourseStatus.Ready result =
        assertInstanceOf(CourseStatus.Ready.class, fixture.status().execute(fixture.root()));
    assertTrue(
        result.artifacts().stream()
            .anyMatch(
                artifact ->
                    artifact.path().value().endsWith("Starter.java")
                        && artifact.presence() == WorkspaceFiles.Presence.MISSING));
    assertEquals("fruit-and-faults check", result.nextCommand());
  }

  @ParameterizedTest
  @ValueSource(strings = {"progress", "manifest", "git", "ownership"})
  void returnsSafeWorkspaceDiagnosticAndPreservesMalformedState(String damaged) throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    Path state =
        switch (damaged) {
          case "progress" -> fixture.root().resolve(".fruit-and-faults/progress.json");
          case "manifest", "ownership" ->
              fixture.root().resolve(".fruit-and-faults/managed-files.json");
          case "git" -> fixture.root().resolve(".git/HEAD");
          default -> throw new IllegalArgumentException();
        };
    Files.writeString(
        state,
        damaged.equals("ownership")
            ? "{\"formatVersion\":1,\"files\":[]}"
            : "SECRET /private/foreign \u001b[31m");
    byte[] before = Files.readAllBytes(state);
    CourseStatus.Unavailable failed =
        assertInstanceOf(CourseStatus.Unavailable.class, fixture.status().execute(fixture.root()));
    assertEquals(FailureCategory.WORKSPACE_CONFLICT, failed.category());
    assertFalse(failed.toString().contains("SECRET"));
    org.junit.jupiter.api.Assertions.assertArrayEquals(before, Files.readAllBytes(state));
  }

  @Test
  void reportsAppendedContinuationAndFinalMetadataCommitAdvice() throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    Course earlier =
        new Course(
            fixture.course().id(),
            1,
            fixture.course().title(),
            fixture.course().lessonOrder().subList(0, 1),
            fixture.course().lessons().subList(0, 1));
    CourseProgress completed =
        ((ProgressTransition.CourseComplete)
                CourseProgress.opening(earlier, null)
                    .advance(
                        earlier.lessonOrder().getFirst(),
                        earlier.lessons().getFirst().question().correctOptionId(),
                        null))
            .progress();
    // Save the valid earlier snapshot through its matching codec, not the installed newer codec.
    Files.delete(fixture.root().resolve(".fruit-and-faults/progress.json"));
    var earlierRepository = new AtomicProgressRepository(new JacksonProgressCodec(earlier));
    earlierRepository.save(fixture.root(), completed);
    Course newer =
        new Course(
            fixture.course().id(),
            2,
            fixture.course().title(),
            fixture.course().lessonOrder(),
            fixture.course().lessons());
    CourseStatus.Ready updated =
        assertInstanceOf(
            CourseStatus.Ready.class,
            new ShowStatus(
                    () -> newer,
                    earlierRepository,
                    fixture.manifests(),
                    fixture.files(),
                    fixture.git())
                .execute(fixture.root()));
    assertTrue(updated.continuationAvailable());
    assertEquals("fruit-and-faults next", updated.nextCommand());
    assertTrue(updated.activeLesson().isEmpty());
    CourseStatus.Ready finished =
        assertInstanceOf(
            CourseStatus.Ready.class,
            new ShowStatus(
                    () -> earlier,
                    earlierRepository,
                    fixture.manifests(),
                    fixture.files(),
                    fixture.git())
                .execute(fixture.root()));
    assertFalse(finished.continuationAvailable());
    assertTrue(
        finished.advice().stream().anyMatch(value -> value.contains("final metadata commit")));
    assertEquals("git status", finished.nextCommand());
    LearnerJourneyFixture.runGit(fixture.root(), "add", ".");
    LearnerJourneyFixture.runGit(
        fixture.root(),
        "-c",
        "user.name=Learner",
        "-c",
        "user.email=learner@example.invalid",
        "commit",
        "-qm",
        "test: final metadata");
    CourseStatus.Ready clean =
        assertInstanceOf(
            CourseStatus.Ready.class,
            new ShowStatus(
                    () -> earlier,
                    earlierRepository,
                    fixture.manifests(),
                    fixture.files(),
                    fixture.git())
                .execute(fixture.root()));
    assertEquals("fruit-and-faults list", clean.nextCommand());
  }

  @Test
  void installedCatalogFailureIsInternalAndDoesNotExposeContentOrCause() throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    CourseStatus.Unavailable failed =
        assertInstanceOf(
            CourseStatus.Unavailable.class,
            new ShowStatus(
                    () -> {
                      throw new IllegalArgumentException("SECRET");
                    },
                    fixture.progress(),
                    fixture.manifests(),
                    fixture.files(),
                    fixture.git())
                .execute(fixture.root()));
    assertEquals(FailureCategory.INTERNAL_ERROR, failed.category());
    assertFalse(failed.toString().contains("SECRET"));
  }
}
