package org.fruitandfaults.lesson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;

import org.fruitandfaults.course.domain.ReflectionOption;
import org.fruitandfaults.course.domain.ReflectionQuestion;
import org.junit.jupiter.api.Test;

class EvaluateReflectionTest {
  private final ReflectionOption wrong =
      new ReflectionOption("test-failure", "Tests failed", "Compilation runs before tests.");
  private final ReflectionOption right =
      new ReflectionOption(
          "compile-first", "Compilation failed", "Correct: tests need compiled classes.");
  private final ReflectionQuestion question =
      new ReflectionQuestion("diagnostics", "What failed?", List.of(wrong, right), "compile-first");

  @Test
  void evaluatesEveryStableOptionWithItsOwnFeedback() {
    var incorrect =
        assertInstanceOf(
            ReflectionResult.Incorrect.class,
            EvaluateReflection.evaluate(question, new ReflectionAnswer("test-failure")));
    assertEquals("Compilation runs before tests.", incorrect.feedback());
    var correct =
        assertInstanceOf(
            ReflectionResult.Correct.class,
            EvaluateReflection.evaluate(question, new ReflectionAnswer("compile-first")));
    assertEquals("compile-first", correct.answer().optionId());
    assertEquals("Correct: tests need compiled classes.", correct.feedback());
  }

  @Test
  void doesNotInterpretDisplayPositionsOrExposeUntrustedUnknownIds() {
    for (String unknown : List.of("1", "2", "unknown", "SECRET\u001b[31m")) {
      var result =
          assertInstanceOf(
              ReflectionResult.UnknownOption.class,
              EvaluateReflection.evaluate(question, new ReflectionAnswer(unknown)));
      assertFalse(result.toString().contains("SECRET"));
      assertFalse(result.toString().contains("compile-first"));
    }
  }

  @Test
  void answerIdentityIsIndependentOfDisplayOrder() {
    var reordered =
        new ReflectionQuestion(
            "diagnostics", "What failed?", List.of(right, wrong), "compile-first");
    for (String id : List.of("test-failure", "compile-first")) {
      assertEquals(
          EvaluateReflection.evaluate(question, new ReflectionAnswer(id)),
          EvaluateReflection.evaluate(reordered, new ReflectionAnswer(id)));
    }
  }

  @Test
  void feedbackCannotActivateTerminalOrBidirectionalControls() {
    var unsafe =
        new ReflectionQuestion(
            "diagnostics",
            "What failed?",
            List.of(
                new ReflectionOption(
                    "test-failure", "Wrong", "Compilation\u001b[31m\u202e before tests."),
                right),
            "compile-first");
    var result =
        assertInstanceOf(
            ReflectionResult.Incorrect.class,
            EvaluateReflection.evaluate(unsafe, new ReflectionAnswer("test-failure")));
    assertFalse(result.feedback().contains("\u001b"));
    assertFalse(result.feedback().contains("\u202e"));
    assertEquals("Compilation[31m before tests.", result.feedback());
  }
}
