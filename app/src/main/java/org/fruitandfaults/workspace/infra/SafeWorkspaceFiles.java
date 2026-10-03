package org.fruitandfaults.workspace.infra;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.fruitandfaults.workspace.application.WorkspaceFiles;
import org.fruitandfaults.workspace.application.WorkspaceWriteException;
import org.fruitandfaults.workspace.domain.DisclosureConflict;
import org.fruitandfaults.workspace.domain.DisclosurePlan;
import org.fruitandfaults.workspace.domain.ManagedFile;
import org.fruitandfaults.workspace.domain.ManagedFiles;
import org.fruitandfaults.workspace.domain.WorkspacePath;
import org.jspecify.annotations.Nullable;

/**
 * Rejects workspace symlinks and portable aliases. Learner destinations are reserved and written
 * through a verified secure parent handle with CREATE_NEW. Creation is exclusive and confined, but
 * bytes are visible during writing. An ordinary failure removes only the identified created entry;
 * a crash may leave partial bytes for disclosure-journal recovery. Providers without secure
 * handles, stable file keys, or flushable channels fail safely.
 */
public final class SafeWorkspaceFiles implements WorkspaceFiles {
  static final int MAX_ASSET_BYTES = 16_777_216;
  private final DocumentWriter writer;
  private final EntryCreator creator;
  private final IdentityReader identityReader;

  /** Uses directory-relative exclusive creation and flushed channel writes. */
  public SafeWorkspaceFiles() {
    this(SafeWorkspaceFiles::writeFlushed, SafeWorkspaceFiles::createNewChannel);
  }

  SafeWorkspaceFiles(DocumentWriter writer, EntryCreator creator) {
    this(writer, creator, BasicFileAttributes::fileKey);
  }

  SafeWorkspaceFiles(DocumentWriter writer, EntryCreator creator, IdentityReader identityReader) {
    this.writer = Objects.requireNonNull(writer);
    this.creator = Objects.requireNonNull(creator);
    this.identityReader = Objects.requireNonNull(identityReader);
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
    conflicts.addAll(managed.aliasConflicts());
    Map<String, WorkspacePath> aliases = new LinkedHashMap<>();
    for (ManagedFile known : managed.files()) {
      aliases.putIfAbsent(known.path().aliasKey(), known.path());
    }
    Set<String> requestedPaths = new HashSet<>();
    for (ManagedFile file : requested) {
      requestedPaths.add(file.path().aliasKey());
      WorkspacePath alias = aliases.putIfAbsent(file.path().aliasKey(), file.path());
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
        if (requestedPaths.contains(
            WorkspacePath.parse(value.substring(0, separator)).aliasKey())) {
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
    return conflicts.isEmpty()
        ? plan
        : new DisclosurePlan.Conflicted(conflicts.stream().distinct().toList());
  }

  @Override
  public void writeNewSafely(Path root, DisclosurePlan plan, Map<WorkspacePath, byte[]> contents)
      throws IOException {
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
      writeNewSafely(root, file.path(), Objects.requireNonNull(verifiedBytes.get(file.path())));
    }
  }

  @Override
  public void writeNewSafely(Path root, WorkspacePath path, byte[] bytes) throws IOException {
    if (bytes.length > MAX_ASSET_BYTES) {
      throw new IOException("Asset exceeds the supported 16 MiB size.");
    }
    Path realRoot = verifiedRoot(root);
    Path target = verifiedTarget(realRoot, path);
    requireMissing(target, path);
    requireStableKey(attributes(realRoot), identityReader);
    createVerifiedParents(realRoot, target);
    List<DirectoryIdentity> directories = directoryIdentities(realRoot, target.getParent());
    verifyDirectories(directories);
    try (SecureDirectoryStream<Path> directory =
        openVerifiedDirectory(
            realRoot, Objects.requireNonNull(target.getParent()), directories, identityReader)) {
      Path name = Objects.requireNonNull(target.getFileName());
      @Nullable Object key = null;
      try {
        verifyRegularFileIdentitySupport(directory, identityReader);
        verifyDirectories(directories);
        try (SeekableByteChannel channel = creator.create(directory, name)) {
          key = requireStableKey(attributes(directory, name), identityReader);
          FileChannel fileChannel = requireFlushable(channel);
          writer.write(fileChannel, bytes.clone());
          fileChannel.force(true);
        }
        verifyDirectories(directories);
        requireEntryIdentity(directory, name, key);
      } catch (IOException | RuntimeException failed) {
        cleanupOwnedTemporary(directory, name, key);
        if (failed instanceof WorkspaceWriteException typed) {
          throw typed;
        }
        if (failed instanceof UnsupportedOperationException
            || failed instanceof AtomicMoveNotSupportedException) {
          throw unsupported(
              "Directory-relative exclusive creation is unavailable; select a supported local filesystem.",
              failed);
        }
        throw new WorkspaceWriteException(
            WorkspaceWriteException.Reason.PUBLICATION_FAILED,
            "Asset creation failed; inspect the target and retry. Existing learner entries are preserved.",
            failed);
      }
    }
  }

  // Transfers the surviving handle to try-with-resources callers; rejected and superseded handles
  // are explicitly closed. Error Prone cannot follow that ownership through the pattern variable.
  @SuppressWarnings("StreamResourceLeak")
  static SecureDirectoryStream<Path> openVerifiedDirectory(
      Path root, Path parent, List<DirectoryIdentity> identities, IdentityReader identityReader)
      throws IOException {
    DirectoryStream<Path> opened = Files.newDirectoryStream(root);
    if (!(opened instanceof SecureDirectoryStream<Path> secure)) {
      opened.close();
      throw unsupported(
          "Secure directory handles are unavailable; select a supported local filesystem.",
          new UnsupportedOperationException("SecureDirectoryStream unavailable"));
    }
    try {
      int index = 0;
      requireDirectoryIdentity(secure, identities.get(index++), identityReader);
      for (Path segment : root.relativize(parent)) {
        if (!segment.toString().isEmpty()) {
          SecureDirectoryStream<Path> next =
              secure.newDirectoryStream(segment, LinkOption.NOFOLLOW_LINKS);
          try {
            secure.close();
          } catch (IOException failedClose) {
            next.close();
            throw failedClose;
          }
          secure = next;
          requireDirectoryIdentity(secure, identities.get(index++), identityReader);
        }
      }
      return secure;
    } catch (IOException | RuntimeException failed) {
      secure.close();
      throw failed;
    }
  }

  private static void verifyRegularFileIdentitySupport(
      SecureDirectoryStream<Path> directory, IdentityReader identityReader) throws IOException {
    Path probe = Path.of("identity-" + UUID.randomUUID() + ".tmp");
    @Nullable Object key = null;
    try {
      try (SeekableByteChannel channel = createNewChannel(directory, probe)) {
        key = requireStableKey(attributes(directory, probe), identityReader);
        requireFlushable(channel);
      }
    } finally {
      cleanupOwnedTemporary(directory, probe, key);
    }
  }

  private static void requireDirectoryIdentity(
      SecureDirectoryStream<Path> directory,
      DirectoryIdentity expected,
      IdentityReader identityReader)
      throws IOException {
    BasicFileAttributeView view = directory.getFileAttributeView(BasicFileAttributeView.class);
    if (view == null) {
      throw unsupported(
          "Directory identity is unavailable; select a supported filesystem.",
          new UnsupportedOperationException("Basic directory attributes unavailable"));
    }
    Object observed = requireStableKey(view.readAttributes(), identityReader);
    if (!observed.equals(expected.key())) {
      throw new WorkspaceWriteException(
          WorkspaceWriteException.Reason.PUBLICATION_FAILED,
          "Workspace directory changed while opening; stop concurrent moves and retry.",
          new IOException("Directory identity changed"));
    }
  }

  static Object requireStableKey(BasicFileAttributes attributes, IdentityReader identityReader)
      throws WorkspaceWriteException {
    Object key = identityReader.key(attributes);
    if (key == null) {
      throw unsupported(
          "Filesystem identity is unavailable; select a filesystem that provides stable file keys.",
          new UnsupportedOperationException("Missing fileKey"));
    }
    return key;
  }

  static SeekableByteChannel createNewChannel(SecureDirectoryStream<Path> directory, Path name)
      throws IOException {
    return directory.newByteChannel(
        name,
        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
  }

  static FileChannel requireFlushable(SeekableByteChannel channel) throws WorkspaceWriteException {
    if (!(channel instanceof FileChannel fileChannel)) {
      throw unsupported(
          "Filesystem channels cannot be durably flushed; select a supported filesystem.",
          new UnsupportedOperationException("FileChannel unavailable"));
    }
    return fileChannel;
  }

  static BasicFileAttributes attributes(SecureDirectoryStream<Path> directory, Path name)
      throws IOException {
    BasicFileAttributeView view =
        directory.getFileAttributeView(
            name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (view == null) {
      throw unsupported(
          "File identity is unavailable; select a supported filesystem.",
          new UnsupportedOperationException("Basic file attributes unavailable"));
    }
    return view.readAttributes();
  }

  static void requireEntryIdentity(
      SecureDirectoryStream<Path> directory, Path name, @Nullable Object key) throws IOException {
    BasicFileAttributes observed = attributes(directory, name);
    if (key == null) {
      throw unsupported(
          "Filesystem identity is unavailable; preserve the created entry for recovery.",
          new UnsupportedOperationException("Missing fileKey"));
    }
    if (!observed.isRegularFile() || observed.isSymbolicLink() || !key.equals(observed.fileKey())) {
      throw new IOException(
          "Created entry changed during writing; preserve the replacement and inspect ownership.");
    }
  }

  static WorkspaceWriteException unsupported(String message, Throwable cause) {
    return new WorkspaceWriteException(
        WorkspaceWriteException.Reason.UNSUPPORTED_PUBLICATION, message, cause);
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

  static void writeFlushed(FileChannel channel, byte[] bytes) throws IOException {
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    while (buffer.hasRemaining()) {
      channel.write(buffer);
    }
    channel.force(true);
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

  record DirectoryIdentity(Path path, @Nullable Object key) {}

  @FunctionalInterface
  interface DocumentWriter {
    void write(FileChannel channel, byte[] bytes) throws IOException;
  }

  @FunctionalInterface
  interface EntryCreator {
    SeekableByteChannel create(SecureDirectoryStream<Path> directory, Path name) throws IOException;
  }

  @FunctionalInterface
  interface IdentityReader {
    @Nullable Object key(BasicFileAttributes attributes);
  }

  private static final class UnsafePathException extends IOException {
    private static final long serialVersionUID = 1L;

    private UnsafePathException() {
      super(
          "Expected a regular target and real directory parents; move symlinks or conflicting paths aside before continuing.");
    }
  }
}
