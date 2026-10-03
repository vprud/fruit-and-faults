package org.fruitandfaults.workspace.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.fruitandfaults.course.domain.CourseId;
import org.fruitandfaults.workspace.application.WorkspaceLocationException;
import org.fruitandfaults.workspace.domain.WorkspaceMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WalkingWorkspaceLocatorTest {
  @TempDir private Path temporary;
  private final WorkspaceMetadata expected = new WorkspaceMetadata(new CourseId("fixture"), 1);
  private final WalkingWorkspaceLocator locator =
      new WalkingWorkspaceLocator(expected, new SafeWorkspaceSetup());
  private Path root;

  @BeforeEach
  void selectRealRoot() throws IOException {
    root = Files.createDirectory(temporary.toRealPath().resolve("Рабочая игра with spaces"));
  }

  @Test
  void discoversSameWorkspaceAtRootAndFromNestedDirectory() throws IOException {
    marker(root, "courseId=fixture\nlayoutVersion=1\n");
    Path nested = Files.createDirectories(root.resolve("src/main/java"));
    assertEquals(root, locator.locate(root).path());
    assertEquals(root, locator.locate(nested).path());
    assertEquals(expected, locator.locate(nested).metadata());
  }

  @Test
  void stopsAtFilesystemRootWhenNoWorkspaceExists() {
    assertEquals(
        WorkspaceLocationException.Reason.NOT_FOUND,
        assertThrows(WorkspaceLocationException.class, () -> locator.locate(root)).reason());
  }

  @Test
  void refusesToChooseBetweenNestedWorkspaceMarkers() throws IOException {
    marker(root, "courseId=fixture\nlayoutVersion=1\n");
    Path nested = Files.createDirectory(root.resolve("nested"));
    marker(nested, "courseId=fixture\nlayoutVersion=1\n");
    assertEquals(
        WorkspaceLocationException.Reason.AMBIGUOUS,
        assertThrows(WorkspaceLocationException.class, () -> locator.locate(nested)).reason());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"courseId=other-course\nlayoutVersion=1\n", "courseId=fixture\nlayoutVersion=2\n"})
  void rejectsIncompatibleWorkspaceMetadata(String metadata) throws IOException {
    marker(root, metadata);
    assertEquals(
        WorkspaceLocationException.Reason.INCOMPATIBLE,
        assertThrows(WorkspaceLocationException.class, () -> locator.locate(root)).reason());
  }

  @Test
  void rejectsSymlinkCurrentDirectoryAndItsDescendants() throws IOException {
    marker(root, "courseId=fixture\nlayoutVersion=1\n");
    Files.createDirectory(root.resolve("child"));
    Path alias = temporary.toRealPath().resolve("alias");
    Files.createSymbolicLink(alias, root);
    assertEquals(
        WorkspaceLocationException.Reason.UNSAFE,
        assertThrows(WorkspaceLocationException.class, () -> locator.locate(alias)).reason());
    assertEquals(
        WorkspaceLocationException.Reason.UNSAFE,
        assertThrows(WorkspaceLocationException.class, () -> locator.locate(alias.resolve("child")))
            .reason());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsSymlinkedMetadataTreeOrMarker(boolean entireTree) throws IOException {
    Path outside = Files.createDirectory(temporary.toRealPath().resolve("outside"));
    Files.writeString(
        outside.resolve("workspace.properties"), "courseId=fixture\nlayoutVersion=1\n");
    if (entireTree) {
      Files.createSymbolicLink(root.resolve(".fruit-and-faults"), outside);
    } else {
      Files.createDirectory(root.resolve(".fruit-and-faults"));
      Files.createSymbolicLink(
          root.resolve(".fruit-and-faults/workspace.properties"),
          outside.resolve("workspace.properties"));
    }
    assertEquals(
        WorkspaceLocationException.Reason.UNSAFE,
        assertThrows(WorkspaceLocationException.class, () -> locator.locate(root)).reason());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "courseId=fixture\nlayoutVersion=0\n",
        "courseId=fixture\nlayoutVersion=one\n",
        "courseId=fixture\n",
        "courseId=fixture\nlayoutVersion=1\nsecret=value\n",
        "courseId=fixture\nlayoutVersion=1\nlayoutVersion=1\n"
      })
  void rejectsMalformedMetadataWithoutChangingIt(String metadata) throws IOException {
    marker(root, metadata);
    assertThrows(WorkspaceLocationException.class, () -> locator.locate(root));
    assertEquals(
        metadata, Files.readString(root.resolve(".fruit-and-faults/workspace.properties")));
  }

  private static void marker(Path workspace, String contents) throws IOException {
    Files.createDirectory(workspace.resolve(".fruit-and-faults"));
    Files.writeString(workspace.resolve(".fruit-and-faults/workspace.properties"), contents);
  }
}
