package org.fruitandfaults.lesson;

import org.fruitandfaults.course.domain.ReflectionQuestion;

/** Evaluates option identity independently of terminal display order. */
public final class EvaluateReflection {
  private EvaluateReflection() {}

  /**
   * Selects only declared feedback and never echoes an unknown answer.
   *
   * @param question validated course question
   * @param answer untrusted stable option selection
   * @return accepted, incorrect, or unknown selection
   */
  public static ReflectionResult evaluate(ReflectionQuestion question, ReflectionAnswer answer) {
    return question.options().stream()
        .filter(option -> option.id().equals(answer.optionId()))
        .findFirst()
        .<ReflectionResult>map(
            option ->
                option.id().equals(question.correctOptionId())
                    ? new ReflectionResult.Correct(answer, feedback(option.feedback()))
                    : new ReflectionResult.Incorrect(feedback(option.feedback())))
        .orElseGet(
            () ->
                new ReflectionResult.UnknownOption(
                    "Choose one of the displayed stable option IDs and retry next."));
  }

  private static String feedback(String declared) {
    StringBuilder safe = new StringBuilder();
    declared
        .codePoints()
        .filter(
            point ->
                (!Character.isISOControl(point) || point == '\n' || point == '\t')
                    && Character.getType(point) != Character.FORMAT)
        .forEach(safe::appendCodePoint);
    return safe.isEmpty() ? "Review the question and choose a displayed option." : safe.toString();
  }
}
