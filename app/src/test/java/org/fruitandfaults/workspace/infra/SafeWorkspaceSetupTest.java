package org.fruitandfaults.workspace.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.fruitandfaults.course.domain.CourseId;
import org.fruitandfaults.workspace.application.WorkspaceSetup;
import org.fruitandfaults.workspace.domain.WorkspaceMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeWorkspaceSetupTest {
  @TempDir private Path temporary;
  private final WorkspaceMetadata metadata = new WorkspaceMetadata(new CourseId("fixture"), 1);
  private final SafeWorkspaceSetup setup = new SafeWorkspaceSetup();
  private Path root;

  @BeforeEach
  void selectRealRoot() throws IOException {
    root = temporary.toRealPath();
  }

  @Test
  void createsMissingDirectoryAncestorsAndPublishesPortableExclusiveIdentity() throws IOException {
    Path target = root.resolve("new parent/Игра");
    assertInstanceOf(WorkspaceSetup.Missing.class, setup.inspect(target));
    setup.createDirectory(target);
    setup.createMetadata(target, metadata);
    assertEquals(Optional.of(metadata), setup.loadMetadata(target));
    assertThrows(IOException.class, () -> setup.createMetadata(target, metadata));
    assertEquals("courseId=fixture\nlayoutVersion=1\n", Files.readString(marker(target)));
  }

  @Test
  void preservesForeignMarkerAppearingAfterPreflight() throws IOException {
    SafeWorkspaceFiles racing =
        new SafeWorkspaceFiles(
            SafeWorkspaceFiles::writeFlushed,
            (directory, name) -> {
              if (name.toString().equals("workspace.properties")) {
                Files.writeString(marker(root), "foreign metadata");
              }
              return SafeWorkspaceFiles.createNewChannel(directory, name);
            });
    assertThrows(
        IOException.class, () -> new SafeWorkspaceSetup(racing).createMetadata(root, metadata));
    assertEquals("foreign metadata", Files.readString(marker(root)));
  }

  @Test
  void preservesPartialMarkerWithoutTreatingItAsInitializedWorkspace() throws IOException {
    SafeWorkspaceFiles failing =
        new SafeWorkspaceFiles(
            (channel, bytes) -> {
              channel.write(ByteBuffer.wrap(new byte[] {'c'}));
              throw new IOException("Injected partial write");
            },
            SafeWorkspaceFiles::createNewChannel);
    assertThrows(
        IOException.class, () -> new SafeWorkspaceSetup(failing).createMetadata(root, metadata));
    assertEquals("c", Files.readString(marker(root)));
    assertThrows(IOException.class, () -> setup.loadMetadata(root));
    assertEquals("c", Files.readString(marker(root)));
  }

  @Test
  void parentReplacementCannotRedirectMarkerWritesOutsideWorkspace() throws IOException {
    Path outside = Files.createDirectory(root.resolve("outside"));
    Path state = root.resolve(".fruit-and-faults");
    SafeWorkspaceFiles racing =
        new SafeWorkspaceFiles(
            (channel, bytes) -> {
              Files.move(state, root.resolve("retained-state"));
              Files.createSymbolicLink(state, outside);
              SafeWorkspaceFiles.writeFlushed(channel, bytes);
            },
            SafeWorkspaceFiles::createNewChannel);
    assertThrows(
        IOException.class, () -> new SafeWorkspaceSetup(racing).createMetadata(root, metadata));
    assertTrue(Files.notExists(outside.resolve("workspace.properties")));
    assertEquals(
        "courseId=fixture\nlayoutVersion=1\n",
        Files.readString(root.resolve("retained-state/workspace.properties")));
  }

  @Test
  void rejectsOversizedAndInvalidUtf8MetadataWithoutReplacingIt() throws IOException {
    Files.createDirectory(root.resolve(".fruit-and-faults"));
    Files.writeString(marker(root), "x".repeat(4097));
    assertThrows(IOException.class, () -> setup.loadMetadata(root));
    assertEquals(4097, Files.size(marker(root)));
    Files.write(marker(root), new byte[] {(byte) 0xff});
    assertThrows(IOException.class, () -> setup.loadMetadata(root));
    assertEquals(1, Files.size(marker(root)));
  }

  private static Path marker(Path workspace) {
    return workspace.resolve(".fruit-and-faults/workspace.properties");
  }
}
