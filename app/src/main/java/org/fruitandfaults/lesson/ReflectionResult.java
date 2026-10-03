package org.fruitandfaults.lesson;

/** Recognition outcomes; none performs IO or persists an answer. */
public sealed interface ReflectionResult {
  /**
   * The selected stable option is accepted.
   *
   * @param answer accepted identity
   * @param feedback explanation supplied by course content
   */
  record Correct(ReflectionAnswer answer, String feedback) implements ReflectionResult {}

  /**
   * A known wrong option has targeted feedback.
   *
   * @param feedback explanation supplied by course content
   */
  record Incorrect(String feedback) implements ReflectionResult {}

  /**
   * An unrecognized selection has safe feedback without revealing the correct option.
   *
   * @param feedback next useful action
   */
  record UnknownOption(String feedback) implements ReflectionResult {}
}
