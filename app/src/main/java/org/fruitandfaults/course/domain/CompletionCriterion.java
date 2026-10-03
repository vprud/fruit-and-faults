package org.fruitandfaults.course.domain;

/**
 * An observable completion rule identified for selection of an embedded validator.
 *
 * @param id stable validator criterion identity
 * @param description learner-facing description of the expected behavior
 */
public record CompletionCriterion(String id, String description) {
  /** Validates the criterion identity and description. */
  public CompletionCriterion {
    id = ContentValidation.identifier(id, "criterion ID");
    description = ContentValidation.text(description, "criterion description");
  }
}
