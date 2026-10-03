package org.fruitandfaults.course.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CourseTest {
  @Test
  void declaredOrderDeterminesLessonSequenceAndCollectionsAreImmutable() {
    List<Lesson> lessons = new ArrayList<>(List.of(lesson("second", "first"), lesson("first")));
    Course course = course(List.of("first", "second"), lessons);
    lessons.clear();
    assertEquals(
        List.of(new LessonId("first"), new LessonId("second")),
        course.lessons().stream().map(Lesson::id).toList());
    assertThrows(UnsupportedOperationException.class, () -> course.lessons().clear());
    assertThrows(UnsupportedOperationException.class, () -> course.lessonOrder().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> course.lessons().getFirst().hints().clear());
    assertThrows(UnsupportedOperationException.class, () -> question().options().clear());
  }

  @Test
  void duplicateLessonIdsAreRejected() {
    rejects(
        "Duplicate lesson",
        () -> course(List.of("first"), List.of(lesson("first"), lesson("first"))));
  }

  @Test
  void missingPrerequisiteIsRejected() {
    rejects(
        "Missing prerequisite",
        () -> course(List.of("first"), List.of(lesson("first", "missing"))));
  }

  @Test
  void cyclicPrerequisitesAreRejected() {
    rejects(
        "earlier",
        () ->
            course(
                List.of("first", "second"),
                List.of(lesson("first", "second"), lesson("second", "first"))));
  }

  @Test
  void outOfOrderPrerequisiteIsRejected() {
    rejects(
        "earlier",
        () ->
            course(
                List.of("first", "second"), List.of(lesson("first", "second"), lesson("second"))));
  }

  @Test
  void missingLessonInDeclaredOrderIsRejected() {
    rejects("Missing lesson", () -> course(List.of("first", "missing"), List.of(lesson("first"))));
  }

  @Test
  void undeclaredLessonAndDuplicateOrderEntriesAreRejected() {
    rejects("declared", () -> course(List.of("first"), List.of(lesson("first"), lesson("second"))));
    rejects("Duplicate", () -> course(List.of("first", "first"), List.of(lesson("first"))));
  }

  @Test
  void duplicateOptionIdsAreRejected() {
    rejects(
        "Duplicate option",
        () ->
            new ReflectionQuestion(
                "question", "Why?", List.of(option("yes"), option("yes")), "yes"));
  }

  @Test
  void absentCorrectOptionIsRejected() {
    rejects(
        "Correct option",
        () ->
            new ReflectionQuestion(
                "question", "Why?", List.of(option("yes"), option("no")), "missing"));
  }

  @Test
  void lessonRequiresExactlyThreeNonBlankHints() {
    for (List<String> hints :
        List.of(
            List.<String>of(),
            List.of("One", "Two"),
            List.of("One", "Two", "Three", "Four"),
            List.of("One", " ", "Three"))) {
      assertThrows(IllegalArgumentException.class, () -> lessonWithHints(hints));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "../outside", "/absolute", "a/../b", "a/./b", "a//b", "a\\b", "C:/file", "a/"})
  void unsafeOrUnnormalizedAssetPathsAreRejected(String path) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LessonAsset(
                new AssetId("starter"),
                path,
                "course/assets/source.java",
                "a".repeat(64),
                AssetPolicy.LEARNER_SCAFFOLD));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LessonAsset(
                new AssetId("starter"),
                "src/Source.java",
                path,
                "a".repeat(64),
                AssetPolicy.LEARNER_SCAFFOLD));
  }

  @Test
  void assetRequiresSha256AndPreservesItsOwnershipPolicy() {
    rejects(
        "SHA-256",
        () ->
            new LessonAsset(
                new AssetId("starter"),
                "src/Source.java",
                "course/assets/source.java",
                "invalid",
                AssetPolicy.LEARNER_SCAFFOLD));
    for (AssetPolicy policy : AssetPolicy.values()) {
      LessonAsset asset =
          new LessonAsset(
              new AssetId("starter"),
              "src/Source.java",
              "course/assets/source.java",
              "a".repeat(64),
              policy);
      assertEquals(policy, asset.policy());
      assertEquals("src/Source.java", asset.relativePath());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "BadId", "../escape", "two words", "-start", "end-"})
  void stableIdentifiersRejectInvalidValues(String value) {
    assertThrows(IllegalArgumentException.class, () -> new CourseId(value));
    assertThrows(IllegalArgumentException.class, () -> new LessonId(value));
    assertThrows(IllegalArgumentException.class, () -> new AssetId(value));
  }

  @Test
  void malformedMetadataIsRejectedBeforeCourseUse() {
    rejects(
        "version",
        () ->
            new Course(
                new CourseId("course"),
                0,
                "Title",
                List.of(new LessonId("first")),
                List.of(lesson("first"))));
    rejects(
        "title",
        () ->
            new Course(
                new CourseId("course"),
                1,
                " ",
                List.of(new LessonId("first")),
                List.of(lesson("first"))));
    rejects("lesson", () -> course(List.of(), List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new ReflectionOption("option", " ", "Feedback"));
    assertThrows(IllegalArgumentException.class, () -> new CompletionCriterion("criterion", " "));
  }

  private static Course course(List<String> order, List<Lesson> lessons) {
    return new Course(
        new CourseId("course"), 1, "Title", order.stream().map(LessonId::new).toList(), lessons);
  }

  private static Lesson lesson(String id, String... prerequisites) {
    return new Lesson(
        new LessonId(id),
        "Title",
        "Goal",
        List.of(prerequisites).stream().map(LessonId::new).toList(),
        List.of(),
        List.of("src/Source.java"),
        List.of(new CompletionCriterion("public-result", "Expected public behavior")),
        "Instructions",
        List.of("One", "Two", "Three"),
        question(),
        "feat: learn movement");
  }

  private static Lesson lessonWithHints(List<String> hints) {
    return new Lesson(
        new LessonId("first"),
        "Title",
        "Goal",
        List.of(),
        List.of(),
        List.of(),
        List.of(new CompletionCriterion("public-result", "Expected public behavior")),
        "Instructions",
        hints,
        question(),
        "feat: learn movement");
  }

  private static ReflectionQuestion question() {
    return new ReflectionQuestion("question", "Why?", List.of(option("yes"), option("no")), "yes");
  }

  private static ReflectionOption option(String id) {
    return new ReflectionOption(id, "Answer " + id, "Feedback " + id);
  }

  private static void rejects(String messageFragment, Runnable action) {
    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action::run);
    assertTrue(
        Objects.requireNonNull(failure.getMessage()).contains(messageFragment),
        failure.getMessage());
  }
}
