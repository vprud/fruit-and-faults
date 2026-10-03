package org.fruitandfaults.workspace.infra;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import org.fruitandfaults.course.domain.CourseId;
import org.fruitandfaults.workspace.application.WorkspaceRoot;
import org.fruitandfaults.workspace.application.WorkspaceSetup;
import org.fruitandfaults.workspace.domain.WorkspaceMetadata;
import org.fruitandfaults.workspace.domain.WorkspacePath;
import org.jspecify.annotations.Nullable;

/**
 * Safe destination preflight and bounded, anchored, write-once workspace.properties persistence.
 */
public final class SafeWorkspaceSetup implements WorkspaceSetup {
  private static final int MAX_METADATA_BYTES = 4096;
  private static final WorkspacePath MARKER =
      WorkspacePath.parse(".fruit-and-faults/workspace.properties");
  private final SafeWorkspaceFiles files;

  /** Uses the existing anchored exclusive file writer. */
  public SafeWorkspaceSetup() {
    this(new SafeWorkspaceFiles());
  }

  SafeWorkspaceSetup(SafeWorkspaceFiles files) {
    this.files = Objects.requireNonNull(files);
  }

  @Override
  public Destination inspect(Path target) throws IOException {
    Path root = safePath(target);
    for (@Nullable Path ancestor = root.getParent();
        ancestor != null;
        ancestor = ancestor.getParent()) {
      if (Files.isDirectory(ancestor, LinkOption.NOFOLLOW_LINKS)
          && loadMetadata(ancestor).isPresent()) {
        throw new IOException(
            "Nested workspace markers are ambiguous; choose a destination outside the existing workspace.");
      }
    }
    if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
      return new Missing(root);
    }
    Optional<WorkspaceMetadata> marker = loadMetadata(root);
    if (marker.isPresent()) {
      return new Initialized(new WorkspaceRoot(root, marker.orElseThrow()));
    }
    try (var entries = Files.newDirectoryStream(root)) {
      return entries.iterator().hasNext() ? new Occupied(root) : new Empty(root);
    }
  }

  @Override
  public void createDirectory(Path target) throws IOException {
    if (!(inspect(target) instanceof Missing missing)) {
      throw new IOException("Destination changed after preview; inspect it and confirm again.");
    }
    Path root = missing.root();
    Path current = Objects.requireNonNull(root.getRoot());
    for (Path segment : root) {
      Path next = current.resolve(segment);
      if (Files.notExists(next, LinkOption.NOFOLLOW_LINKS)) {
        var parents = SafeWorkspaceFiles.directoryIdentities(root.getRoot(), current);
        SafeWorkspaceFiles.verifyDirectories(parents);
        Files.createDirectory(next);
        SafeWorkspaceFiles.verifyDirectories(parents);
      }
      safePath(next);
      current = next;
    }
  }

  @Override
  public Optional<WorkspaceMetadata> loadMetadata(Path root) throws IOException {
    Path safeRoot = safePath(root);
    if (!Files.isDirectory(safeRoot, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Expected an existing real directory.");
    }
    Path file = SafeWorkspaceFiles.verifiedTarget(safeRoot, MARKER);
    if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    var directories = SafeWorkspaceFiles.directoryIdentities(safeRoot, file.getParent());
    try (SecureDirectoryStream<Path> directory =
        SafeWorkspaceFiles.openVerifiedDirectory(
            safeRoot,
            Objects.requireNonNull(file.getParent()),
            directories,
            BasicFileAttributes::fileKey)) {
      Path name = Objects.requireNonNull(file.getFileName());
      Object key =
          SafeWorkspaceFiles.requireStableKey(
              SafeWorkspaceFiles.attributes(directory, name), BasicFileAttributes::fileKey);
      SafeWorkspaceFiles.requireEntryIdentity(directory, name, key);
      byte[] bytes;
      try (SeekableByteChannel channel =
              directory.newByteChannel(
                  name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
          var input = Channels.newInputStream(channel)) {
        bytes = input.readNBytes(MAX_METADATA_BYTES + 1);
      }
      if (bytes.length > MAX_METADATA_BYTES) {
        throw new IOException(
            "Workspace metadata exceeds 4 KiB; preserve it and inspect the marker.");
      }
      SafeWorkspaceFiles.verifyDirectories(directories);
      SafeWorkspaceFiles.requireEntryIdentity(directory, name, key);
      SafeWorkspaceFiles.requireEntryContents(directory, name, bytes);
      SafeWorkspaceFiles.requireEntryIdentity(directory, name, key);
      SafeWorkspaceFiles.verifyDirectories(directories);
      return Optional.of(decode(bytes));
    }
  }

  @Override
  public void createMetadata(Path root, WorkspaceMetadata metadata) throws IOException {
    safePath(root);
    if (loadMetadata(root).isPresent()) {
      throw new IOException(
          "Workspace identity already exists; preserve it and resume the matching course.");
    }
    byte[] bytes =
        ("courseId="
                + metadata.courseId().value()
                + "\nlayoutVersion="
                + metadata.layoutVersion()
                + "\n")
            .getBytes(StandardCharsets.UTF_8);
    files.writeNewSafely(root, MARKER, bytes);
  }

  static Path safePath(Path path) throws IOException {
    Path normalized = path.toAbsolutePath().normalize();
    Path current = Objects.requireNonNull(normalized.getRoot());
    for (Path segment : normalized) {
      current = current.resolve(segment);
      BasicFileAttributes observed;
      try {
        observed = SafeWorkspaceFiles.attributes(current);
      } catch (NoSuchFileException absent) {
        return normalized;
      }
      if (observed.isSymbolicLink() || !observed.isDirectory()) {
        throw new IOException(
            "Expected real directories without symlink ancestors; select a safe workspace path.");
      }
    }
    return normalized;
  }

  private static WorkspaceMetadata decode(byte[] bytes) throws IOException {
    try {
      Properties properties = new UniqueProperties();
      String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
      properties.load(new StringReader(text));
      if (!properties.stringPropertyNames().equals(Set.of("courseId", "layoutVersion"))) {
        throw new IllegalArgumentException("Expected only courseId and layoutVersion.");
      }
      return new WorkspaceMetadata(
          new CourseId(Objects.requireNonNull(properties.getProperty("courseId"))),
          Integer.parseInt(Objects.requireNonNull(properties.getProperty("layoutVersion"))));
    } catch (IllegalArgumentException malformed) {
      throw new IOException(
          "Invalid workspace metadata; preserve it and restore the matching courseId and layoutVersion.",
          malformed);
    }
  }

  private static final class UniqueProperties extends Properties {
    private static final long serialVersionUID = 1L;

    @Override
    public synchronized @Nullable Object put(Object key, Object value) {
      if (containsKey(key)) {
        throw new IllegalArgumentException("Duplicate workspace metadata field.");
      }
      return super.put(key, value);
    }
  }
}
