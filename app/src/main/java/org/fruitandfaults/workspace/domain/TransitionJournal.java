package org.fruitandfaults.workspace.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.fruitandfaults.course.domain.LessonId;
import org.fruitandfaults.progress.domain.CourseProgress;

/**
 * A complete immutable recovery plan, containing facts rather than learner content.
 *
 * @param formatVersion journal schema version
 * @param fromLessonId prior active lesson, empty for initial disclosure
 * @param toLessonId exact lesson being opened
 * @param assets ordered identity, destination, fingerprint, and policy declarations
 * @param expectedManifestVersion ownership schema version
 * @param expectedManaged exact prior ownership, empty only when the manifest was absent
 * @param expectedProgress exact prior progress, empty only for initial disclosure
 * @param intendedProgress complete progress snapshot to commit last
 */
public record TransitionJournal(
    int formatVersion,
    Optional<LessonId> fromLessonId,
    LessonId toLessonId,
    List<ManagedFile> assets,
    int expectedManifestVersion,
    Optional<ManagedFiles> expectedManaged,
    Optional<CourseProgress> expectedProgress,
    CourseProgress intendedProgress) {
  /** Validates a single course transition and all declared ownership facts. */
  public TransitionJournal {
    Objects.requireNonNull(fromLessonId);
    Objects.requireNonNull(toLessonId);
    Objects.requireNonNull(expectedManaged);
    Objects.requireNonNull(expectedProgress);
    Objects.requireNonNull(intendedProgress);
    assets = List.copyOf(assets);
    if (formatVersion != 1
        || expectedManifestVersion != 1
        || !intendedProgress.activeLessonId().equals(Optional.of(toLessonId))
        || !fromLessonId.equals(expectedProgress.flatMap(CourseProgress::activeLessonId))) {
      throw new IllegalArgumentException(
          "Expected a version-one journal with exact from/to lessons.");
    }
    var course = intendedProgress.course();
    var lesson = course.lessons().get(course.lessonOrder().indexOf(toLessonId));
    List<ManagedFile> declared =
        lesson.assets().stream()
            .map(
                asset ->
                    new ManagedFile(
                        WorkspacePath.parse(asset.relativePath()),
                        asset.id(),
                        asset.sha256(),
                        toLessonId,
                        asset.policy()))
            .toList();
    if (!assets.equals(declared)) {
      throw new IllegalArgumentException("Expected the exact ordered assets of the target lesson.");
    }
    CourseProgress validIntended;
    if (expectedProgress.isEmpty()) {
      validIntended =
          CourseProgress.opening(
              course, intendedProgress.activeLessonOpenedAtRevision().orElse(null));
    } else {
      CourseProgress previous = expectedProgress.orElseThrow();
      CourseProgress rebound =
          previous.continueWith(
              course, intendedProgress.activeLessonOpenedAtRevision().orElse(null));
      if (fromLessonId.isEmpty()) {
        if (previous.lessons().size() >= course.lessons().size()) {
          throw new IllegalArgumentException(
              "Expected appended content after a completed historical route.");
        }
        validIntended = rebound;
      } else {
        var previousLesson =
            course.lessons().get(course.lessonOrder().indexOf(fromLessonId.orElseThrow()));
        validIntended =
            rebound
                .advance(
                    previousLesson.id(),
                    previousLesson.question().correctOptionId(),
                    intendedProgress.activeLessonOpenedAtRevision().orElse(null))
                .progress();
      }
    }
    if (!validIntended.equals(intendedProgress)) {
      throw new IllegalArgumentException(
          "Expected one valid progress transition without changed hints or answers.");
    }
    ManagedFiles prior = expectedManaged.orElse(ManagedFiles.empty());
    for (ManagedFile asset : assets) {
      if (prior.find(asset.path()).filter(file -> !file.equals(asset)).isPresent()) {
        throw new IllegalArgumentException("Expected unambiguous unchanged ownership.");
      }
    }
    List<ManagedFile> combined = new ArrayList<>(prior.files());
    for (ManagedFile asset : assets) {
      if (prior.find(asset.path()).isEmpty()) {
        combined.add(asset);
      }
    }
    if (combined.stream().anyMatch(file -> file.path().isToolMetadata())) {
      throw new IllegalArgumentException("Learner assets cannot overlap tool-owned metadata.");
    }
    if (!new ManagedFiles(combined).aliasConflicts().isEmpty()) {
      throw new IllegalArgumentException("Expected ownership without portable path aliases.");
    }
  }

  /**
   * Derives the exact ownership snapshot published before progress.
   *
   * @return prior ownership plus each newly disclosed asset once
   */
  public ManagedFiles intendedManaged() {
    ManagedFiles prior = expectedManaged.orElse(ManagedFiles.empty());
    List<ManagedFile> combined = new ArrayList<>(prior.files());
    assets.stream().filter(file -> prior.find(file.path()).isEmpty()).forEach(combined::add);
    return new ManagedFiles(combined);
  }
}
