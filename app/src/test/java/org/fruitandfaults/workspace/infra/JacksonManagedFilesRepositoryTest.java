package org.fruitandfaults.workspace.infra;

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
import java.util.List;
import java.util.Optional;

import org.fruitandfaults.course.domain.AssetId;
import org.fruitandfaults.course.domain.AssetPolicy;
import org.fruitandfaults.course.domain.LessonId;
import org.fruitandfaults.workspace.application.ManagedFilesReadException;
import org.fruitandfaults.workspace.domain.ManagedFile;
import org.fruitandfaults.workspace.domain.ManagedFiles;
import org.fruitandfaults.workspace.domain.WorkspacePath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JacksonManagedFilesRepositoryTest {
  private static final String HASH = "a".repeat(64);
  private static final String ENTRY =
      "{\"path\":\"src/Game.java\",\"assetId\":\"game\",\"sha256\":\""
          + HASH
          + "\",\"lessonId\":\"first-run\",\"policy\":\"LEARNER_SCAFFOLD\"}";
  private static final String VALID = "{\"formatVersion\":1,\"files\":[" + ENTRY + "]}";
  @TempDir private Path root;
  private final JacksonManagedFilesRepository repository = new JacksonManagedFilesRepository();

  @BeforeEach
  void resolveTemporaryDirectoryAlias() throws IOException {
    root = root.toRealPath();
  }

  @Test
  void missingManifestIsEmptyWithoutCreatingMetadata() throws IOException {
    assertEquals(Optional.empty(), repository.load(root));
    assertTrue(Files.notExists(root.resolve(".fruit-and-faults")));
    repository.save(root, ManagedFiles.empty());
    assertEquals(Optional.of(ManagedFiles.empty()), repository.load(root));
  }

  @Test
  void versionOnePersistsExactOwnershipPathsHashesLessonsAndPolicies() throws IOException {
    ManagedFiles managed =
        new ManagedFiles(
            List.of(
                file("src/моя игра/Check.java", "check", AssetPolicy.IMMUTABLE_CHECK),
                file("src/Test.java", "test", AssetPolicy.EDITABLE_TEMPLATE),
                file("src/Game.java", "game", AssetPolicy.LEARNER_SCAFFOLD)));
    repository.save(root, managed);
    repository.save(root.resolve("unused/.."), managed);
    assertEquals(Optional.of(managed), repository.load(root));
    String json = Files.readString(stateFile());
    assertTrue(json.contains("\"formatVersion\" : 1"));
    assertTrue(json.contains("IMMUTABLE_CHECK"));
    assertTrue(json.contains("EDITABLE_TEMPLATE"));
    assertTrue(json.contains("LEARNER_SCAFFOLD"));
    assertEquals(List.of(stateFile()), children(stateDirectory()));
  }

  @Test
  void validLiteralManifestIsValidatedBeforeDomainConstruction() throws IOException {
    writeJson(VALID);
    assertEquals(
        Optional.of(
            new ManagedFiles(List.of(file("src/Game.java", "game", AssetPolicy.LEARNER_SCAFFOLD)))),
        repository.load(root));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "{",
        "[]",
        "null",
        "{}",
        "{\"formatVersion\":1}",
        "{\"formatVersion\":\"1\",\"files\":[]}",
        "{\"formatVersion\":1.0,\"files\":[]}",
        "{\"formatVersion\":1,\"files\":null}",
        "{\"formatVersion\":1,\"files\":[],\"extra\":true}",
        "{\"formatVersion\":1,\"formatVersion\":1,\"files\":[]}",
        "{\"formatVersion\":1,\"files\":[]} {}"
      })
  void malformedManifestRemainsUntouchedOnLoadAndSave(String json) throws IOException {
    assertRejectedAndPreserved(json, ManagedFilesReadException.Reason.MALFORMED);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"{\"formatVersion\":2}", "{\"formatVersion\":0}", "{\"formatVersion\":-1}"})
  void unsupportedVersionIsReportedBeforeInterpretingOtherFields(String json) throws IOException {
    assertRejectedAndPreserved(json, ManagedFilesReadException.Reason.UNSUPPORTED_FORMAT);
  }

  @Test
  void normalizedUniquePathsAndExactPoliciesAreRequired() throws IOException {
    assertRejectedAndPreserved(
        VALID.replace("src/Game.java", "src/../Game.java"),
        ManagedFilesReadException.Reason.INVALID_STATE);
    assertRejectedAndPreserved(
        VALID.replace("src/Game.java", "C:/Game.java"),
        ManagedFilesReadException.Reason.INVALID_STATE);
    assertRejectedAndPreserved(
        VALID.replace("src/Game.java", "/Game.java"),
        ManagedFilesReadException.Reason.INVALID_STATE);
    assertRejectedAndPreserved(
        VALID.replace("[" + ENTRY + "]", "[" + ENTRY + "," + ENTRY + "]"),
        ManagedFilesReadException.Reason.INVALID_STATE);
    assertRejectedAndPreserved(
        VALID.replace("LEARNER_SCAFFOLD", "learner-owned"),
        ManagedFilesReadException.Reason.INVALID_STATE);
    assertRejectedAndPreserved(
        VALID.replace(HASH, "bad-hash"), ManagedFilesReadException.Reason.INVALID_STATE);
    assertRejectedAndPreserved(
        VALID.replace("\"game\"", "\"Bad ID\""), ManagedFilesReadException.Reason.INVALID_STATE);
    assertRejectedAndPreserved(
        VALID.replace("first-run", ""), ManagedFilesReadException.Reason.INVALID_STATE);
  }

  @Test
  void missingUnknownDuplicateAndWrongTypedEntryFieldsAreMalformed() throws IOException {
    assertRejectedAndPreserved(
        VALID.replace("\"path\":\"src/Game.java\",", ""),
        ManagedFilesReadException.Reason.MALFORMED);
    assertRejectedAndPreserved(
        VALID.replace("\"assetId\":\"game\"", "\"assetId\":null"),
        ManagedFilesReadException.Reason.MALFORMED);
    assertRejectedAndPreserved(
        VALID.replace("\"policy\":\"LEARNER_SCAFFOLD\"", "\"policy\":false"),
        ManagedFilesReadException.Reason.MALFORMED);
    assertRejectedAndPreserved(
        VALID.replace("\"path\":", "\"extra\":0,\"path\":"),
        ManagedFilesReadException.Reason.MALFORMED);
    assertRejectedAndPreserved(
        VALID.replace("\"path\":", "\"path\":\"other\",\"path\":"),
        ManagedFilesReadException.Reason.MALFORMED);
    assertRejectedAndPreserved(
        "{\"formatVersion\":1,\"files\":[false]}", ManagedFilesReadException.Reason.MALFORMED);
  }

  @Test
  void writeAndMoveFailuresPreserveLastValidManifestAndUnrelatedFiles() throws IOException {
    writeJson(VALID);
    byte[] before = Files.readAllBytes(stateFile());
    Files.writeString(stateDirectory().resolve("unrelated.tmp"), "learner work");
    JacksonManagedFilesRepository partial =
        new JacksonManagedFilesRepository(
            (temporary, bytes) -> {
              assertEquals(stateDirectory(), temporary.getParent());
              Files.writeString(temporary, "partial");
              throw new IOException("injected partial write");
            },
            (temporary, target, atomic) -> {
              throw new AssertionError("Partial write cannot replace state");
            });
    assertThrows(IOException.class, () -> partial.save(root, ManagedFiles.empty()));
    assertArrayEquals(before, Files.readAllBytes(stateFile()));
    JacksonManagedFilesRepository failedMove =
        new JacksonManagedFilesRepository(
            (temporary, bytes) -> Files.write(temporary, bytes),
            (temporary, target, atomic) -> {
              assertEquals(target.getParent(), temporary.getParent());
              assertTrue(Files.readString(temporary).contains("\"files\" : [ ]"));
              throw new IOException("injected move failure");
            });
    assertThrows(IOException.class, () -> failedMove.save(root, ManagedFiles.empty()));
    assertArrayEquals(before, Files.readAllBytes(stateFile()));
    assertEquals(2, children(stateDirectory()).size());
    assertEquals("learner work", Files.readString(stateDirectory().resolve("unrelated.tmp")));
  }

  @Test
  void unsupportedAtomicMoveFallsBackOnlyAfterCompleteDocument() throws IOException {
    writeJson(VALID);
    JacksonManagedFilesRepository fallback =
        new JacksonManagedFilesRepository(
            (temporary, bytes) -> Files.write(temporary, bytes),
            (temporary, target, atomic) -> {
              assertTrue(Files.readString(temporary).contains("\"files\" : [ ]"));
              assertEquals(VALID, Files.readString(target));
              if (atomic) {
                throw new AtomicMoveNotSupportedException(
                    temporary.toString(), target.toString(), "injected unsupported atomic move");
              }
              Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            });
    fallback.save(root, ManagedFiles.empty());
    assertEquals(Optional.of(ManagedFiles.empty()), repository.load(root));
    assertEquals(List.of(stateFile()), children(stateDirectory()));
  }

  @Test
  void parentReplacementCannotDeleteOutsideSentinelsOrReplaceOutsideState() throws IOException {
    Path workspace = Files.createDirectory(root.resolve("workspace"));
    Path outside = Files.createDirectory(root.resolve("outside"));
    Files.writeString(outside.resolve("managed-files.json"), "outside state");
    JacksonManagedFilesRepository failing =
        new JacksonManagedFilesRepository(
            (temporary, bytes) -> {
              Files.writeString(outside.resolve(temporary.getFileName()), "outside sentinel");
              Files.move(
                  workspace.resolve(".fruit-and-faults"), workspace.resolve("renamed-metadata"));
              Files.createSymbolicLink(workspace.resolve(".fruit-and-faults"), outside);
            },
            (temporary, target, atomic) -> {
              throw new AssertionError("Replaced metadata cannot publish");
            });
    assertThrows(IOException.class, () -> failing.save(workspace, ManagedFiles.empty()));
    assertEquals("outside state", Files.readString(outside.resolve("managed-files.json")));
    assertEquals(2, children(outside).size());
    Path sentinel =
        children(outside).stream()
            .filter(path -> !path.getFileName().toString().equals("managed-files.json"))
            .findFirst()
            .orElseThrow();
    assertEquals("outside sentinel", Files.readString(sentinel));
  }

  @Test
  void cleanupCannotDeleteForeignTemporaryReplacement() throws IOException {
    JacksonManagedFilesRepository failing =
        new JacksonManagedFilesRepository(
            (temporary, bytes) -> {
              Files.move(temporary, root.resolve("relocated-owned-temporary"));
              Files.writeString(temporary, "foreign replacement");
              throw new IOException("injected temporary replacement");
            },
            (temporary, target, atomic) -> {
              throw new AssertionError("Failed write cannot publish");
            });
    assertThrows(IOException.class, () -> failing.save(root, ManagedFiles.empty()));
    assertEquals(1, children(root.resolve(".fruit-and-faults")).size());
    assertEquals(
        "foreign replacement",
        Files.readString(children(root.resolve(".fruit-and-faults")).getFirst()));
    assertTrue(Files.exists(root.resolve("relocated-owned-temporary")));
  }

  @Test
  void symlinkDirectoryFileOrRootCannotReadOrReplaceOutsideState() throws IOException {
    Path workspace = Files.createDirectory(root.resolve("workspace"));
    Path outside = Files.createDirectory(root.resolve("outside"));
    Path outsideFile = Files.writeString(outside.resolve("managed-files.json"), VALID);
    Files.createSymbolicLink(workspace.resolve(".fruit-and-faults"), outside);
    assertThrows(IOException.class, () -> repository.load(workspace));
    assertThrows(IOException.class, () -> repository.save(workspace, ManagedFiles.empty()));
    Files.delete(workspace.resolve(".fruit-and-faults"));
    Files.createDirectory(workspace.resolve(".fruit-and-faults"));
    Files.createSymbolicLink(
        workspace.resolve(".fruit-and-faults/managed-files.json"), outsideFile);
    assertThrows(IOException.class, () -> repository.load(workspace));
    assertThrows(IOException.class, () -> repository.save(workspace, ManagedFiles.empty()));
    Files.createSymbolicLink(root.resolve("linked-root"), workspace);
    assertThrows(IOException.class, () -> repository.load(root.resolve("linked-root")));
    assertThrows(
        IOException.class,
        () -> repository.save(root.resolve("linked-root"), ManagedFiles.empty()));
    assertEquals(VALID, Files.readString(outsideFile));
  }

  @Test
  void oversizedAndNonRegularManifestCannotBeRewritten() throws IOException {
    String oversized = " ".repeat(1_048_577);
    assertRejectedAndPreserved(oversized, ManagedFilesReadException.Reason.MALFORMED);
    Files.delete(stateFile());
    Files.createDirectory(stateFile());
    assertThrows(IOException.class, () -> repository.load(root));
    assertThrows(IOException.class, () -> repository.save(root, ManagedFiles.empty()));
    assertTrue(Files.isDirectory(stateFile()));
  }

  @Test
  void concurrentManifestReplacementIsPreserved() throws IOException {
    writeJson(VALID);
    JacksonManagedFilesRepository concurrent =
        new JacksonManagedFilesRepository(
            (temporary, bytes) -> {
              Files.write(temporary, bytes);
              Files.move(stateFile(), root.resolve("relocated-manifest"));
              Files.writeString(stateFile(), "{\"formatVersion\":2}");
            },
            (temporary, target, atomic) -> {
              throw new AssertionError("Foreign replacement cannot be overwritten");
            });
    assertThrows(IOException.class, () -> concurrent.save(root, ManagedFiles.empty()));
    assertEquals("{\"formatVersion\":2}", Files.readString(stateFile()));
    assertEquals(VALID, Files.readString(root.resolve("relocated-manifest")));
  }

  private void assertRejectedAndPreserved(String json, ManagedFilesReadException.Reason reason)
      throws IOException {
    writeJson(json);
    byte[] before = Files.readAllBytes(stateFile());
    ManagedFilesReadException readFailure =
        assertThrows(ManagedFilesReadException.class, () -> repository.load(root));
    assertEquals(reason, readFailure.reason());
    ManagedFilesReadException saveFailure =
        assertThrows(
            ManagedFilesReadException.class, () -> repository.save(root, ManagedFiles.empty()));
    assertEquals(reason, saveFailure.reason());
    assertArrayEquals(before, Files.readAllBytes(stateFile()));
    assertEquals(List.of(stateFile()), children(stateDirectory()));
  }

  private void writeJson(String json) throws IOException {
    Files.createDirectories(stateDirectory());
    Files.write(stateFile(), json.getBytes(StandardCharsets.UTF_8));
  }

  private Path stateFile() {
    return root.resolve(".fruit-and-faults/managed-files.json");
  }

  private Path stateDirectory() {
    return root.resolve(".fruit-and-faults");
  }

  private static ManagedFile file(String path, String id, AssetPolicy policy) {
    return new ManagedFile(
        WorkspacePath.parse(path), new AssetId(id), HASH, new LessonId("first-run"), policy);
  }

  private static List<Path> children(Path directory) throws IOException {
    try (var children = Files.list(directory)) {
      return children.toList();
    }
  }
}
