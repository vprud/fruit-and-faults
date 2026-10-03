package org.fruitandfaults.course.domain;

/**
 * A selectable answer and feedback explaining that choice.
 *
 * @param id stable answer identity
 * @param text learner-facing answer text
 * @param feedback targeted explanation shown after selection
 */
public record ReflectionOption(String id, String text, String feedback) {
  /** Validates the answer identity and text. */
  public ReflectionOption {
    id = ContentValidation.identifier(id, "option ID");
    text = ContentValidation.text(text, "option text");
    feedback = ContentValidation.text(feedback, "option feedback");
  }
}
