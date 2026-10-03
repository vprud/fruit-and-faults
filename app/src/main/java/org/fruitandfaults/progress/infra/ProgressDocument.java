package org.fruitandfaults.progress.infra;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * JSON-only progress representation; nullable active fields express unavailable or terminal state.
 *
 * @param formatVersion progress schema version
 * @param courseId stable course identity
 * @param courseContentVersion installed content version
 * @param activeLessonId active lesson identity, absent after completion
 * @param activeLessonOpenedAtRevision opening revision, absent when unavailable
 * @param completedLessonIds completed route prefix
 * @param revealedHintLevels revealed hint counts by lesson
 * @param reflectionAnswers accepted stable answer IDs by completed lesson
 */
record ProgressDocument(
    int formatVersion,
    String courseId,
    int courseContentVersion,
    @Nullable String activeLessonId,
    @Nullable String activeLessonOpenedAtRevision,
    List<String> completedLessonIds,
    Map<String, Integer> revealedHintLevels,
    Map<String, String> reflectionAnswers) {}
