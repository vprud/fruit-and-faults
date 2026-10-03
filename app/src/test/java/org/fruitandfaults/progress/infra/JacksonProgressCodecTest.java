package org.fruitandfaults.progress.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

import org.fruitandfaults.course.domain.Course;
import org.fruitandfaults.course.domain.LessonId;
import org.fruitandfaults.course.infra.ClasspathCourseCatalog;
import org.fruitandfaults.progress.application.ProgressReadException;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JacksonProgressCodecTest {
  private final Course course = new ClasspathCourseCatalog("course").load();
  private final JacksonProgressCodec codec = new JacksonProgressCodec(course);

  @Test
  void validFixturesRoundTripWithStableIdsAndNullableRevision() throws IOException {
    CourseProgress opening = codec.decode(fixture("opening"));
    assertEquals(CourseProgress.opening(course, null), opening);
    CourseProgress advanced = codec.decode(fixture("advanced"));
    assertEquals(Optional.of(new LessonId("coordinate-direction")), advanced.activeLessonId());
    assertEquals(Optional.of("abc123"), advanced.activeLessonOpenedAtRevision());
    assertEquals(2, advanced.lessons().getFirst().hintLevel());
    assertEquals(
        Optional.of("compile-before-tests"), advanced.lessons().getFirst().completedOptionId());
    assertEquals(advanced, codec.decode(codec.encode(advanced)));
    String json = new String(codec.encode(opening), StandardCharsets.UTF_8);
    assertTrue(json.contains("\"formatVersion\" : 1"));
    assertTrue(json.contains("\"activeLessonOpenedAtRevision\" : null"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "formatVersion",
        "courseId",
        "courseContentVersion",
        "activeLessonId",
        "activeLessonOpenedAtRevision",
        "completedLessonIds",
        "revealedHintLevels",
        "reflectionAnswers"
      })
  void everyDocumentFieldMustBePresent(String field) throws IOException {
    String json = new String(fixture("opening"), StandardCharsets.UTF_8);
    String missing =
        json.replaceAll("(?m)^  \"" + field + "\"[^\\n]*\\n", "").replace(",\n}", "\n}");
    rejects(missing, ProgressReadException.Reason.MALFORMED);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "{", "null", "[]", "{}", "{\"formatVersion\":1,\"formatVersion\":1}", "true"})
  void malformedDocumentsAreDistinctFailures(String json) {
    rejects(json, ProgressReadException.Reason.MALFORMED);
  }

  @Test
  void exactVersionIsRequiredBeforeInterpretingOtherFields() {
    rejects("{\"formatVersion\":2}", ProgressReadException.Reason.UNSUPPORTED_FORMAT);
    rejects("{\"formatVersion\":0}", ProgressReadException.Reason.UNSUPPORTED_FORMAT);
    rejects("{\"formatVersion\":1.0}", ProgressReadException.Reason.MALFORMED);
    rejects("{\"formatVersion\":\"1\"}", ProgressReadException.Reason.MALFORMED);
  }

  @Test
  void mismatchingCourseOrContentVersionIsIncompatible() throws IOException {
    String json = new String(fixture("opening"), StandardCharsets.UTF_8);
    rejects(
        json.replace("fruit-and-faults", "other-course"),
        ProgressReadException.Reason.INCOMPATIBLE_COURSE);
    rejects(
        json.replace("\"courseContentVersion\": 1", "\"courseContentVersion\": 2"),
        ProgressReadException.Reason.INCOMPATIBLE_COURSE);
  }

  @Test
  void invalidFactsAreRejectedBeforeConstructingProgress() throws IOException {
    String opening = new String(fixture("opening"), StandardCharsets.UTF_8);
    String advanced = new String(fixture("advanced"), StandardCharsets.UTF_8);
    rejects(
        advanced.replace("[\"first-run\"]", "[\"first-run\",\"first-run\"]"),
        ProgressReadException.Reason.INVALID_STATE);
    rejects(advanced.replace("first-run", "absent"), ProgressReadException.Reason.INVALID_STATE);
    rejects(
        advanced.replace("\"first-run\": 2", "\"first-run\": 4"),
        ProgressReadException.Reason.INVALID_STATE);
    rejects(
        advanced.replace("\"first-run\": 2", "\"first-run\": -1"),
        ProgressReadException.Reason.INVALID_STATE);
    rejects(
        advanced.replace("compile-before-tests", "absent-option"),
        ProgressReadException.Reason.INVALID_STATE);
    rejects(
        advanced.replace("\"first-run\": \"compile-before-tests\"", ""),
        ProgressReadException.Reason.INVALID_STATE);
    rejects(
        opening.replace("\"first-run\"", "\"game-state\""),
        ProgressReadException.Reason.INVALID_STATE);
    rejects(
        opening.replace(
            "\"revealedHintLevels\": {}", "\"revealedHintLevels\": {\"game-state\": 1}"),
        ProgressReadException.Reason.INVALID_STATE);
    rejects(
        opening.replace(
            "\"activeLessonOpenedAtRevision\": null", "\"activeLessonOpenedAtRevision\": \" \""),
        ProgressReadException.Reason.INVALID_STATE);
  }

  @Test
  void unknownFieldsWrongTypesAndNullRequiredValuesAreMalformed() throws IOException {
    String json = new String(fixture("opening"), StandardCharsets.UTF_8);
    rejects(
        json.replace("\"formatVersion\": 1", "\"extra\": 3, \"formatVersion\": 1"),
        ProgressReadException.Reason.MALFORMED);
    rejects(
        json.replace("\"courseId\": \"fruit-and-faults\"", "\"courseId\": null"),
        ProgressReadException.Reason.MALFORMED);
    rejects(
        json.replace("\"completedLessonIds\": []", "\"completedLessonIds\": null"),
        ProgressReadException.Reason.MALFORMED);
    rejects(
        json.replace("\"revealedHintLevels\": {}", "\"revealedHintLevels\": {\"first-run\": null}"),
        ProgressReadException.Reason.MALFORMED);
    rejects(
        json.replace(
            "\"revealedHintLevels\": {}", "\"revealedHintLevels\": {\"first-run\": \"1\"}"),
        ProgressReadException.Reason.MALFORMED);
    rejects(json + " {}", ProgressReadException.Reason.MALFORMED);
  }

  @Test
  void completedIdsMustFollowTheDeclaredRouteOrder() throws IOException {
    String advanced = new String(fixture("advanced"), StandardCharsets.UTF_8);
    String twoCompleted =
        advanced
            .replace(
                "\"activeLessonId\": \"coordinate-direction\"",
                "\"activeLessonId\": \"field-valid-move\"")
            .replace("[\"first-run\"]", "[\"coordinate-direction\",\"first-run\"]")
            .replace(
                "\"first-run\": \"compile-before-tests\"",
                "\"first-run\": \"compile-before-tests\",\"coordinate-direction\": \""
                    + course.lessons().get(1).question().correctOptionId()
                    + "\"");
    rejects(twoCompleted, ProgressReadException.Reason.INVALID_STATE);
  }

  @Test
  void terminalProgressRoundTripsAndDocumentsAreBounded() throws IOException {
    CourseProgress progress = CourseProgress.opening(course, "revision");
    for (var lesson : course.lessons()) {
      progress =
          progress.advance(lesson.id(), lesson.question().correctOptionId(), "revision").progress();
    }
    assertEquals(progress, codec.decode(codec.encode(progress)));
    assertTrue(progress.activeLessonId().isEmpty());
    rejects(
        " ".repeat(JacksonProgressCodec.MAX_DOCUMENT_BYTES + 1),
        ProgressReadException.Reason.MALFORMED);
  }

  @Test
  void encodingCannotProduceAnUnreadableOversizedDocument() {
    CourseProgress oversized =
        CourseProgress.opening(course, "a".repeat(JacksonProgressCodec.MAX_DOCUMENT_BYTES));
    ProgressReadException failure =
        assertThrows(ProgressReadException.class, () -> codec.encode(oversized));
    assertEquals(ProgressReadException.Reason.INVALID_STATE, failure.reason());
  }

  private void rejects(String json, ProgressReadException.Reason reason) {
    ProgressReadException failure =
        assertThrows(
            ProgressReadException.class, () -> codec.decode(json.getBytes(StandardCharsets.UTF_8)));
    assertEquals(reason, failure.reason());
    assertTrue(Objects.requireNonNull(failure.getMessage()).contains("progress"));
  }

  private static byte[] fixture(String name) throws IOException {
    try (var stream =
        Objects.requireNonNull(
            JacksonProgressCodecTest.class.getResourceAsStream("/progress/" + name + ".json"))) {
      return stream.readAllBytes();
    }
  }
}
