package org.fruitandfaults.workspace.infra;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.fruitandfaults.course.domain.AssetId;
import org.fruitandfaults.course.domain.AssetPolicy;
import org.fruitandfaults.course.domain.LessonId;
import org.fruitandfaults.workspace.application.ManagedFilesReadException;
import org.fruitandfaults.workspace.application.ManagedFilesRepository;
import org.fruitandfaults.workspace.application.WorkspaceWriteException;
import org.fruitandfaults.workspace.domain.ManagedFile;
import org.fruitandfaults.workspace.domain.ManagedFiles;
import org.fruitandfaults.workspace.domain.WorkspacePath;
import org.jspecify.annotations.Nullable;

/**
 * Strict version-one ownership JSON in .fruit-and-faults/managed-files.json. Existing malformed or
 * future documents are preserved. Same-directory temporary creation, writes, exclusive initial
 * creation, and atomic replacement use a verified secure directory handle. Initialization writes
 * directly to a reserved entry; a crash may leave an invalid partial document that is preserved.
 * Unsupported atomic replacement fails without a destructive fallback. Cleanup verifies the
 * original entry identity through the same handle.
 */
public final class JacksonManagedFilesRepository implements ManagedFilesRepository {
  static final int MAX_DOCUMENT_BYTES = 1_048_576;
  private static final WorkspacePath MANIFEST =
      WorkspacePath.parse(".fruit-and-faults/managed-files.json");
  private static final Set<String> ROOT_FIELDS = Set.of("formatVersion", "files");
  private static final Set<String> FILE_FIELDS =
      Set.of("path", "assetId", "sha256", "lessonId", "policy");
  private final ObjectMapper mapper =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final SafeWorkspaceFiles.DocumentWriter writer;
  private final DocumentMover mover;
  private final SafeWorkspaceFiles.IdentityReader identityReader;
  private final SafeWorkspaceFiles.EntryCreator creator;

  /** Uses strict JSON validation, exclusive initialization, and secure atomic replacement. */
  public JacksonManagedFilesRepository() {
    this(
        SafeWorkspaceFiles::writeFlushed,
        (directory, temporary, target) -> directory.move(temporary, directory, target));
  }

  JacksonManagedFilesRepository(SafeWorkspaceFiles.DocumentWriter writer, DocumentMover mover) {
    this(writer, mover, BasicFileAttributes::fileKey);
  }

  JacksonManagedFilesRepository(
      SafeWorkspaceFiles.DocumentWriter writer,
      DocumentMover mover,
      SafeWorkspaceFiles.IdentityReader identityReader) {
    this(writer, mover, identityReader, SafeWorkspaceFiles::createNewChannel);
  }

  JacksonManagedFilesRepository(
      SafeWorkspaceFiles.DocumentWriter writer,
      DocumentMover mover,
      SafeWorkspaceFiles.IdentityReader identityReader,
      SafeWorkspaceFiles.EntryCreator creator) {
    this.writer = Objects.requireNonNull(writer);
    this.mover = Objects.requireNonNull(mover);
    this.identityReader = Objects.requireNonNull(identityReader);
    this.creator = Objects.requireNonNull(creator);
  }

  @Override
  public Optional<ManagedFiles> load(Path workspaceRoot) throws IOException {
    return read(workspaceRoot).map(StoredManifest::managed);
  }

  @Override
  public void save(Path workspaceRoot, ManagedFiles managed) throws IOException {
    Path root = SafeWorkspaceFiles.verifiedRoot(workspaceRoot);
    SafeWorkspaceFiles.requireStableKey(SafeWorkspaceFiles.attributes(root), identityReader);
    Optional<StoredManifest> previous = read(root);
    byte[] bytes = encode(managed);
    Path file = SafeWorkspaceFiles.verifiedTarget(root, MANIFEST);
    SafeWorkspaceFiles.createVerifiedParents(root, file);
    List<SafeWorkspaceFiles.DirectoryIdentity> directories =
        SafeWorkspaceFiles.directoryIdentities(root, file.getParent());
    try (SecureDirectoryStream<Path> directory =
        SafeWorkspaceFiles.openVerifiedDirectory(
            root, Objects.requireNonNull(file.getParent()), directories, identityReader)) {
      Path temporary = Path.of("managed-files-" + UUID.randomUUID() + ".tmp");
      Path target = Objects.requireNonNull(file.getFileName());
      @Nullable Object key = null;
      try {
        try (SeekableByteChannel channel =
            SafeWorkspaceFiles.createNewChannel(directory, temporary)) {
          key =
              SafeWorkspaceFiles.requireStableKey(
                  SafeWorkspaceFiles.attributes(directory, temporary), identityReader);
          FileChannel fileChannel = SafeWorkspaceFiles.requireFlushable(channel);
          writer.write(fileChannel, bytes);
          fileChannel.force(true);
        }
        verifyBeforeReplacement(root, previous, directories, directory, temporary, key);
        if (previous.isEmpty()) {
          initialize(directory, target, bytes, directories);
        } else {
          mover.move(directory, temporary, target);
        }
        SafeWorkspaceFiles.verifyDirectories(directories);
      } catch (WorkspaceWriteException | ManagedFilesReadException invalid) {
        throw invalid;
      } catch (UnsupportedOperationException | AtomicMoveNotSupportedException unsupported) {
        throw SafeWorkspaceFiles.unsupported(
            "Directory-relative manifest publication is unavailable; existing state is preserved. Select a supported filesystem.",
            unsupported);
      } catch (IOException failed) {
        throw new WorkspaceWriteException(
            WorkspaceWriteException.Reason.PUBLICATION_FAILED,
            "Manifest replacement failed; preserve the current state and retry after inspecting the workspace.",
            failed);
      } finally {
        SafeWorkspaceFiles.cleanupOwnedTemporary(directory, temporary, key);
      }
    }
  }

  private void initialize(
      SecureDirectoryStream<Path> directory,
      Path target,
      byte[] bytes,
      List<SafeWorkspaceFiles.DirectoryIdentity> directories)
      throws IOException {
    @Nullable Object key = null;
    try {
      try (SeekableByteChannel channel = creator.create(directory, target)) {
        key =
            SafeWorkspaceFiles.requireStableKey(
                SafeWorkspaceFiles.attributes(directory, target), identityReader);
        FileChannel fileChannel = SafeWorkspaceFiles.requireFlushable(channel);
        writer.write(fileChannel, bytes);
        fileChannel.force(true);
      }
      SafeWorkspaceFiles.verifyDirectories(directories);
      SafeWorkspaceFiles.requireEntryIdentity(directory, target, key);
    } catch (IOException | RuntimeException failed) {
      SafeWorkspaceFiles.cleanupOwnedTemporary(directory, target, key);
      throw failed;
    }
  }

  private void verifyBeforeReplacement(
      Path root,
      Optional<StoredManifest> previous,
      List<SafeWorkspaceFiles.DirectoryIdentity> directories,
      SecureDirectoryStream<Path> directory,
      Path temporary,
      @Nullable Object key)
      throws IOException {
    SafeWorkspaceFiles.verifyDirectories(directories);
    SafeWorkspaceFiles.requireEntryIdentity(directory, temporary, key);
    if (!previous.equals(read(root))) {
      throw new IOException(
          "Ownership manifest changed during writing; preserve the current file and retry.");
    }
  }

  private Optional<StoredManifest> read(Path workspaceRoot) throws IOException {
    Path root = SafeWorkspaceFiles.verifiedRoot(workspaceRoot);
    Path file = SafeWorkspaceFiles.verifiedTarget(root, MANIFEST);
    if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    List<SafeWorkspaceFiles.DirectoryIdentity> directories =
        SafeWorkspaceFiles.directoryIdentities(root, file.getParent());
    @Nullable Object key = SafeWorkspaceFiles.attributes(file).fileKey();
    byte[] bytes;
    try (FileChannel channel =
            FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        var input = Channels.newInputStream(channel)) {
      bytes = input.readNBytes(MAX_DOCUMENT_BYTES + 1);
    }
    SafeWorkspaceFiles.verifyDirectories(directories);
    SafeWorkspaceFiles.verifiedTarget(root, MANIFEST);
    if (!Objects.equals(key, SafeWorkspaceFiles.attributes(file).fileKey())) {
      throw new IOException("Ownership manifest changed during reading; retry after edits finish.");
    }
    return Optional.of(new StoredManifest(decode(bytes), key, SafeWorkspaceFiles.sha256(bytes)));
  }

  private ManagedFiles decode(byte[] bytes) throws IOException {
    if (bytes.length > MAX_DOCUMENT_BYTES) {
      throw failure(
          ManagedFilesReadException.Reason.MALFORMED, "Document exceeds the supported 1 MiB size.");
    }
    JsonNode root;
    try {
      root = mapper.readTree(bytes);
    } catch (JsonProcessingException invalid) {
      throw failure(
          ManagedFilesReadException.Reason.MALFORMED,
          "Expected one valid JSON object; repair or restore the document.");
    }
    if (root == null || !root.isObject()) {
      throw failure(ManagedFilesReadException.Reason.MALFORMED, "Expected a JSON object.");
    }
    JsonNode format = required(root, "formatVersion");
    if (!format.isIntegralNumber() || !format.canConvertToInt()) {
      throw failure(
          ManagedFilesReadException.Reason.MALFORMED,
          "Expected an integer format version without coercion.");
    }
    if (format.intValue() != 1) {
      throw failure(
          ManagedFilesReadException.Reason.UNSUPPORTED_FORMAT,
          "Expected format version 1; observed "
              + format.intValue()
              + ". Use a compatible CLI; the manifest is preserved.");
    }
    requireFields(root, ROOT_FIELDS);
    JsonNode entries = required(root, "files");
    if (!entries.isArray()) {
      throw failure(ManagedFilesReadException.Reason.MALFORMED, "Expected a files array.");
    }
    List<ManagedFile> files = new ArrayList<>();
    try {
      for (JsonNode entry : entries) {
        requireFields(entry, FILE_FIELDS);
        files.add(
            new ManagedFile(
                WorkspacePath.parse(text(required(entry, "path"))),
                new AssetId(text(required(entry, "assetId"))),
                text(required(entry, "sha256")),
                new LessonId(text(required(entry, "lessonId"))),
                AssetPolicy.valueOf(text(required(entry, "policy")))));
      }
      ManagedFiles managed = new ManagedFiles(files);
      requireUnambiguous(managed);
      return managed;
    } catch (IllegalArgumentException invalid) {
      throw failure(
          ManagedFilesReadException.Reason.INVALID_STATE,
          "Expected unique normalized paths, stable identities, disclosed SHA-256, and exact asset policies. Repair or restore ownership state.");
    }
  }

  private byte[] encode(ManagedFiles managed) throws IOException {
    requireUnambiguous(managed);
    List<FileDocument> files =
        managed.files().stream()
            .map(
                file ->
                    new FileDocument(
                        file.path().value(),
                        file.assetId().value(),
                        file.sha256(),
                        file.lessonId().value(),
                        file.policy().name()))
            .toList();
    byte[] bytes =
        mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(new ManifestDocument(1, files));
    if (bytes.length > MAX_DOCUMENT_BYTES) {
      throw failure(
          ManagedFilesReadException.Reason.INVALID_STATE,
          "Ownership snapshot exceeds the supported document size.");
    }
    return bytes;
  }

  private static void requireUnambiguous(ManagedFiles managed) throws ManagedFilesReadException {
    if (!managed.aliasConflicts().isEmpty()) {
      throw failure(
          ManagedFilesReadException.Reason.INVALID_STATE,
          "Case or Unicode aliases make ownership ambiguous; repair or restore the manifest.");
    }
  }

  private static void requireFields(JsonNode node, Set<String> expected)
      throws ManagedFilesReadException {
    if (!node.isObject()) {
      throw failure(ManagedFilesReadException.Reason.MALFORMED, "Expected an ownership object.");
    }
    Set<String> observed = new HashSet<>();
    node.fieldNames().forEachRemaining(observed::add);
    if (!observed.equals(expected)) {
      throw failure(
          ManagedFilesReadException.Reason.MALFORMED,
          "Expected exactly the version-one ownership fields.");
    }
  }

  private static JsonNode required(JsonNode node, String field) throws ManagedFilesReadException {
    JsonNode value = node.get(field);
    if (value == null) {
      throw failure(
          ManagedFilesReadException.Reason.MALFORMED, "Missing required field " + field + ".");
    }
    return value;
  }

  private static String text(JsonNode value) throws ManagedFilesReadException {
    if (!value.isTextual()) {
      throw failure(
          ManagedFilesReadException.Reason.MALFORMED,
          "Expected string ownership facts without coercion.");
    }
    return Objects.requireNonNull(value.textValue());
  }

  private static ManagedFilesReadException failure(
      ManagedFilesReadException.Reason reason, String message) {
    return new ManagedFilesReadException(reason, "Invalid managed-files manifest: " + message);
  }

  private record StoredManifest(ManagedFiles managed, @Nullable Object key, String sha256) {}

  private record ManifestDocument(int formatVersion, List<FileDocument> files) {}

  private record FileDocument(
      String path, String assetId, String sha256, String lessonId, String policy) {}

  @FunctionalInterface
  interface DocumentMover {
    void move(SecureDirectoryStream<Path> directory, Path temporary, Path target)
        throws IOException;
  }
}
