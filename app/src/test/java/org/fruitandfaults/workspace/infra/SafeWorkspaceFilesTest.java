package org.fruitandfaults.workspace.infra;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SafeWorkspaceFilesTest {
  private static final byte[] BYTES = "hello".getBytes(StandardCharsets.UTF_8);
  private static final String HASH =
      "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
  @TempDir private Path root;
  private final SafeWorkspaceFiles files = new SafeWorkspaceFiles();

  @Test
  void providerWithoutSecureDirectoryHandlesFailsBeforePublishingLearnerContent()
      throws IOException {
    try (var provider =
        java.nio.file.FileSystems.newFileSystem(
            root.resolve("unsupported-provider.zip"), Map.of("create", "true"))) {
      Path unsupported = provider.getPath("/");
      try (var opened = Files.newDirectoryStream(unsupported)) {
        assertFalse(opened instanceof java.nio.file.SecureDirectoryStream<?>);
      }
      WorkspaceWriteException failed =
          assertThrows(
              WorkspaceWriteException.class,
              () -> files.writeNewSafely(unsupported, WorkspacePath.parse("Game.java"), BYTES));
      assertEquals(WorkspaceWriteException.Reason.UNSUPPORTED_PUBLICATION, failed.reason());
      assertTrue(Files.notExists(unsupported.resolve("Game.java")));
      try (var entries = Files.list(unsupported)) {
        assertEquals(0, entries.count());
      }
    }
  }

  @Test
  void boundedReadsReturnOnlyRequestedRegularFilesAndRejectSymlinks() throws IOException {
    Files.write(root.resolve("Game.java"), BYTES);
    assertEquals(
        "hello",
        new String(
            files.read(root, WorkspacePath.parse("Game.java")).orElseThrow(),
            StandardCharsets.UTF_8));
    assertTrue(files.read(root, WorkspacePath.parse("absent/Game.java")).isEmpty());
    Files.createSymbolicLink(root.resolve("linked"), root.resolve("Game.java"));
    assertThrows(IOException.class, () -> files.read(root, WorkspacePath.parse("linked")));
    Files.createDirectory(root.resolve("directory"));
    assertThrows(IOException.class, () -> files.read(root, WorkspacePath.parse("directory")));
    Path large = root.resolve("large");
    try (var channel =
        java.nio.channels.FileChannel.open(
            large,
            java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE)) {
      channel.position(16_777_216);
      channel.write(ByteBuffer.wrap(new byte[] {1}));
    }
    assertThrows(IOException.class, () -> files.read(root, WorkspacePath.parse("large")));
  }

  @Test
  void directorySwapDuringAnchoredReadNeverFollowsTheReplacementSymlink() throws IOException {
    Path source = Files.createDirectory(root.resolve("src"));
    Files.write(source.resolve("Game.java"), BYTES);
    Path foreign = Files.createDirectory(root.resolve("foreign"));
    Files.writeString(foreign.resolve("Game.java"), "external secret");
    boolean[] swapped = {false};
    SafeWorkspaceFiles racing =
        new SafeWorkspaceFiles(
            SafeWorkspaceFiles::writeFlushed,
            SafeWorkspaceFiles::createNewChannel,
            attributes -> {
              if (!swapped[0]) {
                swapped[0] = true;
                try {
                  Files.move(source, root.resolve("original-src"));
                  Files.createSymbolicLink(source, foreign);
                } catch (IOException failure) {
                  throw new java.io.UncheckedIOException(failure);
                }
              }
              return attributes.fileKey();
            });
    assertThrows(IOException.class, () -> racing.read(root, WorkspacePath.parse("src/Game.java")));
    assertTrue(swapped[0]);
    assertEquals("external secret", Files.readString(foreign.resolve("Game.java")));
  }

  @BeforeEach
  void resolveTemporaryDirectoryAlias() throws IOException {
    root = root.toRealPath();
  }

  @Test
  void writesRawBytesWithSpacesAndCyrillicThroughVerifiedDirectories() throws IOException {
    WorkspacePath path = WorkspacePath.parse("моя игра/src/Game.java");
    assertInstanceOf(DisclosurePlan.Missing.class, files.inspect(root, path));
    files.writeNewSafely(root, path, BYTES);
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
        () -> files.writeNewSafely(root, asset.path(), "other".getBytes(StandardCharsets.UTF_8)));
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
        () -> files.writeNewSafely(root, plan, Map.of(first.path(), BYTES, second.path(), BYTES)));
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
        () -> files.writeNewSafely(root, plan, Map.of(first.path(), BYTES, second.path(), BYTES)));
    assertTrue(Files.notExists(root.resolve("new")));
    Files.delete(root.resolve("other.java"));
    assertThrows(
        IOException.class,
        () ->
            files.writeNewSafely(
                root, plan, Map.of(first.path(), BYTES, second.path(), new byte[0])));
    assertTrue(Files.notExists(root.resolve("new")));
    files.writeNewSafely(root, plan, Map.of(first.path(), BYTES, second.path(), BYTES));
    assertEquals("hello", Files.readString(root.resolve(first.path().value())));
    assertEquals("hello", Files.readString(root.resolve(second.path().value())));
    DisclosurePlan repeated =
        files.preflight(root, List.of(first, second), new ManagedFiles(List.of(first, second)));
    files.writeNewSafely(root, repeated, Map.of());
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
          () -> files.writeNewSafely(workspace, WorkspacePath.parse(path), BYTES));
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
        () -> files.writeNewSafely(root, WorkspacePath.parse("parent/new"), BYTES));
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
        () -> files.writeNewSafely(root, plan, Map.of(parent.path(), BYTES, child.path(), BYTES)));
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
            (channel, bytes) -> SafeWorkspaceFiles.writeFlushed(channel, bytes),
            (directory, target) -> {
              assertEquals(Path.of("Game.java"), target);
              throw new AtomicMoveNotSupportedException(
                  "created-entry", target.toString(), "injected publication failure");
            });
    assertThrows(
        IOException.class,
        () -> failing.writeNewSafely(root, WorkspacePath.parse("Game.java"), BYTES));
    assertEquals(List.of(root.resolve("unrelated.tmp")), children(root));
    assertEquals("learner work", Files.readString(root.resolve("unrelated.tmp")));
  }

  @Test
  void targetAppearingBeforeExclusiveCreationIsPreserved() throws IOException {
    SafeWorkspaceFiles lateTarget =
        new SafeWorkspaceFiles(
            SafeWorkspaceFiles::writeFlushed,
            (directory, target) -> {
              Files.writeString(root.resolve("Game.java"), "late learner work");
              return SafeWorkspaceFiles.createNewChannel(directory, target);
            });
    assertThrows(
        IOException.class,
        () -> lateTarget.writeNewSafely(root, WorkspacePath.parse("Game.java"), BYTES));
    assertEquals("late learner work", Files.readString(root.resolve("Game.java")));
  }

  @Test
  void partialWriteFailureRetainsAmbiguousTargetForRecovery() throws IOException {
    SafeWorkspaceFiles failing =
        new SafeWorkspaceFiles(
            (channel, bytes) -> {
              channel.write(ByteBuffer.wrap("partial".getBytes(StandardCharsets.UTF_8)));
              throw new IOException("injected partial write");
            },
            SafeWorkspaceFiles::createNewChannel);
    assertThrows(
        IOException.class,
        () -> failing.writeNewSafely(root, WorkspacePath.parse("Game.java"), BYTES));
    assertEquals("partial", Files.readString(root.resolve("Game.java")));
    assertThrows(
        IOException.class,
        () -> files.writeNewSafely(root, WorkspacePath.parse("Game.java"), BYTES));
    assertEquals("partial", Files.readString(root.resolve("Game.java")));
    assertEquals(List.of(root.resolve("Game.java")), children(root));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "foreign replacement", "jello", "hello extra"})
  void successfulCreationRejectsForeignBytesSwappedInsideCreator(String foreign)
      throws IOException {
    SafeWorkspaceFiles replaced =
        new SafeWorkspaceFiles(SafeWorkspaceFiles::writeFlushed, swappingCreator(foreign));

    WorkspaceWriteException failure =
        assertThrows(
            WorkspaceWriteException.class,
            () -> replaced.writeNewSafely(root, WorkspacePath.parse("Game.java"), BYTES));

    assertAll(
        () -> assertEquals(WorkspaceWriteException.Reason.PUBLICATION_FAILED, failure.reason()),
        () -> assertEquals(foreign, Files.readString(root.resolve("Game.java"))),
        () -> assertEquals("hello", Files.readString(root.resolve("relocated-owned-entry"))),
        () -> assertEquals(2, children(root).size()));
  }

  @Test
  void successfulCreationAcceptsIdenticalBytesSwappedInsideCreator() throws IOException {
    SafeWorkspaceFiles replaced =
        new SafeWorkspaceFiles(SafeWorkspaceFiles::writeFlushed, swappingCreator("hello"));

    replaced.writeNewSafely(root, WorkspacePath.parse("Game.java"), BYTES);

    assertAll(
        () -> assertEquals("hello", Files.readString(root.resolve("Game.java"))),
        () -> assertEquals("hello", Files.readString(root.resolve("relocated-owned-entry"))),
        () -> assertEquals(2, children(root).size()));
  }

  @Test
  void failedCreationPreservesReplacementMadeInsideCreator() throws IOException {
    SafeWorkspaceFiles replaced =
        new SafeWorkspaceFiles(
            (channel, bytes) -> {
              channel.write(ByteBuffer.wrap("partial".getBytes(StandardCharsets.UTF_8)));
              throw new IOException("injected write failure after creator swap");
            },
            swappingCreator("foreign replacement"));

    assertAll(
        () ->
            assertThrows(
                WorkspaceWriteException.class,
                () -> replaced.writeNewSafely(root, WorkspacePath.parse("Game.java"), BYTES)),
        () -> assertEquals("foreign replacement", Files.readString(root.resolve("Game.java"))),
        () -> assertEquals("partial", Files.readString(root.resolve("relocated-owned-entry"))),
        () -> assertEquals(2, children(root).size()));
  }

  @Test
  void unsupportedExclusivePublicationFailsSafelyWithoutFallback() throws IOException {
    SafeWorkspaceFiles unsupported =
        new SafeWorkspaceFiles(
            (channel, bytes) -> SafeWorkspaceFiles.writeFlushed(channel, bytes),
            (directory, target) -> {
              throw new UnsupportedOperationException("provider cannot securely create");
            });
    WorkspaceWriteException failure =
        assertThrows(
            WorkspaceWriteException.class,
            () -> unsupported.writeNewSafely(root, WorkspacePath.parse("Game.java"), BYTES));
    assertEquals(WorkspaceWriteException.Reason.UNSUPPORTED_PUBLICATION, failure.reason());
    assertEquals(List.of(), children(root));
  }

  @Test
  void cleanupPreservesForeignReplacementOfReservedTarget() throws IOException {
    SafeWorkspaceFiles failing =
        new SafeWorkspaceFiles(
            (channel, bytes) -> {
              Files.move(root.resolve("Game.java"), root.resolve("relocated-owned-entry"));
              Files.writeString(root.resolve("Game.java"), "foreign replacement");
              throw new IOException("injected temporary replacement");
            },
            SafeWorkspaceFiles::createNewChannel);
    assertThrows(
        IOException.class,
        () -> failing.writeNewSafely(root, WorkspacePath.parse("Game.java"), BYTES));
    assertEquals("foreign replacement", Files.readString(root.resolve("Game.java")));
    assertTrue(Files.exists(root.resolve("relocated-owned-entry")));
  }

  @Test
  void parentReplacementCannotPublishOrDeleteOutsideSentinels() throws IOException {
    Path workspace = Files.createDirectory(root.resolve("workspace"));
    Path outside = Files.createDirectory(root.resolve("outside"));
    Files.createDirectory(workspace.resolve("src"));
    SafeWorkspaceFiles failing =
        new SafeWorkspaceFiles(
            (channel, bytes) -> {
              Files.writeString(outside.resolve("sentinel"), "outside sentinel");
              Files.move(workspace.resolve("src"), workspace.resolve("renamed-src"));
              Files.createSymbolicLink(workspace.resolve("src"), outside);
            },
            SafeWorkspaceFiles::createNewChannel);
    assertThrows(
        IOException.class,
        () -> failing.writeNewSafely(workspace, WorkspacePath.parse("src/Game.java"), BYTES));
    assertTrue(Files.notExists(outside.resolve("Game.java")));
    assertEquals(1, children(outside).size());
    assertEquals("outside sentinel", Files.readString(children(outside).getFirst()));
  }

  @Test
  void parentSwapInsideCreatorCannotCreateOutsideAsset() throws IOException {
    Path workspace = Files.createDirectory(root.resolve("workspace"));
    Path outside = Files.createDirectory(root.resolve("outside"));
    Files.createDirectory(workspace.resolve("src"));
    Files.writeString(outside.resolve("sentinel"), "outside sentinel");
    SafeWorkspaceFiles redirected =
        new SafeWorkspaceFiles(
            SafeWorkspaceFiles::writeFlushed,
            (directory, target) -> {
              Files.move(workspace.resolve("src"), workspace.resolve("renamed-src"));
              Files.createSymbolicLink(workspace.resolve("src"), outside);
              return SafeWorkspaceFiles.createNewChannel(directory, target);
            });
    assertAll(
        () ->
            assertThrows(
                WorkspaceWriteException.class,
                () ->
                    redirected.writeNewSafely(
                        workspace, WorkspacePath.parse("src/Game.java"), BYTES)),
        () -> assertTrue(Files.notExists(outside.resolve("Game.java"))),
        () -> assertEquals("outside sentinel", Files.readString(outside.resolve("sentinel"))));
  }

  @Test
  void preflightIndependentlyRejectsAmbiguousSuppliedOwnership() throws IOException {
    ManagedFile upper = asset("Game.java");
    ManagedFile lower = asset("game.java");
    DisclosurePlan.Conflicted plan =
        assertInstanceOf(
            DisclosurePlan.Conflicted.class,
            files.preflight(root, List.of(lower), new ManagedFiles(List.of(upper, lower))));
    assertTrue(
        plan.conflicts()
            .contains(
                new DisclosureConflict(lower.path(), DisclosureConflict.Reason.CASE_COLLISION)));
    assertEquals(List.of(), children(root));
  }

  @Test
  void missingFileKeyIsAnUnsupportedCapabilityRatherThanGenericIoFailure() throws IOException {
    Path temporary = Files.writeString(root.resolve("owned.tmp"), "unchanged temporary");
    WorkspaceWriteException failure =
        assertThrows(
            WorkspaceWriteException.class,
            () ->
                SafeWorkspaceFiles.requireStableKey(
                    SafeWorkspaceFiles.attributes(temporary), _ -> null));
    assertEquals(WorkspaceWriteException.Reason.UNSUPPORTED_PUBLICATION, failure.reason());
    assertEquals("unchanged temporary", Files.readString(temporary));
    assertTrue(Files.notExists(root.resolve("Game.java")));
  }

  @Test
  void providerWithoutFileKeysCannotCreateALearnerTarget() throws IOException {
    SafeWorkspaceFiles unsupported =
        new SafeWorkspaceFiles(
            SafeWorkspaceFiles::writeFlushed, SafeWorkspaceFiles::createNewChannel, _ -> null);
    WorkspaceWriteException failure =
        assertThrows(
            WorkspaceWriteException.class,
            () -> unsupported.writeNewSafely(root, WorkspacePath.parse("Game.java"), BYTES));
    assertEquals(WorkspaceWriteException.Reason.UNSUPPORTED_PUBLICATION, failure.reason());
    assertEquals(List.of(), children(root));
  }

  @Test
  void missingRegularFileKeysFailBeforeReservingLearnerTarget() throws IOException {
    SafeWorkspaceFiles unsupported =
        new SafeWorkspaceFiles(
            SafeWorkspaceFiles::writeFlushed,
            SafeWorkspaceFiles::createNewChannel,
            attributes -> attributes.isDirectory() ? attributes.fileKey() : null);
    WorkspaceWriteException failure =
        assertThrows(
            WorkspaceWriteException.class,
            () -> unsupported.writeNewSafely(root, WorkspacePath.parse("Game.java"), BYTES));
    assertEquals(WorkspaceWriteException.Reason.UNSUPPORTED_PUBLICATION, failure.reason());
    assertTrue(Files.notExists(root.resolve("Game.java")));
    assertEquals(1, children(root).size());
    assertTrue(children(root).getFirst().getFileName().toString().endsWith(".tmp"));
  }

  private static ManagedFile asset(String path) {
    return new ManagedFile(
        WorkspacePath.parse(path),
        new AssetId("game"),
        HASH,
        new LessonId("first-run"),
        AssetPolicy.LEARNER_SCAFFOLD);
  }

  private SafeWorkspaceFiles.EntryCreator swappingCreator(String foreign) {
    return (directory, target) -> {
      var channel = SafeWorkspaceFiles.createNewChannel(directory, target);
      try {
        Files.move(root.resolve("Game.java"), root.resolve("relocated-owned-entry"));
        Files.writeString(root.resolve("Game.java"), foreign);
        return channel;
      } catch (IOException | RuntimeException failed) {
        channel.close();
        throw failed;
      }
    };
  }

  private static List<Path> children(Path directory) throws IOException {
    try (var children = Files.list(directory)) {
      return children.toList();
    }
  }
}
