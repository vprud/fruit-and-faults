package org.fruitandfaults.course.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.fruitandfaults.lesson.ReflectionAnswer;
import org.fruitandfaults.progress.application.ProgressRepository;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.fruitandfaults.validation.domain.FailureCategory;
import org.fruitandfaults.workspace.application.DiscloseLesson;
import org.fruitandfaults.workspace.infra.JacksonTransitionJournalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CancelledWorkspaceCommandsTest {
  @TempDir private Path temporary;

  @ParameterizedTest
  @CsvSource({
    "status,closed", "status,io", "status,cause", "status,flag",
    "hint,closed", "hint,io", "hint,cause", "hint,flag",
    "next,closed", "next,io", "next,cause", "next,flag"
  })
  void interruptedProgressReadsAreCancellationAndNeverWrite(String command, String kind)
      throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    Path state = fixture.root().resolve(".fruit-and-faults/progress.json");
    byte[] before = Files.readAllBytes(state);
    ProgressRepository cancelled =
        new ProgressRepository() {
          @Override
          public Optional<CourseProgress> load(Path root) throws IOException {
            if (kind.equals("flag")) Thread.currentThread().interrupt();
            throw switch (kind) {
              case "closed" -> new java.nio.channels.ClosedByInterruptException();
              case "io" -> new java.io.InterruptedIOException("PRIVATE");
              case "cause" -> new IOException(new java.io.InterruptedIOException("PRIVATE"));
              default -> new IOException("PRIVATE");
            };
          }

          @Override
          public void save(Path root, CourseProgress current) {
            throw new AssertionError("Cancellation must not write progress");
          }
        };
    try {
      FailureCategory category;
      String observed;
      switch (command) {
        case "status" -> {
          var result =
              (CourseStatus.Unavailable)
                  new ShowStatus(
                          fixture.catalog(),
                          cancelled,
                          fixture.manifests(),
                          fixture.files(),
                          fixture.git())
                      .execute(fixture.root());
          category = result.category();
          observed = result.diagnostic().observed();
        }
        case "hint" -> {
          var result =
              (HintResult.Unavailable)
                  new ShowHint(fixture.catalog(), cancelled).execute(fixture.root());
          category = result.category();
          observed = result.diagnostic().observed();
        }
        default -> {
          var journals = new JacksonTransitionJournalRepository(fixture.course());
          var result =
              (AdvanceResult.Unavailable)
                  new AdvanceLesson(
                          fixture.catalog(),
                          cancelled,
                          fixture.manifests(),
                          journals,
                          ignored -> {
                            throw new AssertionError("No validation after cancellation");
                          },
                          fixture.git(),
                          new DiscloseLesson(
                              fixture.catalog(),
                              fixture.files(),
                              fixture.manifests(),
                              cancelled,
                              journals))
                      .execute(
                          new AdvanceRequest(
                              fixture.root(),
                              Optional.of(new ReflectionAnswer("compile-before-tests")),
                              true));
          category = result.category();
          observed = result.diagnostic().observed();
        }
      }
      assertEquals(FailureCategory.INTERRUPTED, category);
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(observed.contains("interrupted"));
      assertFalse(observed.contains("unsafe"));
      assertFalse(observed.contains("PRIVATE"));
      Thread.interrupted();
      assertArrayEquals(before, Files.readAllBytes(state));
      assertFalse(Files.exists(fixture.root().resolve(".fruit-and-faults/transition.json")));
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void preexistingHintCancellationDoesNotPersistOrRevealAHint() throws IOException {
    var fixture = new CourseApplicationFixture(temporary);
    Path state = fixture.root().resolve(".fruit-and-faults/progress.json");
    byte[] before = Files.readAllBytes(state);
    try {
      Thread.currentThread().interrupt();
      var result =
          org.junit.jupiter.api.Assertions.assertInstanceOf(
              HintResult.Unavailable.class,
              new ShowHint(fixture.catalog(), fixture.progress()).execute(fixture.root()));
      assertEquals(FailureCategory.INTERRUPTED, result.category());
      assertTrue(Thread.currentThread().isInterrupted());
      Thread.interrupted();
      assertArrayEquals(before, Files.readAllBytes(state));
    } finally {
      Thread.interrupted();
    }
  }
}
