package org.fruitandfaults.journey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.fruitandfaults.course.application.CourseAssets;
import org.fruitandfaults.course.application.CourseCatalog;
import org.fruitandfaults.course.domain.AssetId;
import org.fruitandfaults.course.domain.AssetPolicy;
import org.fruitandfaults.course.domain.CompletionCriterion;
import org.fruitandfaults.course.domain.Course;
import org.fruitandfaults.course.domain.Lesson;
import org.fruitandfaults.course.domain.LessonAsset;
import org.fruitandfaults.course.domain.LessonId;
import org.fruitandfaults.course.domain.ReflectionOption;
import org.fruitandfaults.course.domain.ReflectionQuestion;
import org.fruitandfaults.course.infra.ClasspathCourseCatalog;

/** Trusted test release retaining the full installed v1 prefix and appending one pilot asset. */
final class JourneyContinuation implements CourseCatalog, CourseAssets {
  private final ClasspathCourseCatalog previous = new ClasspathCourseCatalog("course");
  private final byte[] content;
  private final Course updated;

  JourneyContinuation() throws IOException, NoSuchAlgorithmException {
    try (var input =
        java.util.Objects.requireNonNull(
            getClass()
                .getClassLoader()
                .getResourceAsStream("journeys/phase-a/continuation/lesson-5.txt"))) {
      content = input.readAllBytes();
    }
    var old = previous.load();
    var lesson =
        new Lesson(
            new LessonId("phase-b-pilot"),
            "Phase B pilot continuation",
            "Continue the existing learner project.",
            List.of(old.lessons().getLast().id()),
            List.of(
                new LessonAsset(
                    new AssetId("phase-b-pilot-note"),
                    "docs/lesson-5.txt",
                    "journeys/phase-a/continuation/lesson-5.txt",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)),
                    AssetPolicy.LEARNER_SCAFFOLD)),
            List.of("docs/lesson-5.txt"),
            List.of(
                new CompletionCriterion(
                    "starter-public-result", "Preserve the existing public starter result.")),
            new String(content, StandardCharsets.UTF_8),
            List.of(
                "Inspect saved progress.",
                "Keep the public contracts.",
                "Continue in the same project."),
            new ReflectionQuestion(
                "phase-b-pilot-reflection",
                "Where does the course continue?",
                List.of(
                    new ReflectionOption(
                        "same-workspace",
                        "The existing workspace.",
                        "Continue with saved learner work."),
                    new ReflectionOption(
                        "new-workspace",
                        "A fresh workspace.",
                        "The previous project remains the source of truth.")),
                "same-workspace"),
            "feat(course): continue the pilot");
    var lessons = new ArrayList<>(old.lessons());
    lessons.add(lesson);
    updated =
        new Course(old.id(), 2, old.title(), lessons.stream().map(Lesson::id).toList(), lessons);
  }

  @Override
  public Course load() {
    return updated;
  }

  @Override
  public List<Course> supportedCourses() {
    return List.of(previous.load(), updated);
  }

  @Override
  public byte[] load(LessonAsset asset) {
    return asset.id().value().equals("phase-b-pilot-note") ? content.clone() : previous.load(asset);
  }
}
