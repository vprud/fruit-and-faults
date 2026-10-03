package org.fruitandfaults.course.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

import org.fruitandfaults.course.domain.AssetPolicy;
import org.fruitandfaults.course.domain.Course;
import org.fruitandfaults.course.domain.Lesson;
import org.fruitandfaults.course.domain.LessonId;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ClasspathCourseCatalogTest {
  @Test
  void installedBundleExposesFourStableLessonsAndContentVersionOne() {
    Course course = new ClasspathCourseCatalog("course").load();
    assertEquals("fruit-and-faults", course.id().value());
    assertEquals(1, course.contentVersion());
    assertEquals(
        List.of("first-run", "coordinate-direction", "field-valid-move", "game-state"),
        course.lessons().stream().map(lesson -> lesson.id().value()).toList());
    for (int index = 0; index < course.lessons().size(); index++) {
      Lesson lesson = course.lessons().get(index);
      assertEquals(
          index == 0 ? List.of() : List.of(course.lessons().get(index - 1).id()),
          lesson.prerequisites());
      assertEquals(3, lesson.hints().size());
      assertFalse(lesson.instructions().isBlank());
      assertFalse(lesson.goal().isBlank());
      assertFalse(lesson.completionCriteria().isEmpty());
      assertFalse(lesson.assets().isEmpty(), "Every installed lesson discloses its own assets");
      assertTrue(
          lesson.question().options().stream()
              .anyMatch(option -> option.id().equals(lesson.question().correctOptionId())));
    }
    assertEquals("compile-before-tests", course.lessons().getFirst().question().correctOptionId());
  }

  @Test
  void laterBundlesMustExplicitlyShipTrustedHistoricalContracts() {
    ClassLoader loader =
        new ClassLoader(getClass().getClassLoader()) {
          @Override
          public @Nullable InputStream getResourceAsStream(String name) {
            if (name.equals("course-new/compatibility.properties"))
              return new ByteArrayInputStream(
                  "versions=1\nversion.1.root=course-valid\n".getBytes(StandardCharsets.UTF_8));
            if (name.startsWith("course-new/")) {
              try (var input =
                  super.getResourceAsStream(name.replace("course-new/", "course-valid/"))) {
                if (input == null) return null;
                byte[] bytes = input.readAllBytes();
                if (name.endsWith("course.properties"))
                  bytes =
                      new String(bytes, StandardCharsets.UTF_8)
                          .replace("contentVersion=1", "contentVersion=2")
                          .getBytes(StandardCharsets.UTF_8);
                return new ByteArrayInputStream(bytes);
              } catch (IOException failed) {
                throw new UncheckedIOException(failed);
              }
            }
            return super.getResourceAsStream(name);
          }
        };
    var catalog = new ClasspathCourseCatalog("course-new", loader);
    assertEquals(2, catalog.load().contentVersion());
    assertEquals(
        List.of(2, 1), catalog.supportedCourses().stream().map(Course::contentVersion).toList());
    assertEquals(1, catalog.load(1).contentVersion());
    assertTrue(catalog.load(catalog.load(1).lessons().getFirst().assets().getFirst()).length > 0);
    assertThrows(IllegalArgumentException.class, () -> catalog.load(3));
    assertEquals(1, new ClasspathCourseCatalog("course").supportedCourses().size());
  }

  @Test
  void compatibilityManifestCannotClaimUnknownOrConflictingHistoricalVersions() {
    for (String metadata :
        List.of(
            "versions=2\nversion.2.root=course-valid\n",
            "versions=1\nversion.1.root=missing-history\n",
            "versions=1\nversion.1.root=course\n",
            "versions=1,1\nversion.1.root=course-valid\n")) {
      var catalog =
          new ClasspathCourseCatalog(
              "course-valid",
              resourceLoader(
                  "course-valid/compatibility.properties",
                  metadata.getBytes(StandardCharsets.UTF_8)));
      assertThrows(IllegalArgumentException.class, catalog::supportedCourses);
    }
  }

  @Test
  void explicitOrderIgnoresDirectoryNamesAndAssetsHashRawUtf8Bytes() {
    Course course = new ClasspathCourseCatalog("course-valid").load();
    assertEquals(List.of(new LessonId("first"), new LessonId("second")), course.lessonOrder());
    Lesson first = course.lessons().getFirst();
    assertEquals("Réfléchir avant de changer.\n", first.instructions());
    assertEquals(List.of("Hint one.\n", "Hint two.\n", "Hint three.\n"), first.hints());
    assertEquals("Pourquoi?", first.question().prompt());
    assertEquals(
        "Because behavior is observable.", first.question().options().getFirst().feedback());
    assertEquals(
        List.of(
            AssetPolicy.IMMUTABLE_CHECK,
            AssetPolicy.EDITABLE_TEMPLATE,
            AssetPolicy.LEARNER_SCAFFOLD),
        first.assets().stream().map(asset -> asset.policy()).toList());
    assertEquals("course-valid/z-last/assets/raw.txt", first.assets().getFirst().resourcePath());
    assertEquals("src/Raw.txt", first.assets().getFirst().relativePath());
    assertEquals(
        "b95becd154aa095f76c4ca47a5aeb8350d6dfcb838404edfc9dae06628de938d",
        first.assets().getFirst().sha256());
  }

  @ParameterizedTest
  @CsvSource({
    "missing-resource, absent/lesson.properties",
    "missing-key, title",
    "invalid-version, contentVersion",
    "unsupported-version, absent/lesson.properties",
    "unknown-key, typo",
    "invalid-id, CourseId"
  })
  void invalidFixtureBundlesExplainWhereToRepairContent(String fixture, String detail) {
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ClasspathCourseCatalog("course-invalid/" + fixture).load());
    String message = Objects.requireNonNull(failure.getMessage());
    assertTrue(message.contains(detail), message);
    assertTrue(message.contains("course-invalid/" + fixture), message);
    assertTrue(message.contains("Restore"), message);
  }

  @Test
  void missingCourseResourceNamesExpectedResource() {
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ClasspathCourseCatalog("course-invalid/absent").load());
    assertTrue(
        Objects.requireNonNull(failure.getMessage())
            .contains("course-invalid/absent/course.properties"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "../outside", "/absolute", "a/./b", "a\\b", "a//b"})
  void unsafeClasspathRootsAreRejected(String root) {
    assertThrows(IllegalArgumentException.class, () -> new ClasspathCourseCatalog(root));
  }

  @ParameterizedTest
  @CsvSource({
    "id=first, id=second, identity",
    "IMMUTABLE_CHECK, UNKNOWN_POLICY, UNKNOWN_POLICY",
    "assets/raw.txt, assets/missing.txt, assets/missing.txt",
    "'hints=hints/01.md,hints/02.md,hints/03.md', hints=hints/01.md, three",
    "criterion.public-result.description=Observe the result, criterion.public-result.description=, description"
  })
  void malformedLessonMetadataFailsWithResourceContext(String before, String after, String detail) {
    failsOverlay("z-last/lesson.properties", text -> text.replace(before, after), detail);
  }

  @Test
  void unknownLessonAndQuestionPropertiesAreRejected() {
    failsOverlay("z-last/lesson.properties", text -> text + "typo=unexpected\n", "typo");
    failsOverlay("z-last/question.properties", text -> text + "typo=unexpected\n", "typo");
  }

  @Test
  void checksumMismatchIsRejectedRatherThanTrusted() {
    failsOverlay(
        "z-last/lesson.properties",
        text -> text + "asset.check.sha256=" + "0".repeat(64) + "\n",
        "SHA-256");
  }

  @Test
  void declaredChecksumIsVerifiedAgainstSourceBytes() {
    Course course =
        overlay(
                "z-last/lesson.properties",
                text ->
                    text
                        + "asset.check.sha256=b95becd154aa095f76c4ca47a5aeb8350d6dfcb838404edfc9dae06628de938d\n")
            .load();
    assertEquals(3, course.lessons().getFirst().assets().size());
  }

  @Test
  void reflectionAndHintInvariantsAreEnforcedAtTheLoadingBoundary() {
    failsOverlay(
        "z-last/question.properties",
        text -> text.replace("options=yes,no", "options=yes,yes"),
        "Duplicate");
    failsOverlay(
        "z-last/question.properties",
        text -> text.replace("correctOptionId=yes", "correctOptionId=absent"),
        "Correct option");
    failsOverlay("z-last/hints/01.md", text -> " \n", "hint");
  }

  @Test
  void invalidUtf8AndOversizedResourcesAreRejected() {
    for (byte[] bytes : List.of(new byte[] {(byte) 0xc3, 0x28}, new byte[1_048_577])) {
      ClassLoader loader = resourceLoader("course-valid/z-last/instructions.md", bytes);
      IllegalArgumentException failure =
          assertThrows(
              IllegalArgumentException.class,
              () -> new ClasspathCourseCatalog("course-valid", loader).load());
      assertTrue(Objects.requireNonNull(failure.getMessage()).contains("instructions.md"));
    }
  }

  private static void failsOverlay(String resource, UnaryOperator<String> edit, String detail) {
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> overlay(resource, edit).load());
    assertTrue(Objects.requireNonNull(failure.getMessage()).contains(detail), failure.getMessage());
    assertTrue(failure.getMessage().contains("course-valid/" + resource), failure.getMessage());
  }

  private static ClasspathCourseCatalog overlay(String resource, UnaryOperator<String> edit) {
    String path = "course-valid/" + resource;
    try (InputStream input =
        Objects.requireNonNull(
            ClasspathCourseCatalogTest.class.getClassLoader().getResourceAsStream(path))) {
      byte[] bytes =
          edit.apply(new String(input.readAllBytes(), StandardCharsets.UTF_8))
              .getBytes(StandardCharsets.UTF_8);
      return new ClasspathCourseCatalog("course-valid", resourceLoader(path, bytes));
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private static ClassLoader resourceLoader(String path, byte[] bytes) {
    return new ClassLoader(ClasspathCourseCatalogTest.class.getClassLoader()) {
      @Override
      public @Nullable InputStream getResourceAsStream(String name) {
        return name.equals(path)
            ? new ByteArrayInputStream(bytes)
            : super.getResourceAsStream(name);
      }
    };
  }
}
