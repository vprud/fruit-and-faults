package org.fruitandfaults.workspace.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.fruitandfaults.course.domain.AssetId;
import org.fruitandfaults.course.domain.AssetPolicy;
import org.fruitandfaults.course.domain.LessonId;
import org.fruitandfaults.workspace.application.WorkspaceWriteException;
import org.fruitandfaults.workspace.domain.DisclosureConflict;
import org.fruitandfaults.workspace.domain.DisclosurePlan;
import org.fruitandfaults.workspace.domain.ManagedFile;
import org.fruitandfaults.workspace.domain.ManagedFiles;
import org.fruitandfaults.workspace.domain.WorkspacePath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeWorkspaceFilesTest {
  private static final byte[] BYTES = "hello".getBytes(StandardCharsets.UTF_8);
  private static final String HASH =
      "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
  @TempDir private Path root;
  private final SafeWorkspaceFiles files = new SafeWorkspaceFiles();

  @BeforeEach
  void resolveTemporaryDirectoryAlias() throws IOException {
    root = root.toRealPath();
  }

  @Test
  void writesRawBytesWithSpacesAndCyrillicThroughVerifiedDirectories() throws IOException {
    WorkspacePath path = WorkspacePath.parse("моя игра/src/Game.java");
    assertInstanceOf(DisclosurePlan.Missing.class, files.inspect(root, path));
    files.writeNewAtomically(root, path, BYTES);
    assertEquals("hello", Files.readString(root.resolve(path.value())));
    assertEquals(new DisclosurePlan.RegularFile(HASH), files.inspect(root, path));
    assertEquals(List.of(root.resolve(path.value())), children(root.resolve("моя игра/src")));
  }

  @Test
  void unknownExistingFileIsNeverOverwrittenEvenIfItsBytesMatch() throws IOException {
    ManagedFile asset = asset("Game.java");
    Files.write(root.resolve(asset.path().value()), BYTES);
    DisclosurePlan.Conflicted plan =
        assertInstanceOf(
            DisclosurePlan.Conflicted.class,
            files.preflight(root, List.of(asset), ManagedFiles.empty()));
    assertEquals(
        List.of(new DisclosureConflict(asset.path(), DisclosureConflict.Reason.EXISTING_UNMANAGED)),
        plan.conflicts());
    assertThrows(
        IOException.class,
        () ->
            files.writeNewAtomically(root, asset.path(), "other".getBytes(StandardCharsets.UTF_8)));
    assertEquals("hello", Files.readString(root.resolve(asset.path().value())));
  }

  @Test
  void allTargetsAreInspectedBeforeBatchCreatesAnyFileOrDirectory() throws IOException {
    ManagedFile first = asset("new/src/Game.java");
    ManagedFile second = asset("learner.txt");
    Files.writeString(root.resolve("learner.txt"), "learner work");
    DisclosurePlan plan = files.preflight(root, List.of(first, second), ManagedFiles.empty());
    assertInstanceOf(DisclosurePlan.Conflicted.class, plan);
    assertThrows(
        IOException.class,
        () ->
            files.writeNewAtomically(
                root, plan, Map.of(first.path(), BYTES, second.path(), BYTES)));
    assertTrue(Files.notExists(root.resolve("new")));
    assertEquals("learner work", Files.readString(root.resolve("learner.txt")));
  }

  @Test
  void applicableBatchRechecksTargetsAndContentBeforeCreatingAnything() throws IOException {
    ManagedFile first = asset("new/Game.java");
    ManagedFile second = asset("other.java");
    DisclosurePlan plan = files.preflight(root, List.of(first, second), ManagedFiles.empty());
    Files.writeString(root.resolve("other.java"), "late learner work");
    assertThrows(
        IOException.class,
        () ->
            files.writeNewAtomically(
                root, plan, Map.of(first.path(), BYTES, second.path(), BYTES)));
    assertTrue(Files.notExists(root.resolve("new")));
    Files.delete(root.resolve("other.java"));
    assertThrows(
        IOException.class,
        () ->
            files.writeNewAtomically(
                root, plan, Map.of(first.path(), BYTES, second.path(), new byte[0])));
    assertTrue(Files.notExists(root.resolve("new")));
    files.writeNewAtomically(root, plan, Map.of(first.path(), BYTES, second.path(), BYTES));
    assertEquals("hello", Files.readString(root.resolve(first.path().value())));
    assertEquals("hello", Files.readString(root.resolve(second.path().value())));
    DisclosurePlan repeated =
        files.preflight(root, List.of(first, second), new ManagedFiles(List.of(first, second)));
    files.writeNewAtomically(root, repeated, Map.of());
    assertEquals(
        List.of(first, second),
        assertInstanceOf(DisclosurePlan.Applicable.class, repeated).alreadyApplied());
  }

  @Test
  void rejectsSymlinkFilesParentsAndRootsEvenWhenTheyPointInside() throws IOException {
    Path outside = Files.createDirectory(root.resolve("outside"));
    Path workspace = Files.createDirectory(root.resolve("workspace"));
    Path sentinel = Files.writeString(outside.resolve("sentinel"), "outside sentinel");
    Files.createSymbolicLink(workspace.resolve("linked-file"), sentinel);
    Files.createSymbolicLink(workspace.resolve("linked-parent"), outside);
    Path inside = Files.createDirectory(workspace.resolve("real"));
    Files.writeString(inside.resolve("sentinel"), "inside sentinel");
    Files.createSymbolicLink(workspace.resolve("inside-link"), inside);
    for (String path :
        List.of("linked-file", "linked-parent/sentinel", "linked-parent/new", "inside-link/new")) {
      assertInstanceOf(
          DisclosurePlan.UnsafePath.class, files.inspect(workspace, WorkspacePath.parse(path)));
      assertThrows(
          IOException.class,
          () -> files.writeNewAtomically(workspace, WorkspacePath.parse(path), BYTES));
    }
    Files.createSymbolicLink(root.resolve("linked-root"), workspace);
    assertThrows(
        IOException.class,
        () -> files.inspect(root.resolve("linked-root"), WorkspacePath.parse("new")));
    assertEquals("outside sentinel", Files.readString(sentinel));
    assertTrue(Files.notExists(outside.resolve("new")));
    assertTrue(Files.notExists(inside.resolve("new")));
  }

  @Test
  void nonDirectoryParentsAndNonRegularTargetsAreUnsafe() throws IOException {
    Files.writeString(root.resolve("parent"), "learner work");
    Files.createDirectory(root.resolve("directory"));
    assertInstanceOf(
        DisclosurePlan.UnsafePath.class, files.inspect(root, WorkspacePath.parse("parent/new")));
    assertInstanceOf(
        DisclosurePlan.UnsafePath.class, files.inspect(root, WorkspacePath.parse("directory")));
    assertThrows(
        IOException.class,
        () -> files.writeNewAtomically(root, WorkspacePath.parse("parent/new"), BYTES));
    assertThrows(
        IOException.class, () -> files.inspect(root.resolve("absent"), WorkspacePath.parse("new")));
  }

  @Test
  void requestedFileCannotAlsoBeAParentOfAnotherTarget() throws IOException {
    DisclosurePlan.Conflicted plan =
        assertInstanceOf(
            DisclosurePlan.Conflicted.class,
            files.preflight(
                root, List.of(asset("src"), asset("src/Game.java")), ManagedFiles.empty()));
    assertEquals(
        List.of(
            new DisclosureConflict(
                WorkspacePath.parse("src/Game.java"), DisclosureConflict.Reason.TARGET_ANCESTOR)),
        plan.conflicts());
    assertEquals(List.of(), children(root));
  }

  @Test
  void caseAliasedFileAncestorIsRejectedBeforeCreatingAnyTarget() throws IOException {
    ManagedFile parent = asset("SRC");
    ManagedFile child = asset("src/Game.java");
    DisclosurePlan.Conflicted plan =
        assertInstanceOf(
            DisclosurePlan.Conflicted.class,
            files.preflight(root, List.of(parent, child), ManagedFiles.empty()));
    assertEquals(
        List.of(new DisclosureConflict(child.path(), DisclosureConflict.Reason.TARGET_ANCESTOR)),
        plan.conflicts());
    assertThrows(
        IOException.class,
        () ->
            files.writeNewAtomically(
                root, plan, Map.of(parent.path(), BYTES, child.path(), BYTES)));
    assertEquals(List.of(), children(root));
  }

  @Test
  void caseAliasesConflictWhenTheFilesystemExposesThem() throws IOException {
    Files.writeString(root.resolve("CaseProbe"), "probe");
    assumeTrue(Files.exists(root.resolve("caseprobe")), "Filesystem is case-sensitive");
    Files.delete(root.resolve("CaseProbe"));
    ManagedFile upper = asset("Game.java");
    ManagedFile lower = asset("game.java");
    DisclosurePlan.Conflicted plan =
        assertInstanceOf(
            DisclosurePlan.Conflicted.class,
            files.preflight(root, List.of(upper, lower), ManagedFiles.empty()));
    assertEquals(
        List.of(new DisclosureConflict(lower.path(), DisclosureConflict.Reason.CASE_COLLISION)),
        plan.conflicts());
    assertEquals(List.of(), children(root));
    Files.write(root.resolve("Game.java"), BYTES);
    assertInstanceOf(
        DisclosurePlan.Conflicted.class,
        files.preflight(root, List.of(lower), new ManagedFiles(List.of(upper))));
  }

  @Test
  void injectedPublicationFailureLeavesNoTargetAndPreservesUnrelatedFiles() throws IOException {
    Files.writeString(root.resolve("unrelated.tmp"), "learner work");
    SafeWorkspaceFiles failing =
        new SafeWorkspaceFiles(
            (temporary, bytes) -> Files.write(temporary, bytes),
            (temporary, target) -> {
              assertEquals(target.getParent(), temporary.getParent());
              assertEquals("hello", Files.readString(temporary));
              throw new AtomicMoveNotSupportedException(
                  temporary.toString(), target.toString(), "injected publication failure");
            });
    assertThrows(
        IOException.class,
        () -> failing.writeNewAtomically(root, WorkspacePath.parse("Game.java"), BYTES));
    assertEquals(List.of(root.resolve("unrelated.tmp")), children(root));
    assertEquals("learner work", Files.readString(root.resolve("unrelated.tmp")));
  }

  @Test
  void targetAppearingDuringWriteAndExclusivePublicationIsPreserved() throws IOException {
    SafeWorkspaceFiles lateTarget =
        new SafeWorkspaceFiles(
            (temporary, bytes) -> {
              Files.write(temporary, bytes);
              Files.writeString(root.resolve("Game.java"), "late learner work");
            },
            (temporary, target) -> Files.createLink(target, temporary));
    assertThrows(
        IOException.class,
        () -> lateTarget.writeNewAtomically(root, WorkspacePath.parse("Game.java"), BYTES));
    assertEquals("late learner work", Files.readString(root.resolve("Game.java")));
    Files.delete(root.resolve("Game.java"));
    SafeWorkspaceFiles racingPublication =
        new SafeWorkspaceFiles(
            (temporary, bytes) -> Files.write(temporary, bytes),
            (temporary, target) -> {
              Files.writeString(target, "racing learner work");
              Files.createLink(target, temporary);
            });
    assertThrows(
        IOException.class,
        () -> racingPublication.writeNewAtomically(root, WorkspacePath.parse("Game.java"), BYTES));
    assertEquals("racing learner work", Files.readString(root.resolve("Game.java")));
  }

  @Test
  void partialWriteFailureCleansOnlyOwnedTemporaryFile() throws IOException {
    SafeWorkspaceFiles failing =
        new SafeWorkspaceFiles(
            (temporary, bytes) -> {
              Files.writeString(temporary, "partial");
              throw new IOException("injected partial write");
            },
            (temporary, target) -> {
              throw new AssertionError("Failed write cannot publish");
            });
    assertThrows(
        IOException.class,
        () -> failing.writeNewAtomically(root, WorkspacePath.parse("Game.java"), BYTES));
    assertEquals(List.of(), children(root));
  }

  @Test
  void unsupportedExclusivePublicationFailsSafelyWithoutFallback() throws IOException {
    SafeWorkspaceFiles unsupported =
        new SafeWorkspaceFiles(
            (temporary, bytes) -> Files.write(temporary, bytes),
            (temporary, target) -> {
              throw new UnsupportedOperationException("provider cannot link");
            });
    WorkspaceWriteException failure =
        assertThrows(
            WorkspaceWriteException.class,
            () -> unsupported.writeNewAtomically(root, WorkspacePath.parse("Game.java"), BYTES));
    assertEquals(WorkspaceWriteException.Reason.UNSUPPORTED_PUBLICATION, failure.reason());
    assertEquals(List.of(), children(root));
  }

  @Test
  void cleanupPreservesForeignTemporaryReplacement() throws IOException {
    SafeWorkspaceFiles failing =
        new SafeWorkspaceFiles(
            (temporary, bytes) -> {
              Files.move(temporary, root.resolve("relocated-owned-temporary"));
              Files.writeString(temporary, "foreign replacement");
              throw new IOException("injected temporary replacement");
            },
            (temporary, target) -> {
              throw new AssertionError("Failed write cannot publish");
            });
    assertThrows(
        IOException.class,
        () -> failing.writeNewAtomically(root, WorkspacePath.parse("Game.java"), BYTES));
    try (var entries = Files.list(root)) {
      Path foreign =
          entries
              .filter(path -> !path.getFileName().toString().equals("relocated-owned-temporary"))
              .findFirst()
              .orElseThrow();
      assertEquals("foreign replacement", Files.readString(foreign));
    }
    assertTrue(Files.exists(root.resolve("relocated-owned-temporary")));
  }

  @Test
  void parentReplacementCannotPublishOrDeleteOutsideSentinels() throws IOException {
    Path workspace = Files.createDirectory(root.resolve("workspace"));
    Path outside = Files.createDirectory(root.resolve("outside"));
    Files.createDirectory(workspace.resolve("src"));
    SafeWorkspaceFiles failing =
        new SafeWorkspaceFiles(
            (temporary, bytes) -> {
              Files.writeString(outside.resolve(temporary.getFileName()), "outside sentinel");
              Files.move(workspace.resolve("src"), workspace.resolve("renamed-src"));
              Files.createSymbolicLink(workspace.resolve("src"), outside);
            },
            (temporary, target) -> {
              throw new AssertionError("Replaced parent cannot publish");
            });
    assertThrows(
        IOException.class,
        () -> failing.writeNewAtomically(workspace, WorkspacePath.parse("src/Game.java"), BYTES));
    assertTrue(Files.notExists(outside.resolve("Game.java")));
    assertEquals(1, children(outside).size());
    assertEquals("outside sentinel", Files.readString(children(outside).getFirst()));
  }

  private static ManagedFile asset(String path) {
    return new ManagedFile(
        WorkspacePath.parse(path),
        new AssetId("game"),
        HASH,
        new LessonId("first-run"),
        AssetPolicy.LEARNER_SCAFFOLD);
  }

  private static List<Path> children(Path directory) throws IOException {
    try (var children = Files.list(directory)) {
      return children.toList();
    }
  }
}
