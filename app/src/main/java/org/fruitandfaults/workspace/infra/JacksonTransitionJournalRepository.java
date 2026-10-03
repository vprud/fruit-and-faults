package org.fruitandfaults.workspace.infra;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.fruitandfaults.course.domain.Course;
import org.fruitandfaults.course.domain.LessonId;
import org.fruitandfaults.progress.application.ProgressReadException;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.fruitandfaults.progress.infra.JacksonProgressCodec;
import org.fruitandfaults.workspace.application.ManagedFilesReadException;
import org.fruitandfaults.workspace.application.TransitionJournalReadException;
import org.fruitandfaults.workspace.application.TransitionJournalRepository;
import org.fruitandfaults.workspace.domain.ManagedFiles;
import org.fruitandfaults.workspace.domain.TransitionJournal;
import org.fruitandfaults.workspace.domain.WorkspacePath;
import org.jspecify.annotations.Nullable;

/**
 * Strict bounded version-one JSON in .fruit-and-faults/transition.json. The complete journal is
 * written once with the anchored exclusive asset writer, retaining partial or ambiguous failures.
 * Reads and exact-plan deletion use verified secure directory handles and stable entry identities.
 * Unsupported providers fail safely; malformed and future journals are never replaced or removed.
 */
public final class JacksonTransitionJournalRepository implements TransitionJournalRepository {
  private static final int MAX_DOCUMENT_BYTES = 1_048_576;
  private static final WorkspacePath JOURNAL =
      WorkspacePath.parse(".fruit-and-faults/transition.json");
  private static final Set<String> FIELDS =
      Set.of(
          "formatVersion",
          "fromLessonId",
          "toLessonId",
          "assets",
          "expectedManifestVersion",
          "expectedManaged",
          "expectedProgress",
          "intendedProgress");
  private final JacksonProgressCodec progressCodec;
  private final JacksonManagedFilesRepository managedCodec = new JacksonManagedFilesRepository();
  private final SafeWorkspaceFiles files;
  private final ObjectMapper mapper =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  /**
   * Uses the installed course and anchored exclusive persistence.
   *
   * @param course validated installed course
   */
  public JacksonTransitionJournalRepository(Course course) {
    this(course, new SafeWorkspaceFiles());
  }

  /**
   * Uses the same explicitly supported historical contracts as workspace progress persistence.
   *
   * @param codec installed and trusted historical progress codec
   */
  public JacksonTransitionJournalRepository(JacksonProgressCodec codec) {
    progressCodec = Objects.requireNonNull(codec);
    files = new SafeWorkspaceFiles();
  }

  JacksonTransitionJournalRepository(Course course, SafeWorkspaceFiles files) {
    progressCodec = new JacksonProgressCodec(course);
    this.files = Objects.requireNonNull(files);
  }

  @Override
  public Optional<TransitionJournal> load(Path root) throws IOException {
    return read(root).map(StoredJournal::journal);
  }

  @Override
  public void create(Path root, TransitionJournal journal) throws IOException {
    if (read(root).isPresent()) {
      throw new IOException(
          "A pending disclosure journal already exists; recover that transition first.");
    }
    files.writeNewSafely(root, JOURNAL, encode(journal));
  }

  @Override
  public void remove(Path root, TransitionJournal journal) throws IOException {
    Optional<StoredJournal> stored = read(root);
    if (stored.isEmpty() || !stored.orElseThrow().journal().equals(journal)) {
      throw new IOException(
          "Expected the exact committed disclosure journal; preserve pending state and inspect the workspace.");
    }
    Path realRoot = SafeWorkspaceFiles.verifiedRoot(root);
    Path file = SafeWorkspaceFiles.verifiedTarget(realRoot, JOURNAL);
    var directories = SafeWorkspaceFiles.directoryIdentities(realRoot, file.getParent());
    try (SecureDirectoryStream<Path> directory =
        SafeWorkspaceFiles.openVerifiedDirectory(
            realRoot,
            Objects.requireNonNull(file.getParent()),
            directories,
            BasicFileAttributes::fileKey)) {
      Path name = Objects.requireNonNull(file.getFileName());
      StoredJournal expected = stored.orElseThrow();
      SafeWorkspaceFiles.verifyDirectories(directories);
      SafeWorkspaceFiles.requireEntryIdentity(directory, name, expected.key());
      SafeWorkspaceFiles.requireEntryContents(directory, name, expected.bytes());
      SafeWorkspaceFiles.requireEntryIdentity(directory, name, expected.key());
      SafeWorkspaceFiles.verifyDirectories(directories);
      directory.deleteFile(name);
      SafeWorkspaceFiles.verifyDirectories(directories);
    }
  }

  private Optional<StoredJournal> read(Path root) throws IOException {
    Path realRoot = SafeWorkspaceFiles.verifiedRoot(root);
    Path file = SafeWorkspaceFiles.verifiedTarget(realRoot, JOURNAL);
    if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    var directories = SafeWorkspaceFiles.directoryIdentities(realRoot, file.getParent());
    try (SecureDirectoryStream<Path> directory =
        SafeWorkspaceFiles.openVerifiedDirectory(
            realRoot,
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
        bytes = input.readNBytes(MAX_DOCUMENT_BYTES + 1);
      }
      SafeWorkspaceFiles.verifyDirectories(directories);
      SafeWorkspaceFiles.requireEntryIdentity(directory, name, key);
      if (bytes.length > MAX_DOCUMENT_BYTES) {
        throw failure(
            TransitionJournalReadException.Reason.MALFORMED,
            "Document exceeds the supported 1 MiB size.");
      }
      SafeWorkspaceFiles.requireEntryContents(directory, name, bytes);
      SafeWorkspaceFiles.requireEntryIdentity(directory, name, key);
      SafeWorkspaceFiles.verifyDirectories(directories);
      return Optional.of(new StoredJournal(decode(bytes), bytes, key));
    }
  }

  private byte[] encode(TransitionJournal journal) throws IOException {
    JsonNode ownership =
        mapper.readTree(managedCodec.encode(new ManagedFiles(journal.assets()))).get("files");
    JsonNode expectedManaged =
        journal.expectedManaged().isPresent()
            ? mapper.readTree(managedCodec.encode(journal.expectedManaged().orElseThrow()))
            : mapper.nullNode();
    JsonNode expectedProgress =
        journal.expectedProgress().isPresent()
            ? mapper.readTree(progressCodec.encode(journal.expectedProgress().orElseThrow()))
            : mapper.nullNode();
    JsonNode intendedProgress = mapper.readTree(progressCodec.encode(journal.intendedProgress()));
    byte[] bytes =
        mapper
            .writerWithDefaultPrettyPrinter()
            .writeValueAsBytes(
                new JournalDocument(
                    1,
                    journal.fromLessonId().map(LessonId::value).orElse(null),
                    journal.toLessonId().value(),
                    ownership,
                    journal.expectedManifestVersion(),
                    expectedManaged,
                    expectedProgress,
                    intendedProgress));
    if (bytes.length > MAX_DOCUMENT_BYTES) {
      throw failure(
          TransitionJournalReadException.Reason.INVALID_STATE,
          "Journal exceeds the supported 1 MiB size.");
    }
    return bytes;
  }

  private TransitionJournal decode(byte[] bytes) throws IOException {
    if (bytes.length > MAX_DOCUMENT_BYTES) {
      throw failure(
          TransitionJournalReadException.Reason.MALFORMED,
          "Document exceeds the supported 1 MiB size.");
    }
    JsonNode root;
    try {
      root = mapper.readTree(bytes);
    } catch (JsonProcessingException invalid) {
      throw failure(
          TransitionJournalReadException.Reason.MALFORMED,
          "Expected one valid JSON object; repair or restore the pending journal.");
    }
    if (root == null || !root.isObject()) {
      throw failure(TransitionJournalReadException.Reason.MALFORMED, "Expected a JSON object.");
    }
    int format = integer(required(root, "formatVersion"));
    if (format != 1) {
      throw failure(
          TransitionJournalReadException.Reason.UNSUPPORTED_FORMAT,
          "Expected format version 1; observed "
              + format
              + ". Use a compatible CLI; the journal is preserved.");
    }
    Set<String> observed = new HashSet<>();
    root.fieldNames().forEachRemaining(observed::add);
    if (!observed.equals(FIELDS)) {
      throw failure(
          TransitionJournalReadException.Reason.MALFORMED,
          "Expected exactly the version-one journal fields.");
    }
    JsonNode expectedManaged = required(root, "expectedManaged");
    JsonNode expectedProgress = required(root, "expectedProgress");
    JsonNode from = required(root, "fromLessonId");
    try {
      var assetManifest = mapper.createObjectNode().put("formatVersion", 1);
      assetManifest.set("files", required(root, "assets"));
      ManagedFiles assets = managedCodec.decode(mapper.writeValueAsBytes(assetManifest));
      Optional<ManagedFiles> priorManaged =
          expectedManaged.isNull()
              ? Optional.empty()
              : Optional.of(managedCodec.decode(mapper.writeValueAsBytes(expectedManaged)));
      Optional<CourseProgress> priorProgress =
          expectedProgress.isNull()
              ? Optional.empty()
              : Optional.of(progressCodec.decode(mapper.writeValueAsBytes(expectedProgress)));
      CourseProgress intended =
          progressCodec.decode(mapper.writeValueAsBytes(required(root, "intendedProgress")));
      return new TransitionJournal(
          format,
          from.isNull() ? Optional.empty() : Optional.of(new LessonId(text(from))),
          new LessonId(text(required(root, "toLessonId"))),
          assets.files(),
          integer(required(root, "expectedManifestVersion")),
          priorManaged,
          priorProgress,
          intended);
    } catch (IllegalArgumentException | ManagedFilesReadException | ProgressReadException invalid) {
      throw failure(
          TransitionJournalReadException.Reason.INVALID_STATE,
          "Expected exact installed assets and one valid versioned course transition; restore the matching course or repair the journal. The journal is preserved.");
    }
  }

  private static JsonNode required(JsonNode node, String field)
      throws TransitionJournalReadException {
    JsonNode value = node.get(field);
    if (value == null) {
      throw failure(
          TransitionJournalReadException.Reason.MALFORMED, "Missing required field " + field + ".");
    }
    return value;
  }

  private static int integer(JsonNode value) throws TransitionJournalReadException {
    if (!value.isIntegralNumber() || !value.canConvertToInt()) {
      throw failure(
          TransitionJournalReadException.Reason.MALFORMED, "Expected an integer without coercion.");
    }
    return value.intValue();
  }

  private static String text(JsonNode value) throws TransitionJournalReadException {
    if (!value.isTextual()) {
      throw failure(
          TransitionJournalReadException.Reason.MALFORMED,
          "Expected string identities without coercion.");
    }
    return Objects.requireNonNull(value.textValue());
  }

  private static TransitionJournalReadException failure(
      TransitionJournalReadException.Reason reason, String message) {
    return new TransitionJournalReadException(reason, "Invalid disclosure journal: " + message);
  }

  private static final class StoredJournal {
    private final TransitionJournal journal;
    private final byte[] bytes;
    private final Object key;

    private StoredJournal(TransitionJournal journal, byte[] bytes, Object key) {
      this.journal = journal;
      this.bytes = bytes;
      this.key = key;
    }

    private TransitionJournal journal() {
      return journal;
    }

    private byte[] bytes() {
      return bytes;
    }

    private Object key() {
      return key;
    }
  }

  private record JournalDocument(
      int formatVersion,
      @Nullable String fromLessonId,
      String toLessonId,
      JsonNode assets,
      int expectedManifestVersion,
      JsonNode expectedManaged,
      JsonNode expectedProgress,
      JsonNode intendedProgress) {}
}
