package org.fruitandfaults.course.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A validated course with a complete explicitly ordered route.
 *
 * @param id stable course identity
 * @param contentVersion positive content schema version
 * @param title display title
 * @param lessonOrder declared lesson identities in route order
 * @param lessons lesson definitions, returned in the declared order
 */
public record Course(
    CourseId id,
    int contentVersion,
    String title,
    List<LessonId> lessonOrder,
    List<Lesson> lessons) {
  /** Validates the complete route and prerequisites, then orders immutable lessons. */
  public Course {
    Objects.requireNonNull(id);
    if (contentVersion < 1) {
      throw new IllegalArgumentException(
          "Expected positive content version; observed " + contentVersion + ".");
    }
    title = ContentValidation.text(title, "course title");
    lessonOrder = List.copyOf(lessonOrder);
    if (lessonOrder.isEmpty()) {
      throw new IllegalArgumentException("Expected at least one declared lesson.");
    }
    if (new HashSet<>(lessonOrder).size() != lessonOrder.size()) {
      throw new IllegalArgumentException("Duplicate lesson ID in declared order.");
    }
    Map<LessonId, Lesson> definitions = new HashMap<>();
    for (Lesson lesson : lessons) {
      if (definitions.putIfAbsent(lesson.id(), lesson) != null) {
        throw new IllegalArgumentException("Duplicate lesson ID: " + lesson.id().value());
      }
    }
    List<Lesson> ordered = new ArrayList<>();
    Set<LessonId> earlier = new HashSet<>();
    for (LessonId lessonId : lessonOrder) {
      Lesson lesson = definitions.get(lessonId);
      if (lesson == null) {
        throw new IllegalArgumentException("Missing lesson in declared order: " + lessonId.value());
      }
      for (LessonId prerequisite : lesson.prerequisites()) {
        if (!definitions.containsKey(prerequisite)) {
          throw new IllegalArgumentException(
              "Missing prerequisite " + prerequisite.value() + " for " + lessonId.value());
        }
        if (!earlier.contains(prerequisite)) {
          throw new IllegalArgumentException(
              "Prerequisite must occur earlier: "
                  + prerequisite.value()
                  + " for "
                  + lessonId.value());
        }
      }
      ordered.add(lesson);
      earlier.add(lessonId);
    }
    if (ordered.size() != definitions.size()) {
      throw new IllegalArgumentException("Every lesson must be included in the declared order.");
    }
    lessons = List.copyOf(ordered);
  }
}
