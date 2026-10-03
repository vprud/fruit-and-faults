package org.fruitandfaults.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import org.fruitandfaults.course.application.AdvanceRequest;
import org.fruitandfaults.course.application.AdvanceResult;
import org.fruitandfaults.lesson.ReflectionAnswer;
import org.fruitandfaults.validation.domain.Diagnostic;
import org.fruitandfaults.workspace.application.StartRequest;
import org.fruitandfaults.workspace.application.StartResult;
import org.fruitandfaults.workspace.application.WorkspaceCancellation;
import org.fruitandfaults.workspace.application.WorkspaceLocationException;
import org.jspecify.annotations.Nullable;

/** Starts the Fruit and Faults course CLI. */
public final class FruitAndFaults {
  private static final String HELP =
      """
      Usage: fruit-and-faults <command> [options]

      Commands:
        start <workspace>  Create or resume a learner workspace
        status             Show current lesson and progress
        check              Validate the current lesson
        hint               Show the next hint
        next               Advance after completing the current lesson
        list               Show the course route

      Options:
        --help             Show this help
        --version          Show the CLI version
        --no-color         Disable interactive terminal colors
        --verbose          Show bounded sanitized diagnostics
        --answer <id>      Select a stable reflection option for next
        --yes              Confirm changes for start or next

      Without an interactive terminal, start requires --yes;
      next requires --answer <id> and --yes.
      """;

  private FruitAndFaults() {}

  /**
   * Runs the CLI using the process streams and returns its exit code to the operating system.
   *
   * @param args command-line arguments
   */
  public static void main(String[] args) {
    System.exit(run(args, System.in, System.out, System.err));
  }

  /**
   * Runs one command without terminating the calling process or closing its streams.
   *
   * @param args command-line arguments
   * @param in learner input stream
   * @param out human-oriented output stream
   * @param err diagnostic output stream
   * @return stable numeric process exit code
   */
  public static int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
    var console = Optional.ofNullable(System.console()).filter(java.io.Console::isTerminal);
    boolean interactive = in == System.in && out == System.out && console.isPresent();
    return run(
        args,
        interactive
            ? new ConsoleTerminal(console.orElseThrow().reader(), out, err, true)
            : new ConsoleTerminal(in, out, err, false),
        Path.of("").toAbsolutePath().normalize(),
        ApplicationFactory::create);
  }

  /**
   * Runs injected command ports and terminal capabilities without changing process-global streams.
   *
   * @param args literal invocation
   * @param terminal explicit interactive capability and output routing
   * @param current current directory for upward workspace discovery and relative start paths
   * @param application lazy composition, unused for help, version, or malformed invocation
   * @return stable numeric exit code
   */
  public static int run(
      String[] args,
      Terminal terminal,
      Path current,
      Supplier<ApplicationFactory.Application> application) {
    Arguments parsed = new CommandParser().parse(args);
    CommandResult result =
        switch (parsed) {
          case Arguments.Help ignored -> new CommandResult(ExitCode.SUCCESS, HELP, "");
          case Arguments.Version ignored -> versionResult();
          case Arguments.Failure failure ->
              new CommandResult(ExitCode.INVALID_ARGUMENTS, "", failure.diagnostic());
          case Arguments.Invocation invocation ->
              execute(invocation, terminal, current, application);
        };
    terminal.write(result);
    return result.exitCode().value();
  }

  private static CommandResult execute(
      Arguments.Invocation arguments,
      Terminal terminal,
      Path current,
      Supplier<ApplicationFactory.Application> factory) {
    var renderer = new TextRenderer(terminal.interactive(), arguments.noColor());
    if (!terminal.interactive()
        && arguments.command() == Arguments.Command.NEXT
        && (arguments.answer().isEmpty() || !arguments.yes())) {
      return renderer.diagnostic(
          ExitCode.INVALID_ARGUMENTS,
          new Diagnostic(
              "Явный ответ и подтверждение без интерактивного терминала.",
              "Для next нужны --answer <option-id> и --yes.",
              "Выполните next --answer <option-id> --yes либо откройте интерактивный терминал."),
          false,
          null);
    }
    ApplicationFactory.Application application;
    try {
      application = factory.get();
    } catch (RuntimeException failed) {
      if (WorkspaceCancellation.restoreIfInterrupted(failed))
        return interruption(renderer, arguments.verbose(), failed);
      return renderer.diagnostic(
          ExitCode.INTERNAL_ERROR,
          new Diagnostic(
              "Полная установка курса и CLI.",
              "Курс или адаптер команды недоступен.",
              "Проверьте установку CLI и повторите команду."),
          arguments.verbose(),
          failed);
    }
    try {
      if (arguments.command() == Arguments.Command.START) {
        Path target =
            current.resolve(Path.of(arguments.target().orElseThrow())).toAbsolutePath().normalize();
        return completed(
            start(application, target, arguments, terminal, renderer),
            renderer,
            arguments.verbose());
      }
      Path root = application.locator().locate(current).path();
      CommandResult result =
          switch (arguments.command()) {
            case STATUS -> renderer.status(application.status().apply(root), arguments.verbose());
            case CHECK -> renderer.check(application.check().execute(root), arguments.verbose());
            case HINT -> renderer.hint(application.hint().apply(root), arguments.verbose());
            case NEXT -> next(application, root, arguments, terminal, renderer);
            case LIST -> renderer.list(application.list().execute(root));
            case START -> throw new IllegalStateException("Start dispatched before discovery.");
          };
      return completed(result, renderer, arguments.verbose());
    } catch (WorkspaceLocationException failed) {
      if (failed.reason() == WorkspaceLocationException.Reason.INTERRUPTED
          || WorkspaceCancellation.restoreIfInterrupted(failed)) {
        Thread.currentThread().interrupt();
        return interruption(renderer, arguments.verbose(), failed);
      }
      return renderer.diagnostic(
          failed.reason() == WorkspaceLocationException.Reason.NOT_FOUND
              ? ExitCode.INVALID_ARGUMENTS
              : ExitCode.WORKSPACE_CONFLICT,
          new Diagnostic(
              "Один совместимый рабочий каталог курса.",
              failed.reason() == WorkspaceLocationException.Reason.NOT_FOUND
                  ? "Рабочий каталог курса не найден."
                  : "Рабочий каталог неоднозначен, несовместим или небезопасен.",
              "Перейдите в каталог своего курса или выполните fruit-and-faults start <workspace> --yes."),
          arguments.verbose(),
          failed);
    } catch (InvalidPathException failed) {
      return renderer.diagnostic(
          ExitCode.INVALID_ARGUMENTS,
          new Diagnostic(
              "Допустимый путь рабочего каталога.",
              "Путь не поддерживается этой операционной системой.",
              "Укажите допустимый путь после start."),
          arguments.verbose(),
          failed);
    } catch (IOException | IllegalArgumentException failed) {
      if (WorkspaceCancellation.restoreIfInterrupted(failed))
        return interruption(renderer, arguments.verbose(), failed);
      return renderer.diagnostic(
          ExitCode.WORKSPACE_CONFLICT,
          new Diagnostic(
              "Корректное сохранённое состояние и безопасный ввод.",
              "Состояние курса или ввод не удалось безопасно прочитать.",
              "Сохраните свои файлы, проверьте состояние рабочего каталога и повторите команду."),
          arguments.verbose(),
          failed);
    } catch (RuntimeException failed) {
      if (WorkspaceCancellation.restoreIfInterrupted(failed))
        return interruption(renderer, arguments.verbose(), failed);
      return renderer.diagnostic(
          ExitCode.INTERNAL_ERROR,
          new Diagnostic(
              "Полная установка курса и CLI.",
              "Курс или адаптер команды недоступен.",
              "Проверьте установку CLI и повторите команду."),
          arguments.verbose(),
          failed);
    }
  }

  private static CommandResult start(
      ApplicationFactory.Application application,
      Path root,
      Arguments.Invocation arguments,
      Terminal terminal,
      TextRenderer renderer)
      throws IOException {
    StartResult result = application.start().apply(new StartRequest(root, false));
    if (!(result instanceof StartResult.Created || result instanceof StartResult.Resumed)
        && WorkspaceCancellation.restoreIfInterrupted(null))
      return interruption(renderer, arguments.verbose(), null);
    if (result instanceof StartResult.PreviewRequired) {
      terminal.write(renderer.start(result, arguments.verbose()));
      if (!arguments.yes()) {
        if (!terminal.interactive())
          return renderer.diagnostic(
              ExitCode.INVALID_ARGUMENTS,
              new Diagnostic(
                  "Явное подтверждение создания файлов.",
                  "Для start требуется --yes.",
                  "Проверьте предпросмотр и повторите start <workspace> --yes."),
              false,
              null);
        if (!confirm(terminal, "Создать рабочий каталог? [да/нет]: ")) return cancelled(renderer);
      }
      result = application.start().apply(new StartRequest(root, true));
    }
    return renderer.start(result, arguments.verbose());
  }

  private static CommandResult next(
      ApplicationFactory.Application application,
      Path root,
      Arguments.Invocation arguments,
      Terminal terminal,
      TextRenderer renderer)
      throws IOException {
    Optional<ReflectionAnswer> answer = arguments.answer().map(ReflectionAnswer::new);
    for (int attempt = 0; attempt < 32; attempt++) {
      AdvanceResult result = application.next().apply(new AdvanceRequest(root, answer, false));
      if (!(result instanceof AdvanceResult.Advanced
              || result instanceof AdvanceResult.CourseComplete)
          && WorkspaceCancellation.restoreIfInterrupted(null))
        return interruption(renderer, arguments.verbose(), null);
      if (result instanceof AdvanceResult.NeedsAnswer question) {
        terminal.write(renderer.next(question, arguments.verbose()));
        if (!terminal.interactive())
          return renderer.diagnostic(
              ExitCode.INVALID_ARGUMENTS,
              new Diagnostic(
                  "Стабильный ID варианта.",
                  "Ответ не предоставлен.",
                  "Повторите next --answer <option-id> --yes."),
              false,
              null);
        Optional<String> selected = terminal.readLine("Выберите номер варианта: ");
        if (selected.isEmpty()) return cancelled(renderer);
        // Spaces are forbidden in course option IDs, so malformed input cannot select one.
        String optionId = "invalid selection";
        try {
          int index = Integer.parseInt(selected.orElseThrow().strip()) - 1;
          if (index >= 0 && index < question.options().size())
            optionId = question.options().get(index).id();
        } catch (NumberFormatException invalid) {
          // The use case gives safe unknown-option feedback without echoing terminal input.
        }
        answer = Optional.of(new ReflectionAnswer(optionId));
      } else if (result instanceof AdvanceResult.Incorrect) {
        if (!terminal.interactive()) return renderer.next(result, arguments.verbose());
        terminal.write(renderer.next(result, arguments.verbose()));
        if (!confirm(terminal, "Повторить? [да/нет]: ")) return cancelled(renderer);
        answer = Optional.empty();
      } else if (result instanceof AdvanceResult.PreviewRequired) {
        terminal.write(renderer.next(result, arguments.verbose()));
        if (!arguments.yes() && !confirm(terminal, "Применить изменения? [да/нет]: "))
          return cancelled(renderer);
        return renderer.next(
            application.next().apply(new AdvanceRequest(root, answer, true)), arguments.verbose());
      } else {
        return renderer.next(result, arguments.verbose());
      }
    }
    return renderer.diagnostic(
        ExitCode.INCOMPLETE,
        new Diagnostic(
            "Ответ и подтверждение за ограниченное число попыток.",
            "Лимит попыток исчерпан; прогресс сохранён.",
            "Обдумайте вопрос и повторите next."),
        false,
        null);
  }

  private static boolean confirm(Terminal terminal, String prompt) throws IOException {
    return terminal
        .readLine(prompt)
        .map(
            value ->
                switch (value.strip().toLowerCase(Locale.ROOT)) {
                  case "да", "д", "yes", "y" -> true;
                  default -> false;
                })
        .orElse(false);
  }

  private static CommandResult cancelled(TextRenderer renderer) {
    CommandResult text = renderer.success("Переход отменён. Прогресс сохранён.\n");
    return new CommandResult(ExitCode.INCOMPLETE, text.stdout(), "");
  }

  private static CommandResult interruption(
      TextRenderer renderer, boolean verbose, @Nullable Throwable cause) {
    return renderer.diagnostic(
        ExitCode.VALIDATION_INTERRUPTED,
        new Diagnostic(
            "Завершённая команда без отмены.",
            "Операция прервана; это не признак повреждения рабочего каталога.",
            "Сохраните существующие файлы, прогресс и журнал; повторите команду, когда будете готовы."),
        verbose,
        cause);
  }

  private static CommandResult completed(
      CommandResult result, TextRenderer renderer, boolean verbose) {
    return result.exitCode() != ExitCode.SUCCESS && WorkspaceCancellation.restoreIfInterrupted(null)
        ? interruption(renderer, verbose, null)
        : result;
  }

  private static CommandResult versionResult() {
    try (InputStream version = FruitAndFaults.class.getResourceAsStream("/cli-version.txt")) {
      if (version != null) {
        String cliVersion = new String(version.readAllBytes(), StandardCharsets.UTF_8).strip();
        return new CommandResult(ExitCode.SUCCESS, "fruit-and-faults " + cliVersion + "\n", "");
      }
    } catch (IOException exception) {
      return unavailableVersion();
    }
    return unavailableVersion();
  }

  private static CommandResult unavailableVersion() {
    return new CommandResult(
        ExitCode.INTERNAL_ERROR,
        "",
        "Expected a build-supplied CLI version; observed unavailable version metadata. "
            + "Reinstall fruit-and-faults from a complete distribution.\n");
  }
}
