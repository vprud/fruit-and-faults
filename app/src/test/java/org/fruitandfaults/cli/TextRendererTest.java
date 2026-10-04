package org.fruitandfaults.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.fruitandfaults.course.application.AdvanceResult;
import org.fruitandfaults.course.application.CourseStatus;
import org.fruitandfaults.course.domain.LessonId;
import org.fruitandfaults.git.application.GitStatus;
import org.fruitandfaults.validation.domain.CheckOutcome;
import org.fruitandfaults.validation.domain.Diagnostic;
import org.fruitandfaults.validation.domain.FailureCategory;
import org.fruitandfaults.workspace.application.StartResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TextRendererTest {
  @ParameterizedTest
  @CsvSource({
    "INCOMPLETE_WORK,1",
    "MISSING_ARTIFACT,1",
    "WORKSPACE_CONFLICT,3",
    "COMPILATION_ERROR,4",
    "TEST_FAILURE,4",
    "TIMEOUT,5",
    "INTERRUPTED,5",
    "INTERNAL_ERROR,10"
  })
  void failedChecksRetainStableExitCodesAndDiagnosticRouting(FailureCategory category, int code)
      throws IOException {
    CommandResult result =
        new TextRenderer(false, false)
            .check(
                new CheckOutcome.Failed(
                    category,
                    List.of(
                        new Diagnostic(
                            "Работающий Starter",
                            "Starter.java:7",
                            "Исправьте строку и выполните check"))),
                false);
    assertEquals(code, result.exitCode().value());
    assertEquals("", result.stdout());
    assertTrue(result.stderr().contains("Ожидалось: Работающий Starter\n"));
    assertTrue(result.stderr().contains("Получено: Starter.java:7\n"));
    assertTrue(result.stderr().contains("Дальше: Исправьте строку и выполните check\n"));
    assertFalse(result.stderr().contains("\u001b"));
    assertEquals(transcript("check-failed.stderr.txt"), result.stderr());
  }

  @Test
  void passingCheckUsesStdoutAndColorOnlyWhenInteractiveAndEnabled() throws IOException {
    var passed = new CheckOutcome.Passed(List.of());
    assertEquals(
        "Проверка пройдена.\n", new TextRenderer(false, false).check(passed, false).stdout());
    assertEquals(
        transcript("check-passed.stdout.txt"),
        new TextRenderer(false, false).check(passed, false).stdout());
    assertEquals("", new TextRenderer(true, false).check(passed, false).stderr());
    assertTrue(new TextRenderer(true, false).check(passed, false).stdout().contains("\u001b[32m"));
    assertFalse(new TextRenderer(true, true).check(passed, false).stdout().contains("\u001b"));
  }

  @Test
  void verboseCauseIsBoundedAndDoesNotExposeExceptionMessagesOrStackTraces() {
    var cause =
        new IOException("token=SECRET /Users/private/hidden", new IllegalStateException("SECRET"));
    var diagnostic = new Diagnostic("Доступный курс", "Ошибка адаптера", "Переустановите CLI");
    var renderer = new TextRenderer(false, false);
    assertFalse(
        renderer
            .diagnostic(ExitCode.INTERNAL_ERROR, diagnostic, false, cause)
            .stderr()
            .contains("IOException"));
    String verbose = renderer.diagnostic(ExitCode.INTERNAL_ERROR, diagnostic, true, cause).stderr();
    assertTrue(verbose.contains("IOException"));
    assertTrue(verbose.contains("IllegalStateException"));
    assertFalse(verbose.contains("SECRET"));
    assertFalse(verbose.contains("/Users"));
    assertFalse(verbose.contains("\tat "));
    assertTrue(verbose.length() < 1024);
  }

  @Test
  void startConflictPreservesApplicationRecoveryGuidanceWithoutRecommendingStartAgain() {
    Path root = Path.of("workspace").toAbsolutePath();
    var result =
        new TextRenderer(false, false)
            .start(
                new StartResult.Conflict(
                    root,
                    "Preserve pending advancement and recover with fruit-and-faults next.",
                    List.of(root.resolve(".fruit-and-faults/transition.json"))),
                false);
    assertEquals(3, result.exitCode().value());
    assertEquals("", result.stdout());
    assertTrue(result.stderr().contains("fruit-and-faults next"));
    assertTrue(
        result
            .stderr()
            .endsWith(
                "Дальше: Сохраните свои файлы, проверьте указанные пути и следуйте указаниям выше.\n"));
    assertFalse(result.stderr().contains("повторите start"));
    assertFalse(result.stderr().contains(root.toString()));
  }

  @Test
  void reflectionDisplaysNumberedStableChoicesWithoutGradingData() throws IOException {
    var question =
        new AdvanceResult.NeedsAnswer(
            new LessonId("first-run"),
            "diagnostics",
            "Что произошло?",
            List.of(
                new AdvanceResult.Option("compile-before-tests", "Компиляция"),
                new AdvanceResult.Option("tests", "Тесты")));
    assertEquals(
        "Что произошло?\n  1. Компиляция [compile-before-tests]\n  2. Тесты [tests]\n",
        new TextRenderer(false, false).question(question));
    assertEquals(
        transcript("reflection.stdout.txt"), new TextRenderer(false, false).question(question));
  }

  @Test
  void terminalNeverReadsOrPromptsWithoutInteractiveCapability() throws IOException {
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();
    var terminal =
        new ConsoleTerminal(
            new InputStream() {
              @Override
              public int read() {
                throw new AssertionError("Noninteractive input must never be read");
              }

              @Override
              public int read(byte[] bytes, int offset, int length) {
                return read();
              }
            },
            stream(output),
            stream(error),
            false);
    assertTrue(terminal.readLine("Создать? ").isEmpty());
    terminal.write(new CommandResult(ExitCode.SUCCESS, "Готово\n", "Диагностика\n"));
    assertEquals("Готово\n", output.toString(StandardCharsets.UTF_8));
    assertEquals("Диагностика\n", error.toString(StandardCharsets.UTF_8));
  }

  @Test
  void interactiveTerminalReadsUtf8LinesAndEndsAtEof() throws IOException {
    var output = new ByteArrayOutputStream();
    var terminal =
        new ConsoleTerminal(
            new ByteArrayInputStream("да\r\n2\n".getBytes(StandardCharsets.UTF_8)),
            stream(output),
            stream(new ByteArrayOutputStream()),
            true);
    assertEquals("да", terminal.readLine("Подтвердить? ").orElseThrow());
    assertEquals("2", terminal.readLine("Вариант: ").orElseThrow());
    assertTrue(terminal.readLine("Ещё: ").isEmpty());
    assertEquals("Подтвердить? Вариант: Ещё: ", output.toString(StandardCharsets.UTF_8));
  }

  @Test
  void completePreviewAndStatusRecommendationSurviveLongHumanOutput() {
    Path root = Path.of("workspace").toAbsolutePath();
    List<Path> paths =
        java.util.stream.IntStream.range(0, 100)
            .mapToObj(index -> root.resolve("section-" + index + "/" + "x".repeat(60) + ".java"))
            .toList();
    var renderer = new TextRenderer(false, false);
    String preview = renderer.start(new StartResult.PreviewRequired(root, paths), false).stdout();
    assertTrue(preview.contains("section-99/"));
    assertFalse(preview.contains("вывод сокращён"));
    var status =
        new CourseStatus.Ready(
            Optional.of(
                new CourseStatus.ActiveLesson(
                    new LessonId("first-run"), "Первый урок", "Цель " + "д".repeat(6000), 0)),
            List.of(),
            new GitStatus(Optional.empty(), 0, 0, false, false),
            Optional.empty(),
            List.of(),
            false,
            "fruit-and-faults check");
    String output = renderer.status(status, false).stdout();
    assertTrue(output.endsWith("Следующая команда: fruit-and-faults check\n"));
    assertEquals(1, output.split("Следующая команда:", -1).length - 1);
  }

  @Test
  void diagnosticsStripTerminalAndBidirectionalControlsAndStayBounded() {
    var result =
        new TextRenderer(false, false)
            .diagnostic(
                ExitCode.INTERNAL_ERROR,
                new Diagnostic("ожидание", "\u001b\u202e" + "а".repeat(10000), "следующий шаг"),
                false,
                null);
    assertFalse(result.stderr().contains("\u001b"));
    assertFalse(result.stderr().contains("\u202e"));
    assertTrue(result.stderr().contains("вывод сокращён"));
    assertTrue(result.stderr().length() < 5000);
  }

  @Test
  void oversizedInputAndPreexistingInterruptionStopPrompts() {
    var terminal =
        new ConsoleTerminal(
            new ByteArrayInputStream(("x".repeat(257) + "\n").getBytes(StandardCharsets.UTF_8)),
            stream(new ByteArrayOutputStream()),
            stream(new ByteArrayOutputStream()),
            true);
    assertThrows(IOException.class, () -> terminal.readLine("Вариант: "));
    Thread.currentThread().interrupt();
    try {
      assertThrows(IOException.class, () -> terminal.readLine("Вариант: "));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void nativeCharacterReaderAcceptsBareCarriageReturnLines() throws IOException {
    var terminal =
        new ConsoleTerminal(
            new StringReader("да\r2\r"),
            stream(new ByteArrayOutputStream()),
            stream(new ByteArrayOutputStream()),
            true);
    assertEquals("да", terminal.readLine("Подтвердить? ").orElseThrow());
    assertEquals("2", terminal.readLine("Вариант: ").orElseThrow());
  }

  @Test
  void maximumLengthLineIsAcceptedAfterCrLf() throws IOException {
    var terminal =
        new ConsoleTerminal(
            new StringReader("да\r\n" + "x".repeat(256) + "\r\n"),
            stream(new ByteArrayOutputStream()),
            stream(new ByteArrayOutputStream()),
            true);
    assertEquals("да", terminal.readLine("Подтвердить? ").orElseThrow());
    assertEquals("x".repeat(256), terminal.readLine("Вариант: ").orElseThrow());
  }

  @Test
  void successfulChecksRetainActionableObservationsAndFailureKeepsItsTail() {
    var renderer = new TextRenderer(false, false);
    var note =
        new Diagnostic(
            "Аналогичный тест",
            "Изменились только байты шаблона",
            "Проверьте утверждения; изменение текста не доказывает понимание");
    var result = renderer.check(new CheckOutcome.Passed(List.of(note)), false);
    assertTrue(result.stdout().contains("Изменились только байты шаблона"));
    assertTrue(result.stdout().contains("не доказывает понимание"));
    assertEquals("", result.stderr());
    var diagnostics = new java.util.ArrayList<Diagnostic>();
    for (int index = 0; index < 40; index++) diagnostics.add(note);
    diagnostics.add(
        new Diagnostic("Работающий код", "Последний существенный сбой", "Исправьте строку"));
    String failure =
        renderer
            .check(new CheckOutcome.Failed(FailureCategory.COMPILATION_ERROR, diagnostics), false)
            .stderr();
    assertTrue(failure.contains("Последний существенный сбой"));
    assertEquals(32, failure.split("Ожидалось:", -1).length - 1);
  }

  private static PrintStream stream(ByteArrayOutputStream output) {
    return new PrintStream(output, true, StandardCharsets.UTF_8);
  }

  private static String transcript(String name) throws IOException {
    try (var input =
        java.util.Objects.requireNonNull(
            TextRendererTest.class.getResourceAsStream("/transcripts/" + name))) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
