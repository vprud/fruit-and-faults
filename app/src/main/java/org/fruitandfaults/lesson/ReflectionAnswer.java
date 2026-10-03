package org.fruitandfaults.lesson;

import java.util.Objects;

/**
 * An untrusted stable option selection, never a display position.
 *
 * @param optionId selected option identity
 */
public record ReflectionAnswer(String optionId) {
  /**
   * Requires an explicit selection without interpreting or echoing it.
   *
   * @param optionId untrusted selected identity
   */
  public ReflectionAnswer {
    Objects.requireNonNull(optionId);
  }

  @Override
  public String toString() {
    return "ReflectionAnswer[selection withheld]";
  }
}
