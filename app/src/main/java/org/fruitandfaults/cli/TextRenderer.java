package org.fruitandfaults.cli;

import java.nio.file.Path;
import java.util.List;

import org.fruitandfaults.course.application.AdvanceResult;
import org.fruitandfaults.course.application.CourseStatus;
import org.fruitandfaults.course.application.HintResult;
import org.fruitandfaults.course.application.LessonSummary;
import org.fruitandfaults.course.domain.Lesson;
import org.fruitandfaults.git.application.GitLessonGate;
import org.fruitandfaults.progress.domain.CourseProgress;
import org.fruitandfaults.validation.domain.CheckOutcome;
import org.fruitandfaults.validation.domain.Diagnostic;
import org.fruitandfaults.validation.domain.FailureCategory;
import org.fruitandfaults.workspace.application.StartResult;
import org.jspecify.annotations.Nullable;

/** Renders typed results with stable routing, bounded text, and opt-in terminal color. */
public final class TextRenderer {
  private final boolean color;

  /**
   * Enables color only for an interactive terminal unless explicitly disabled.
   *
   * @param interactive terminal capability
   * @param noColor explicit color opt-out
   */
  public TextRenderer(boolean interactive, boolean noColor) {
    color = interactive && !noColor;
  }

  /**
   * Renders validation feedback and preserves its stable failure category.
   *
   * @param outcome complete validation result
   * @param verbose diagnostic detail requested
   * @return human success on stdout or failures on stderr
   */
  public CommandResult check(CheckOutcome outcome, boolean verbose) {
    if (outcome instanceof CheckOutcome.Passed passed) {
      return success("Проверка пройдена.\n" + observations(passed.diagnostics()));
    }
    var failed = (CheckOutcome.Failed) outcome;
    StringBuilder diagnostics = new StringBuilder(observations(failed.diagnostics()));
    if (verbose) diagnostics.append("Категория: ").append(failed.category()).append('\n');
    return new CommandResult(exitCode(failed.category()), "", diagnostics.toString());
  }

  private String observations(List<Diagnostic> diagnostics) {
    StringBuilder text = new StringBuilder();
    for (Diagnostic value :
        diagnostics.subList(Math.max(0, diagnostics.size() - 32), diagnostics.size())) {
      text.append(diagnostic(ExitCode.SUCCESS, value, false, null).stderr());
    }
    return text.toString();
  }

  /**
   * Shows expected state, observation, and next action without arbitrary exception messages.
   *
   * @param code stable outcome
   * @param diagnostic typed feedback
   * @param verbose include bounded cause identities
   * @param cause optional adapter failure
   * @return safe stderr response
   */
  public CommandResult diagnostic(
      ExitCode code, Diagnostic diagnostic, boolean verbose, @Nullable Throwable cause) {
    StringBuilder text =
        new StringBuilder("Ожидалось: ")
            .append(safe(diagnostic.expected()))
            .append("\nПолучено: ")
            .append(safe(diagnostic.observed()))
            .append("\nДальше: ")
            .append(safe(diagnostic.nextAction()))
            .append('\n');
    if (verbose && cause != null) {
      text.append("Причина:");
      Throwable current = cause;
      for (int count = 0; current != null && count < 4; count++) {
        text.append(' ').append(safe(current.getClass().getSimpleName()));
        current = current.getCause();
      }
      text.append('\n');
    }
    return new CommandResult(code, "", text.toString());
  }

  /**
   * Displays numbered options and stable IDs without the accepted answer or targeted feedback.
   *
   * @param question safe prompt view
   * @return line-oriented choices
   */
  public String question(AdvanceResult.NeedsAnswer question) {
    StringBuilder text = new StringBuilder(safe(question.prompt())).append('\n');
    for (int index = 0; index < question.options().size(); index++) {
      var option = question.options().get(index);
      text.append("  ")
          .append(index + 1)
          .append(". ")
          .append(safe(option.text()))
          .append(" [")
          .append(safe(option.id()))
          .append("]\n");
    }
    return text.toString();
  }

  CommandResult start(StartResult result, boolean verbose) {
    return switch (result) {
      case StartResult.PreviewRequired preview -> {
        StringBuilder text =
            new StringBuilder("Предпросмотр:\nКаталог: ").append(preview.root()).append('\n');
        preview
            .paths()
            .forEach(path -> text.append("  ").append(relative(preview.root(), path)).append('\n'));
        yield success(text.toString());
      }
      case StartResult.Created created ->
          success(
              "Рабочий каталог создан.\n"
                  + opened(created.progress())
                  + publishing(created.progress()));
      case StartResult.Resumed resumed ->
          success("Продолжение сохранённого курса.\n" + opened(resumed.progress()));
      case StartResult.Conflict conflict -> {
        StringBuilder observed = new StringBuilder(conflict.diagnostic());
        conflict
            .paths()
            .forEach(path -> observed.append("\n  ").append(relative(conflict.root(), path)));
        yield diagnostic(
            ExitCode.WORKSPACE_CONFLICT,
            new Diagnostic(
                "Безопасный пустой каталог или совместимый рабочий каталог курса.",
                observed.toString(),
                "Сохраните свои файлы, проверьте указанные пути и повторите start с подходящим каталогом."),
            verbose,
            null);
      }
      case StartResult.Failed failed ->
          diagnostic(
              exitCode(failed.category()),
              new Diagnostic(
                  "Полная установка CLI и безопасная подготовка рабочего каталога.",
                  failed.diagnostic(),
                  "Сохраните файлы, проверьте Git и установку CLI; повторите start для восстановления."),
              verbose,
              null);
    };
  }

  CommandResult status(CourseStatus result, boolean verbose) {
    if (result instanceof CourseStatus.Unavailable unavailable) {
      return diagnostic(exitCode(unavailable.category()), unavailable.diagnostic(), verbose, null);
    }
    var status = (CourseStatus.Ready) result;
    StringBuilder text = new StringBuilder();
    status
        .activeLesson()
        .ifPresentOrElse(
            active ->
                text.append("Урок: ")
                    .append(active.title())
                    .append(" [")
                    .append(active.id().value())
                    .append("]\nЦель: ")
                    .append(active.goal())
                    .append("\nПодсказки: ")
                    .append(active.hintLevel())
                    .append("/3\n"),
            () -> text.append("Курс завершён.\n"));
    text.append("Ожидаемые файлы (наличие не заменяет проверку):\n");
    status
        .artifacts()
        .forEach(
            artifact ->
                text.append("  ")
                    .append(artifact.path().value())
                    .append(
                        artifact.presence()
                                == org.fruitandfaults.workspace.application.WorkspaceFiles.Presence
                                    .PRESENT
                            ? " — есть\n"
                            : " — отсутствует\n"));
    text.append("Git: ")
        .append(
            status.git().headRevision().map(value -> value.substring(0, 8)).orElse("коммитов нет"))
        .append("; изменённых файлов: ")
        .append(status.git().trackedChanges())
        .append("; новых файлов: ")
        .append(status.git().untrackedChanges())
        .append('\n');
    text.append("origin: ")
        .append(status.git().originPresent() ? "есть" : "нет")
        .append("; upstream: ")
        .append(status.git().upstreamPresent() ? "есть" : "нет")
        .append('\n');
    advice(text, status.advice());
    if (status.continuationAvailable()) text.append("Доступно продолжение курса.\n");
    text.append("Следующая команда: ").append(status.nextCommand()).append('\n');
    return success(text.toString());
  }

  CommandResult hint(HintResult result, boolean verbose) {
    return switch (result) {
      case HintResult.Revealed hint ->
          success("Подсказка " + hint.level() + "/3:\n" + hint.text() + "\n");
      case HintResult.CourseComplete ignored ->
          success("Курс завершён. Активного урока для подсказки нет.\n");
      case HintResult.Unavailable unavailable ->
          diagnostic(exitCode(unavailable.category()), unavailable.diagnostic(), verbose, null);
    };
  }

  CommandResult list(List<LessonSummary> lessons) {
    StringBuilder text = new StringBuilder("Маршрут курса:\n");
    for (int index = 0; index < lessons.size(); index++) {
      var lesson = lessons.get(index);
      text.append(index + 1)
          .append(". ")
          .append(lesson.title())
          .append(" [")
          .append(lesson.id().value())
          .append("] — ")
          .append(
              switch (lesson.state()) {
                case COMPLETED -> "завершён";
                case ACTIVE -> "текущий";
                case AVAILABLE -> "доступен";
                case LOCKED -> "закрыт";
              })
          .append('\n');
    }
    return success(text.toString());
  }

  CommandResult next(AdvanceResult result, boolean verbose) {
    return switch (result) {
      case AdvanceResult.NeedsAnswer question -> success(question(question));
      case AdvanceResult.Incorrect incorrect ->
          diagnostic(
              ExitCode.INCOMPLETE,
              new Diagnostic(
                  "Верный ответ на вопрос текущего урока.",
                  incorrect.feedback(),
                  "Обдумайте вопрос и повторите next с другим вариантом."),
              verbose,
              null);
      case AdvanceResult.CheckFailed failed -> check(failed.outcome(), verbose);
      case AdvanceResult.GitBlocked blocked ->
          diagnostic(
              ExitCode.INCOMPLETE,
              new Diagnostic(
                  "Новый локальный коммит и чистый рабочий каталог.",
                  blocked.decision() == GitLessonGate.Decision.MISSING_COMMIT
                      ? "Урок ещё не записан новым локальным коммитом."
                      : "В рабочем каталоге есть незакоммиченные изменения.",
                  "Выполните git status и git diff, сохраните проверенную работу коммитом и повторите next."),
              verbose,
              null);
      case AdvanceResult.PreviewRequired preview -> {
        StringBuilder text = new StringBuilder("Предпросмотр:\n");
        preview
            .plan()
            .filesToCreate()
            .forEach(file -> text.append("  создать ").append(file.path().value()).append('\n'));
        preview
            .plan()
            .alreadyApplied()
            .forEach(
                file -> text.append("  уже раскрыт ").append(file.path().value()).append('\n'));
        if (!preview.plan().filesToCreate().isEmpty()
            || !preview.plan().alreadyApplied().isEmpty()) {
          text.append("  .fruit-and-faults/transition.json (временный журнал)\n")
              .append("  .fruit-and-faults/managed-files.json\n");
        }
        text.append("  .fruit-and-faults/progress.json\n");
        advice(text, preview.advice());
        yield success(text.toString());
      }
      case AdvanceResult.Advanced advanced -> success(opened(advanced.progress()));
      case AdvanceResult.Recovered recovered ->
          success("Переход восстановлен.\n" + opened(recovered.progress()));
      case AdvanceResult.CourseComplete complete -> {
        StringBuilder text = new StringBuilder("Курс завершён.\n");
        advice(text, complete.advice());
        yield success(text.toString());
      }
      case AdvanceResult.Conflict conflict -> {
        StringBuilder observed = new StringBuilder(conflict.diagnostic().observed());
        conflict.paths().forEach(path -> observed.append("\n  ").append(path.path().value()));
        yield diagnostic(
            ExitCode.WORKSPACE_CONFLICT,
            new Diagnostic(
                conflict.diagnostic().expected(),
                observed.toString(),
                conflict.diagnostic().nextAction()),
            verbose,
            null);
      }
      case AdvanceResult.Unavailable unavailable ->
          diagnostic(exitCode(unavailable.category()), unavailable.diagnostic(), verbose, null);
    };
  }

  private static String opened(CourseProgress progress) {
    if (progress.activeLessonId().isEmpty()) return "Курс завершён.\n";
    Lesson lesson =
        progress
            .course()
            .lessons()
            .get(progress.course().lessonOrder().indexOf(progress.activeLessonId().orElseThrow()));
    return "Урок: "
        + lesson.title()
        + " ["
        + lesson.id().value()
        + "]\nЦель: "
        + lesson.goal()
        + "\n"
        + lesson.instructions()
        + "\nРекомендуемый коммит: "
        + lesson.recommendedCommitMessage()
        + "\n";
  }

  private static String publishing(CourseProgress progress) {
    String commit = progress.course().lessons().getFirst().recommendedCommitMessage();
    return "\nПубликация в GitHub необязательна; курс работает локально.\n"
        + "Создайте на сайте GitHub пустой публичный репозиторий без README, лицензии и .gitignore.\n"
        + "После исправления стартера и успешной проверки просмотрите git status и git diff.\n"
        + "Добавьте проверенные файлы через git add, затем выполните:\n  git diff --cached\n  git commit -m \""
        + commit
        + "\"\n"
        + "Инструкции по origin, main, push и входу через GitHub: docs/publishing-to-github.md.\n"
        + "Не вводите пароли или токены в CLI.\n";
  }

  private static String relative(Path root, Path path) {
    Path normalized = path.toAbsolutePath().normalize();
    if (!normalized.startsWith(root)) return "[путь вне выбранного каталога]";
    String relative = root.relativize(normalized).toString().replace('\\', '/');
    return relative.isEmpty() ? "." : relative;
  }

  private static void advice(StringBuilder text, List<String> advice) {
    for (String value : advice) {
      if (value.contains("origin"))
        text.append("Совет: origin отсутствует; GitHub можно настроить позже.\n");
      else if (value.toLowerCase(java.util.Locale.ROOT).contains("upstream"))
        text.append("Совет: upstream отсутствует; публикация не блокирует урок.\n");
      else if (value.contains("metadata commit"))
        text.append(
            "Совет: создайте финальный коммит метаданных, чтобы клон сохранил завершение курса.\n");
      else text.append("Совет: ").append(value).append('\n');
    }
  }

  /**
   * Formats human-oriented successful text.
   *
   * @param text safe course or CLI text
   * @return success on stdout
   */
  public CommandResult success(String text) {
    // Human views come from bounded installed resources and complete inspected path lists.
    // Do not truncate a preview or remove the one next-command line before confirmation.
    String safe = clean(text, Integer.MAX_VALUE);
    return new CommandResult(
        ExitCode.SUCCESS, color ? "\u001b[32m" + safe + "\u001b[0m" : safe, "");
  }

  /**
   * Maps application categories to the published Task 1 process contract.
   *
   * @param category application failure meaning
   * @return stable delivery exit code
   */
  public static ExitCode exitCode(FailureCategory category) {
    return switch (category) {
      case INCOMPLETE_WORK, MISSING_ARTIFACT -> ExitCode.INCOMPLETE;
      case WORKSPACE_CONFLICT -> ExitCode.WORKSPACE_CONFLICT;
      case COMPILATION_ERROR, TEST_FAILURE -> ExitCode.VALIDATION_FAILURE;
      case TIMEOUT, INTERRUPTED -> ExitCode.VALIDATION_INTERRUPTED;
      case INTERNAL_ERROR -> ExitCode.INTERNAL_ERROR;
    };
  }

  static String safe(String text) {
    return clean(text, 4096);
  }

  private static String clean(String text, int limit) {
    StringBuilder result = new StringBuilder();
    text.codePoints()
        .limit(limit)
        .filter(
            value ->
                value == '\n'
                    || (!Character.isISOControl(value)
                        && Character.getType(value) != Character.FORMAT))
        .forEach(result::appendCodePoint);
    if (text.codePointCount(0, text.length()) > limit) result.append("\n[вывод сокращён]\n");
    return result.toString();
  }
}
