package org.fruitandfaults.workspace.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.fruitandfaults.workspace.application.WorkspaceFiles;
import org.fruitandfaults.workspace.domain.WorkspacePath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspacePresenceTest {
  @TempDir private Path temporary;

  @Test
  void observesOnlyRegularFileMetadataAndRejectsSymlinkParentsAndSpecialFiles() throws IOException {
    Path root = Files.createDirectory(temporary.toRealPath().resolve("workspace"));
    Path outside = Files.createDirectory(temporary.toRealPath().resolve("outside"));
    Files.writeString(root.resolve("present"), "keep");
    Files.createSymbolicLink(root.resolve("linked"), outside);
    Files.createSymbolicLink(root.resolve("file-link"), root.resolve("present"));
    Files.createDirectory(root.resolve("directory"));
    var files = new SafeWorkspaceFiles();
    assertEquals(
        WorkspaceFiles.Presence.PRESENT, files.presence(root, WorkspacePath.parse("present")));
    assertEquals(
        WorkspaceFiles.Presence.MISSING,
        files.presence(root, WorkspacePath.parse("missing/child")));
    assertEquals(
        WorkspaceFiles.Presence.UNSAFE, files.presence(root, WorkspacePath.parse("linked/child")));
    assertEquals(
        WorkspaceFiles.Presence.UNSAFE, files.presence(root, WorkspacePath.parse("file-link")));
    assertEquals(
        WorkspaceFiles.Presence.UNSAFE, files.presence(root, WorkspacePath.parse("directory")));
    assertEquals("keep", Files.readString(root.resolve("present")));
  }
}
