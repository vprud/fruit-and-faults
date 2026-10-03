package org.fruitandfaults.progress.infra;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Optional;

import org.fruitandfaults.progress.application.ProgressRepository;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.jspecify.annotations.Nullable;

/**
 * Stores progress in .fruit-and-faults/progress.json using flushed same-directory temporary files.
 * When ATOMIC_MOVE is unsupported, a replace move is used only after the new file is complete; the
 * previous file remains in place throughout writing. The fallback cannot promise crash-atomic
 * replacement on every filesystem. Failed-write cleanup uses the original directory handle and
 * temporary-file identity; when the provider cannot verify these safely, the temporary file is
 * retained rather than deleting through a possibly replaced pathname.
 */
public final class AtomicProgressRepository implements ProgressRepository {
  private final JacksonProgressCodec codec;
  private final DocumentWriter writer;
  private final DocumentMover mover;

  /**
   * Uses the local filesystem's durable write and replacement operations.
   *
   * @param codec strict codec for the installed course
   */
  public AtomicProgressRepository(JacksonProgressCodec codec) {
    this(codec, AtomicProgressRepository::writeFlushed, AtomicProgressRepository::move);
  }

  AtomicProgressRepository(JacksonProgressCodec codec, DocumentWriter writer, DocumentMover mover) {
    this.codec = Objects.requireNonNull(codec);
    this.writer = Objects.requireNonNull(writer);
    this.mover = Objects.requireNonNull(mover);
  }

  @Override
  public Optional<CourseProgress> load(Path workspaceRoot) throws IOException {
    Path file = stateFile(workspaceRoot);
    verifyStatePath(file);
    if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    try (FileChannel channel =
            FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        var input = Channels.newInputStream(channel)) {
      return Optional.of(
          codec.decode(input.readNBytes(JacksonProgressCodec.MAX_DOCUMENT_BYTES + 1)));
    }
  }

  @Override
  public void save(Path workspaceRoot, CourseProgress progress) throws IOException {
    Path file = stateFile(workspaceRoot);
    load(workspaceRoot);
    byte[] bytes = codec.encode(progress);
    if (Files.notExists(file.getParent(), LinkOption.NOFOLLOW_LINKS)) {
      Files.createDirectory(file.getParent());
    }
    verifyStatePath(file);
    try (DirectoryStream<Path> directory = Files.newDirectoryStream(file.getParent())) {
      Path temporary = Files.createTempFile(file.getParent(), "progress-", ".tmp");
      @Nullable Object temporaryKey =
          Files.readAttributes(temporary, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
              .fileKey();
      try {
        writer.write(temporary, bytes);
        verifyStatePath(file);
        try {
          mover.move(temporary, file, true);
        } catch (AtomicMoveNotSupportedException unsupported) {
          verifyStatePath(file);
          mover.move(temporary, file, false);
        }
      } finally {
        cleanupOwnedTemporary(directory, temporary, temporaryKey);
      }
    }
  }

  private static void cleanupOwnedTemporary(
      DirectoryStream<Path> directory, Path temporary, @Nullable Object originalKey)
      throws IOException {
    if (!(directory instanceof SecureDirectoryStream<Path> secure) || originalKey == null) {
      return;
    }
    Path basename = Objects.requireNonNull(temporary.getFileName());
    try {
      BasicFileAttributeView view =
          secure.getFileAttributeView(
              basename, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
      if (view != null && originalKey.equals(view.readAttributes().fileKey())) {
        secure.deleteFile(basename);
      }
    } catch (NoSuchFileException alreadyMoved) {
      // Successful replacement has already removed the temporary directory entry.
    }
  }

  private static Path stateFile(Path workspaceRoot) throws IOException {
    Path root = workspaceRoot.toAbsolutePath().normalize();
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
      throw new IOException("Expected an existing workspace directory, not a symlink.");
    }
    return root.toRealPath().resolve(".fruit-and-faults/progress.json");
  }

  private static void verifyStatePath(Path file) throws IOException {
    Path directory = Objects.requireNonNull(file.getParent());
    if (Files.isSymbolicLink(directory)
        || (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
            && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))) {
      throw new IOException(
          "Expected a real workspace metadata directory; symlinks are not allowed.");
    }
    if (Files.isSymbolicLink(file)
        || (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
            && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))) {
      throw new IOException("Expected a regular progress file; symlinks are not allowed.");
    }
  }

  private static void writeFlushed(Path file, byte[] bytes) throws IOException {
    try (FileChannel channel =
        FileChannel.open(
            file,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS)) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    }
  }

  private static void move(Path source, Path target, boolean atomic) throws IOException {
    if (atomic) {
      Files.move(
          source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } else {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  @FunctionalInterface
  interface DocumentWriter {
    void write(Path file, byte[] bytes) throws IOException;
  }

  @FunctionalInterface
  interface DocumentMover {
    void move(Path source, Path target, boolean atomic) throws IOException;
  }
}
