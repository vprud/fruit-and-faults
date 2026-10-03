package org.fruitandfaults.progress.infra;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import org.fruitandfaults.course.infra.ClasspathCourseCatalog;
import org.fruitandfaults.progress.application.ProgressReadException;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AtomicProgressRepositoryTest {
  @TempDir private Path root;
  private final JacksonProgressCodec codec =
      new JacksonProgressCodec(new ClasspathCourseCatalog("course").load());
  private final CourseProgress opening =
      CourseProgress.opening(new ClasspathCourseCatalog("course").load(), null);

  @BeforeEach
  void resolvePlatformTemporaryDirectoryAlias() throws IOException {
    root = root.toRealPath();
  }

  @Test
  void missingProgressIsEmptyAndSavingRoundTripsRepeatedly() throws IOException {
    AtomicProgressRepository repository = new AtomicProgressRepository(codec);
    assertEquals(Optional.empty(), repository.load(root));
    assertTrue(Files.notExists(root.resolve(".fruit-and-faults")));
    repository.save(root, opening);
    repository.save(root.resolve("unused/.."), opening);
    assertEquals(Optional.of(opening), repository.load(root));
    assertOnlyProgressFile();
  }

  @Test
  void writeFailurePreservesLastValidFileAndCleansPartialTemporaryFile() throws IOException {
    new AtomicProgressRepository(codec).save(root, opening);
    byte[] before = Files.readAllBytes(stateFile());
    AtomicProgressRepository repository =
        new AtomicProgressRepository(
            codec,
            (temporary, bytes) -> {
              assertEquals(stateFile().getParent(), temporary.getParent());
              Files.writeString(temporary, "partial");
              throw new IOException("injected partial write");
            },
            (source, target, atomic) -> {
              throw new AssertionError("A failed write must not replace state");
            });
    assertThrows(IOException.class, () -> repository.save(root, opening));
    assertArrayEquals(before, Files.readAllBytes(stateFile()));
    assertOnlyProgressFile();
  }

  @Test
  void moveFailurePreservesLastValidFileAndCleansOwnedTemporaryFile() throws IOException {
    new AtomicProgressRepository(codec).save(root, opening);
    byte[] before = Files.readAllBytes(stateFile());
    Files.writeString(root.resolve(".fruit-and-faults/unrelated.tmp"), "learner content");
    AtomicProgressRepository repository =
        new AtomicProgressRepository(
            codec,
            (temporary, bytes) -> Files.write(temporary, bytes),
            (temporary, target, atomic) -> {
              assertEquals(target.getParent(), temporary.getParent());
              assertEquals(opening, codec.decode(Files.readAllBytes(temporary)));
              assertArrayEquals(before, Files.readAllBytes(target));
              throw new IOException("injected move failure");
            });
    assertThrows(IOException.class, () -> repository.save(root, opening));
    assertArrayEquals(before, Files.readAllBytes(stateFile()));
    assertEquals(
        "learner content", Files.readString(root.resolve(".fruit-and-faults/unrelated.tmp")));
    try (var children = Files.list(stateFile().getParent())) {
      assertEquals(2, children.count());
    }
  }

  @Test
  void unsupportedAtomicMoveFallsBackOnlyAfterCompleteWrite() throws IOException {
    new AtomicProgressRepository(codec).save(root, opening);
    CourseProgress updated =
        opening.revealHint(new org.fruitandfaults.course.domain.LessonId("first-run"));
    AtomicProgressRepository repository =
        new AtomicProgressRepository(
            codec,
            (temporary, bytes) -> Files.write(temporary, bytes),
            (temporary, target, atomic) -> {
              assertEquals(updated, codec.decode(Files.readAllBytes(temporary)));
              assertEquals(Optional.of(opening), new AtomicProgressRepository(codec).load(root));
              if (atomic) {
                throw new AtomicMoveNotSupportedException(
                    temporary.toString(), target.toString(), "injected unsupported atomic move");
              }
              Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            });
    repository.save(root, updated);
    assertEquals(Optional.of(updated), repository.load(root));
    assertOnlyProgressFile();
  }

  @ParameterizedTest
  @ValueSource(strings = {"{", "{\"formatVersion\":2}", "{\"formatVersion\":0}"})
  void unreadableProgressIsNeverRewritten(String json) throws IOException {
    Files.createDirectory(root.resolve(".fruit-and-faults"));
    byte[] before = json.getBytes(StandardCharsets.UTF_8);
    Files.write(stateFile(), before);
    AtomicProgressRepository repository = new AtomicProgressRepository(codec);
    assertThrows(ProgressReadException.class, () -> repository.load(root));
    assertThrows(ProgressReadException.class, () -> repository.save(root, opening));
    assertArrayEquals(before, Files.readAllBytes(stateFile()));
    assertOnlyProgressFile();
  }

  @Test
  void stateDirectoryOrFileSymlinksCannotEscapeWorkspace() throws IOException {
    Path outside = Files.createDirectory(root.resolve("outside"));
    Path workspace = Files.createDirectory(root.resolve("workspace"));
    Path metadata = workspace.resolve(".fruit-and-faults");
    Files.createSymbolicLink(metadata, outside);
    AtomicProgressRepository repository = new AtomicProgressRepository(codec);
    assertThrows(IOException.class, () -> repository.load(workspace));
    assertThrows(IOException.class, () -> repository.save(workspace, opening));
    assertTrue(Files.notExists(outside.resolve("progress.json")));
    Files.delete(metadata);
    Files.createDirectory(metadata);
    Path outsideFile = Files.writeString(outside.resolve("progress.json"), "sentinel");
    Files.createSymbolicLink(metadata.resolve("progress.json"), outsideFile);
    assertThrows(IOException.class, () -> repository.load(workspace));
    assertThrows(IOException.class, () -> repository.save(workspace, opening));
    assertEquals("sentinel", Files.readString(outsideFile));
    Path linkedRoot = root.resolve("linked-workspace");
    Files.createSymbolicLink(linkedRoot, workspace);
    assertThrows(IOException.class, () -> repository.load(linkedRoot));
    assertThrows(IOException.class, () -> repository.save(linkedRoot, opening));
  }

  @Test
  void oversizedAndNonRegularStateFailWithoutReplacement() throws IOException {
    Files.createDirectory(root.resolve(".fruit-and-faults"));
    Files.writeString(stateFile(), " ".repeat(JacksonProgressCodec.MAX_DOCUMENT_BYTES + 1));
    AtomicProgressRepository repository = new AtomicProgressRepository(codec);
    assertThrows(ProgressReadException.class, () -> repository.load(root));
    assertThrows(ProgressReadException.class, () -> repository.save(root, opening));
    assertEquals(JacksonProgressCodec.MAX_DOCUMENT_BYTES + 1L, Files.size(stateFile()));
    Files.delete(stateFile());
    Files.createDirectory(stateFile());
    assertThrows(IOException.class, () -> repository.load(root));
    assertThrows(IOException.class, () -> repository.save(root, opening));
  }

  private Path stateFile() {
    return root.resolve(".fruit-and-faults/progress.json");
  }

  private void assertOnlyProgressFile() throws IOException {
    try (var children = Files.list(stateFile().getParent())) {
      assertEquals(java.util.List.of(stateFile()), children.toList());
    }
  }
}
