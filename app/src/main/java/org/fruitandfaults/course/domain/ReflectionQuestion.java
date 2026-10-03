package org.fruitandfaults.course.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A reflection prompt with explicitly identified options and one correct answer.
 *
 * @param id stable question identity
 * @param prompt learner-facing question
 * @param options ordered selectable answers
 * @param correctOptionId identity of the correct option
 */
public record ReflectionQuestion(
    String id, String prompt, List<ReflectionOption> options, String correctOptionId) {
  /** Copies options and validates unique identities and the correct answer. */
  public ReflectionQuestion {
    id = ContentValidation.identifier(id, "question ID");
    prompt = ContentValidation.text(prompt, "question prompt");
    options = List.copyOf(options);
    correctOptionId = ContentValidation.identifier(correctOptionId, "correct option ID");
    if (options.size() < 2) {
      throw new IllegalArgumentException("Expected at least two reflection options.");
    }
    Set<String> ids = new HashSet<>();
    for (ReflectionOption option : options) {
      if (!ids.add(option.id())) {
        throw new IllegalArgumentException("Duplicate option ID: " + option.id());
      }
    }
    if (!ids.contains(correctOptionId)) {
      throw new IllegalArgumentException("Correct option is absent: " + correctOptionId);
    }
  }
}
