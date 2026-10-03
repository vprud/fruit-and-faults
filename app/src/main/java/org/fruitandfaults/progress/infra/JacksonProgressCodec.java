package org.fruitandfaults.progress.infra;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.fruitandfaults.course.application.CourseCatalog;
import org.fruitandfaults.course.domain.Course;
import org.fruitandfaults.course.domain.CourseCompatibility;
import org.fruitandfaults.course.domain.LessonId;
import org.fruitandfaults.progress.application.ProgressReadException;
import org.fruitandfaults.progress.application.ProgressReadException.Reason;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.fruitandfaults.progress.domain.LessonProgress;
import org.fruitandfaults.progress.domain.ProgressFormatVersion;
import org.jspecify.annotations.Nullable;

/** Maps strictly typed version-one JSON documents to fully validated domain snapshots. */
public final class JacksonProgressCodec {
  static final int MAX_DOCUMENT_BYTES = 1_048_576;
  private static final Set<String> FIELDS =
      Set.of(
          "formatVersion",
          "courseId",
          "courseContentVersion",
          "activeLessonId",
          "activeLessonOpenedAtRevision",
          "completedLessonIds",
          "revealedHintLevels",
          "reflectionAnswers");
  private final Course course;
  private final Map<Integer, Course> supported;
  private final ObjectMapper mapper =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  /**
   * Associates the boundary with the installed validated course.
   *
   * @param course validated installed content
   */
  public JacksonProgressCodec(Course course) {
    this(course, List.of());
  }

  /**
   * Resolves supported contracts from the installed trusted catalog.
   *
   * @param catalog installed and supported historical content
   */
  public JacksonProgressCodec(CourseCatalog catalog) {
    this(catalog.load(), catalog.supportedCourses());
  }

  /**
   * Supports exact prior definitions while preserving the version-one persisted schema.
   *
   * @param course installed content
   * @param previous trusted supported historical definitions
   */
  public JacksonProgressCodec(Course course, List<Course> previous) {
    this.course = Objects.requireNonNull(course);
    Map<Integer, Course> versions = new LinkedHashMap<>();
    versions.put(course.contentVersion(), course);
    for (Course historical : previous) {
      CourseCompatibility.requirePrefix(course, historical);
      Course duplicate = versions.putIfAbsent(historical.contentVersion(), historical);
      if (duplicate != null && !duplicate.equals(historical)) {
        throw new IllegalArgumentException("Ambiguous supported course content version.");
      }
    }
    supported = Map.copyOf(versions);
  }

  /**
   * Reads exact field types and validates all facts before constructing domain state.
   *
   * @param bytes bounded UTF-8 JSON document
   * @return validated course progress
   * @throws ProgressReadException if the document is malformed or incompatible
   * @throws IOException if JSON processing fails
   */
  public CourseProgress decode(byte[] bytes) throws IOException {
    if (bytes.length > MAX_DOCUMENT_BYTES) {
      throw failure(Reason.MALFORMED, "Document exceeds the supported size.");
    }
    JsonNode root;
    try {
      root = mapper.readTree(bytes);
    } catch (JsonProcessingException invalid) {
      throw failure(
          Reason.MALFORMED, "Expected one valid JSON object; repair or restore the document.");
    }
    if (root == null || !root.isObject()) {
      throw failure(Reason.MALFORMED, "Expected a JSON object.");
    }
    int format = integer(required(root, "formatVersion"));
    if (format != 1) {
      throw failure(
          Reason.UNSUPPORTED_FORMAT,
          "Expected format version 1; observed "
              + format
              + ". Use a compatible CLI; the file is preserved.");
    }
    Set<String> observedFields = new HashSet<>();
    root.fieldNames().forEachRemaining(observedFields::add);
    if (!observedFields.equals(FIELDS)) {
      throw failure(Reason.MALFORMED, "Expected exactly the version-one fields.");
    }
    String id = text(required(root, "courseId"));
    int contentVersion = integer(required(root, "courseContentVersion"));
    Course documentCourse = supported.get(contentVersion);
    if (!id.equals(course.id().value()) || documentCourse == null) {
      throw failure(
          Reason.INCOMPATIBLE_COURSE,
          "Expected course "
              + course.id().value()
              + " content version "
              + course.contentVersion()
              + "; observed "
              + id
              + " version "
              + contentVersion
              + ". Install matching content.");
    }
    JsonNode completed = required(root, "completedLessonIds");
    if (!completed.isArray()) {
      throw failure(Reason.MALFORMED, "Expected completedLessonIds array.");
    }
    List<String> completedIds = new ArrayList<>();
    for (JsonNode item : completed) {
      completedIds.add(text(item));
    }
    Map<String, Integer> hints = new LinkedHashMap<>();
    JsonNode hintsNode = object(required(root, "revealedHintLevels"));
    var hintFields = hintsNode.properties().iterator();
    while (hintFields.hasNext()) {
      var entry = hintFields.next();
      hints.put(entry.getKey(), integer(entry.getValue()));
    }
    Map<String, String> answers = new LinkedHashMap<>();
    var answerFields = object(required(root, "reflectionAnswers")).properties().iterator();
    while (answerFields.hasNext()) {
      var entry = answerFields.next();
      answers.put(entry.getKey(), text(entry.getValue()));
    }
    ProgressDocument document =
        new ProgressDocument(
            format,
            id,
            contentVersion,
            nullableText(required(root, "activeLessonId")),
            nullableText(required(root, "activeLessonOpenedAtRevision")),
            completedIds,
            hints,
            answers);
    return toDomain(document, documentCourse);
  }

  /**
   * Serializes a valid matching snapshot without filesystem or machine-local metadata.
   *
   * @param progress validated progress for the installed course
   * @return version-one JSON bytes
   * @throws IOException if progress is incompatible or serialization fails
   */
  public byte[] encode(CourseProgress progress) throws IOException {
    Course saved = progress.course();
    if (!saved.equals(supported.get(saved.contentVersion()))) {
      throw failure(Reason.INCOMPATIBLE_COURSE, "Expected progress for the installed course.");
    }
    List<String> completed = new ArrayList<>();
    Map<String, Integer> hints = new LinkedHashMap<>();
    Map<String, String> answers = new LinkedHashMap<>();
    for (LessonProgress lesson : progress.lessons()) {
      String id = lesson.lessonId().value();
      if (lesson.hintLevel() > 0) {
        hints.put(id, lesson.hintLevel());
      }
      lesson
          .completedOptionId()
          .ifPresent(
              answer -> {
                completed.add(id);
                answers.put(id, answer);
              });
    }
    byte[] bytes =
        mapper
            .writerWithDefaultPrettyPrinter()
            .writeValueAsBytes(
                new ProgressDocument(
                    1,
                    saved.id().value(),
                    saved.contentVersion(),
                    progress.activeLessonId().map(LessonId::value).orElse(null),
                    progress.activeLessonOpenedAtRevision().orElse(null),
                    completed,
                    hints,
                    answers));
    if (bytes.length > MAX_DOCUMENT_BYTES) {
      throw failure(Reason.INVALID_STATE, "Progress exceeds the supported document size.");
    }
    return bytes;
  }

  private CourseProgress toDomain(ProgressDocument document, Course documentCourse)
      throws ProgressReadException {
    Set<String> ids =
        new HashSet<>(documentCourse.lessonOrder().stream().map(LessonId::value).toList());
    Set<String> completed = new HashSet<>(document.completedLessonIds());
    if (completed.size() != document.completedLessonIds().size()
        || !ids.containsAll(completed)
        || !ids.containsAll(document.revealedHintLevels().keySet())
        || !document
            .completedLessonIds()
            .equals(
                documentCourse.lessonOrder().stream()
                    .limit(completed.size())
                    .map(LessonId::value)
                    .toList())
        || !completed.equals(document.reflectionAnswers().keySet())) {
      throw failure(
          Reason.INVALID_STATE,
          "Expected unique course lesson IDs and one answer per completed lesson.");
    }
    try {
      List<LessonProgress> states =
          documentCourse.lessonOrder().stream()
              .map(
                  id ->
                      new LessonProgress(
                          id,
                          document.revealedHintLevels().getOrDefault(id.value(), 0),
                          Optional.ofNullable(document.reflectionAnswers().get(id.value()))))
              .toList();
      return new CourseProgress(
          documentCourse,
          new ProgressFormatVersion(document.formatVersion()),
          states,
          Optional.ofNullable(document.activeLessonId()).map(LessonId::new),
          Optional.ofNullable(document.activeLessonOpenedAtRevision()));
    } catch (IllegalArgumentException invalid) {
      throw failure(
          Reason.INVALID_STATE,
          "Invalid lesson facts: "
              + invalid.getMessage()
              + " Restore valid progress before continuing.");
    }
  }

  private static JsonNode required(JsonNode root, String field) throws ProgressReadException {
    JsonNode value = root.get(field);
    if (value == null) {
      throw failure(Reason.MALFORMED, "Missing required field " + field + ".");
    }
    return value;
  }

  private static JsonNode object(JsonNode node) throws ProgressReadException {
    if (!node.isObject()) {
      throw failure(Reason.MALFORMED, "Expected an object of lesson facts.");
    }
    return node;
  }

  private static int integer(JsonNode node) throws ProgressReadException {
    if (!node.isIntegralNumber() || !node.canConvertToInt()) {
      throw failure(Reason.MALFORMED, "Expected an integer, without coercion.");
    }
    return node.intValue();
  }

  private static String text(JsonNode node) throws ProgressReadException {
    if (!node.isTextual()) {
      throw failure(Reason.MALFORMED, "Expected a string, without coercion.");
    }
    return Objects.requireNonNull(node.textValue());
  }

  private static @Nullable String nullableText(JsonNode node) throws ProgressReadException {
    return node.isNull() ? null : text(node);
  }

  private static ProgressReadException failure(Reason reason, String message) {
    return new ProgressReadException(reason, "Invalid progress: " + message);
  }
}
