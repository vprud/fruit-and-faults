package org.fruitandfaults.workspace.infra;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.fruitandfaults.workspace.application.WorkspaceFiles;
import org.fruitandfaults.workspace.application.WorkspaceWriteException;
import org.fruitandfaults.workspace.domain.DisclosureConflict;
import org.fruitandfaults.workspace.domain.DisclosurePlan;
import org.fruitandfaults.workspace.domain.ManagedFile;
import org.fruitandfaults.workspace.domain.ManagedFiles;
import org.fruitandfaults.workspace.domain.WorkspacePath;
import org.jspecify.annotations.Nullable;

/**
 * Rejects all workspace symlinks and conservatively rejects case/Unicode aliases on every host. New
 * assets use an exclusive hard link to flushed same-directory temporary bytes; no overwriting move
 * fallback is permitted. Directory and temporary-file identities are rechecked before publication.
 * Failed cleanup uses the original secure directory handle and file identity, retaining the
 * temporary when the provider cannot safely identify it. Java has no public directory-relative
 * hard-link primitive, so hostile simultaneous parent renames during publication cannot be fully
 * excluded by this adapter's pathname checks.
 */
public final class SafeWorkspaceFiles implements WorkspaceFiles {
  static final int MAX_ASSET_BYTES = 16_777_216;
  private final DocumentWriter writer;
  private final DocumentPublisher publisher;

  /** Uses flushed writes and an exclusive no-replacement publication operation. */
  public SafeWorkspaceFiles() {
    this(
        SafeWorkspaceFiles::writeFlushed,
        (temporary, target) -> Files.createLink(target, temporary));
  }

  SafeWorkspaceFiles(DocumentWriter writer, DocumentPublisher publisher) {
    this.writer = Objects.requireNonNull(writer);
    this.publisher = Objects.requireNonNull(publisher);
  }

  @Override
  public DisclosurePlan.Observation inspect(Path root, WorkspacePath path) throws IOException {
    Path realRoot = verifiedRoot(root);
    Path target;
    try {
      target = verifiedTarget(realRoot, path);
    } catch (UnsafePathException unsafe) {
      return new DisclosurePlan.UnsafePath();
    }
    if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
      return new DisclosurePlan.Missing();
    }
    List<DirectoryIdentity> directories = directoryIdentities(realRoot, target.getParent());
    byte[] bytes;
    try (FileChannel channel =
        FileChannel.open(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      if (channel.size() > MAX_ASSET_BYTES) {
        throw new IOException(
            "Asset exceeds the supported 16 MiB size; reduce it before continuing.");
      }
      ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
      while (buffer.hasRemaining() && channel.read(buffer) != -1) {}
      bytes = java.util.Arrays.copyOf(buffer.array(), buffer.position());
      if (channel.size() > MAX_ASSET_BYTES || channel.size() != bytes.length) {
        throw new IOException("Asset changed during inspection; retry after edits finish.");
      }
    }
    verifyDirectories(directories);
    verifiedTarget(realRoot, path);
    return new DisclosurePlan.RegularFile(sha256(bytes));
  }

  @Override
  public DisclosurePlan preflight(Path root, List<ManagedFile> requested, ManagedFiles managed)
      throws IOException {
    verifiedRoot(root);
    Map<WorkspacePath, DisclosurePlan.Observation> observations = new LinkedHashMap<>();
    List<DisclosureConflict> conflicts = new ArrayList<>();
    Map<String, WorkspacePath> aliases = new LinkedHashMap<>();
    for (ManagedFile known : managed.files()) {
      aliases.put(fold(known.path().value()), known.path());
    }
    Set<String> requestedPaths = new HashSet<>();
    for (ManagedFile file : requested) {
      requestedPaths.add(fold(file.path().value()));
      WorkspacePath alias = aliases.putIfAbsent(fold(file.path().value()), file.path());
      if (alias != null && !alias.equals(file.path())) {
        conflicts.add(
            new DisclosureConflict(file.path(), DisclosureConflict.Reason.CASE_COLLISION));
      }
      observations.put(file.path(), inspect(root, file.path()));
    }
    for (ManagedFile file : requested) {
      String value = file.path().value();
      int separator = value.indexOf('/');
      while (separator != -1) {
        if (requestedPaths.contains(fold(value.substring(0, separator)))) {
          conflicts.add(
              new DisclosureConflict(file.path(), DisclosureConflict.Reason.TARGET_ANCESTOR));
          break;
        }
        separator = value.indexOf('/', separator + 1);
      }
    }
    DisclosurePlan plan = DisclosurePlan.evaluate(requested, managed, observations);
    if (plan instanceof DisclosurePlan.Conflicted rejected) {
      conflicts.addAll(rejected.conflicts());
    }
    return conflicts.isEmpty() ? plan : new DisclosurePlan.Conflicted(conflicts);
  }

  @Override
  public void writeNewAtomically(
      Path root, DisclosurePlan plan, Map<WorkspacePath, byte[]> contents) throws IOException {
    if (!(plan instanceof DisclosurePlan.Applicable applicable)) {
      throw new IOException("Disclosure has conflicts; resolve the listed paths before applying.");
    }
    List<ManagedFile> requested = new ArrayList<>(applicable.filesToCreate());
    requested.addAll(applicable.alreadyApplied());
    DisclosurePlan refreshed =
        preflight(root, requested, new ManagedFiles(applicable.alreadyApplied()));
    if (!(refreshed instanceof DisclosurePlan.Applicable current)) {
      throw new IOException(
          "Workspace changed after preflight; inspect the disclosure conflicts again.");
    }
    Map<WorkspacePath, byte[]> verifiedBytes = new LinkedHashMap<>();
    for (ManagedFile file : current.filesToCreate()) {
      byte[] raw = contents.get(file.path());
      if (raw == null || raw.length > MAX_ASSET_BYTES || !sha256(raw).equals(file.sha256())) {
        throw new IOException(
            "Expected disclosed bytes for "
                + file.path().value()
                + "; check installed course content.");
      }
      verifiedBytes.put(file.path(), raw.clone());
    }
    for (ManagedFile file : current.filesToCreate()) {
      writeNewAtomically(root, file.path(), Objects.requireNonNull(verifiedBytes.get(file.path())));
    }
  }

  @Override
  public void writeNewAtomically(Path root, WorkspacePath path, byte[] bytes) throws IOException {
    if (bytes.length > MAX_ASSET_BYTES) {
      throw new IOException("Asset exceeds the supported 16 MiB size.");
    }
    Path realRoot = verifiedRoot(root);
    Path target = verifiedTarget(realRoot, path);
    requireMissing(target, path);
    createVerifiedParents(realRoot, target);
    List<DirectoryIdentity> directories = directoryIdentities(realRoot, target.getParent());
    verifyDirectories(directories);
    try (DirectoryStream<Path> directory =
        Files.newDirectoryStream(Objects.requireNonNull(target.getParent()))) {
      Path temporary = Files.createTempFile(target.getParent(), "asset-", ".tmp");
      @Nullable Object key = attributes(temporary).fileKey();
      try {
        writer.write(temporary, bytes.clone());
        verifyDirectories(directories);
        verifiedTarget(realRoot, path);
        requireMissing(target, path);
        requireTemporaryIdentity(temporary, key);
        try {
          publisher.publish(temporary, target);
        } catch (UnsupportedOperationException | AtomicMoveNotSupportedException unsupported) {
          throw new WorkspaceWriteException(
              WorkspaceWriteException.Reason.UNSUPPORTED_PUBLICATION,
              "This filesystem cannot exclusively publish complete assets. Select a local filesystem with hard-link support.",
              unsupported);
        } catch (IOException failed) {
          throw new WorkspaceWriteException(
              WorkspaceWriteException.Reason.PUBLICATION_FAILED,
              "Asset publication failed; existing learner files were preserved. Inspect the target and retry.",
              failed);
        }
      } finally {
        cleanupOwnedTemporary(directory, temporary, key);
      }
    }
  }

  static Path verifiedRoot(Path root) throws IOException {
    Path normalized = root.toAbsolutePath().normalize();
    if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(normalized)) {
      throw new IOException(
          "Expected an existing real workspace directory; select a directory rather than a symlink.");
    }
    return normalized.toRealPath();
  }

  static Path verifiedTarget(Path root, WorkspacePath path) throws IOException {
    Path target = root.resolve(path.value()).normalize();
    if (!target.startsWith(root)) {
      throw new UnsafePathException();
    }
    Path current = root;
    String[] segments = path.value().split("/", -1);
    for (int index = 0; index < segments.length; index++) {
      current = current.resolve(segments[index]);
      BasicFileAttributes observed;
      try {
        observed = attributes(current);
      } catch (NoSuchFileException missing) {
        return target;
      }
      if (observed.isSymbolicLink()
          || (index < segments.length - 1 ? !observed.isDirectory() : !observed.isRegularFile())) {
        throw new UnsafePathException();
      }
    }
    return target;
  }

  static void createVerifiedParents(Path root, Path target) throws IOException {
    Path parent = Objects.requireNonNull(target.getParent());
    Path current = root;
    for (Path segment : root.relativize(parent)) {
      if (segment.toString().isEmpty()) {
        continue;
      }
      current = current.resolve(segment);
      if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) {
        directoryIdentities(root, current.getParent());
        try {
          Files.createDirectory(current);
        } catch (java.nio.file.FileAlreadyExistsException appeared) {
          // Inspect the concurrently created entry before using it.
        }
      }
      if (!attributes(current).isDirectory() || Files.isSymbolicLink(current)) {
        throw new UnsafePathException();
      }
    }
  }

  static List<DirectoryIdentity> directoryIdentities(Path root, @Nullable Path parent)
      throws IOException {
    List<DirectoryIdentity> identities = new ArrayList<>();
    Path current = root;
    identities.add(directoryIdentity(current));
    for (Path segment : root.relativize(Objects.requireNonNull(parent))) {
      if (!segment.toString().isEmpty()) {
        current = current.resolve(segment);
        identities.add(directoryIdentity(current));
      }
    }
    return List.copyOf(identities);
  }

  static void verifyDirectories(List<DirectoryIdentity> identities) throws IOException {
    for (DirectoryIdentity identity : identities) {
      if (!identity.equals(directoryIdentity(identity.path()))) {
        throw new IOException(
            "Workspace directory changed during access; stop concurrent moves and retry.");
      }
    }
  }

  private static DirectoryIdentity directoryIdentity(Path path) throws IOException {
    BasicFileAttributes observed = attributes(path);
    if (!observed.isDirectory() || observed.isSymbolicLink()) {
      throw new UnsafePathException();
    }
    return new DirectoryIdentity(path, observed.fileKey());
  }

  static BasicFileAttributes attributes(Path path) throws IOException {
    return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
  }

  static void requireTemporaryIdentity(Path temporary, @Nullable Object key) throws IOException {
    BasicFileAttributes observed = attributes(temporary);
    if (key == null
        || !observed.isRegularFile()
        || observed.isSymbolicLink()
        || !key.equals(observed.fileKey())) {
      throw new IOException(
          "Temporary asset identity changed or cannot be verified; retry on a supported local filesystem.");
    }
  }

  static void cleanupOwnedTemporary(
      DirectoryStream<Path> directory, Path temporary, @Nullable Object key) throws IOException {
    if (!(directory instanceof SecureDirectoryStream<Path> secure) || key == null) {
      return;
    }
    Path basename = Objects.requireNonNull(temporary.getFileName());
    try {
      BasicFileAttributeView view =
          secure.getFileAttributeView(
              basename, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
      if (view != null && key.equals(view.readAttributes().fileKey())) {
        secure.deleteFile(basename);
      }
    } catch (NoSuchFileException alreadyRemoved) {
      // Successful metadata moves have already removed the owned temporary entry.
    }
  }

  static void writeFlushed(Path file, byte[] bytes) throws IOException {
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

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("Java must provide SHA-256.", unavailable);
    }
  }

  private static void requireMissing(Path target, WorkspacePath path) throws IOException {
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException(
          "Existing destination "
              + path.value()
              + " is preserved; inspect ownership before continuing.");
    }
  }

  private static String fold(String path) {
    return Normalizer.normalize(path, Normalizer.Form.NFD).toLowerCase(Locale.ROOT);
  }

  record DirectoryIdentity(Path path, @Nullable Object key) {}

  @FunctionalInterface
  interface DocumentWriter {
    void write(Path file, byte[] bytes) throws IOException;
  }

  @FunctionalInterface
  interface DocumentPublisher {
    void publish(Path temporary, Path target) throws IOException;
  }

  private static final class UnsafePathException extends IOException {
    private static final long serialVersionUID = 1L;

    private UnsafePathException() {
      super(
          "Expected a regular target and real directory parents; move symlinks or conflicting paths aside before continuing.");
    }
  }
}
