package org.fruitandfaults.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.fruitandfaults.course.application.AdvanceResult;
import org.fruitandfaults.course.application.CourseStatus;
import org.fruitandfaults.course.application.HintResult;
import org.fruitandfaults.course.application.LessonResult;
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
  private static final Pattern ANSI_CSI = Pattern.compile("\u001b\\[[0-?]*[ -/]*[@-~]");
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
      return new CommandResult(
          ExitCode.SUCCESS,
          styled("\u001b[32m", "Check passed.") + "\n" + observations(passed.diagnostics()),
          "");
    }
    var failed = (CheckOutcome.Failed) outcome;
    StringBuilder diagnostics = new StringBuilder(observations(failed.diagnostics()));
    if (verbose) diagnostics.append("Category: ").append(failed.category()).append('\n');
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
        new StringBuilder("Expected: ")
            .append(safe(diagnostic.expected()))
            .append("\nObserved: ")
            .append(safe(diagnostic.observed()))
            .append("\nNext: ")
            .append(safe(diagnostic.nextAction()))
            .append('\n');
    if (verbose && cause != null) {
      text.append("Cause:");
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
            new StringBuilder("Preview:\nWorkspace: ").append(preview.root()).append('\n');
        preview
            .paths()
            .forEach(path -> text.append("  ").append(relative(preview.root(), path)).append('\n'));
        yield success(text.toString());
      }
      case StartResult.Created created -> summary(created.progress(), "Workspace created.");
      case StartResult.Resumed resumed -> summary(resumed.progress(), "Course resumed.");
      case StartResult.Conflict conflict -> {
        StringBuilder observed = new StringBuilder(conflict.diagnostic());
        conflict
            .paths()
            .forEach(path -> observed.append("\n  ").append(relative(conflict.root(), path)));
        yield diagnostic(
            ExitCode.WORKSPACE_CONFLICT,
            new Diagnostic(
                "A safe empty directory or compatible course workspace.",
                observed.toString(),
                "Keep your files, inspect the listed paths, and follow the guidance above."),
            verbose,
            null);
      }
      case StartResult.Failed failed ->
          diagnostic(
              exitCode(failed.category()),
              new Diagnostic(
                  "A complete CLI installation and safe workspace preparation.",
                  failed.diagnostic(),
                  "Keep your files, inspect Git and the CLI installation, then retry start for recovery."),
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
                text.append("Lesson: ")
                    .append(active.title())
                    .append(" [")
                    .append(active.id().value())
                    .append("]\nGoal: ")
                    .append(active.goal())
                    .append("\nHints: ")
                    .append(active.hintLevel())
                    .append("/3\n"),
            () -> text.append("Course complete.\n"));
    text.append("Expected files (presence is not validation):\n");
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
                            ? " — present\n"
                            : " — missing\n"));
    text.append("Git: ")
        .append(
            status.git().headRevision().map(value -> value.substring(0, 8)).orElse("no commits"))
        .append("; changed files: ")
        .append(status.git().trackedChanges())
        .append("; new files: ")
        .append(status.git().untrackedChanges())
        .append('\n');
    text.append("origin: ")
        .append(status.git().originPresent() ? "present" : "absent")
        .append("; upstream: ")
        .append(status.git().upstreamPresent() ? "present" : "absent")
        .append('\n');
    advice(text, status.advice());
    if (status.continuationAvailable()) text.append("A course continuation is available.\n");
    text.append("Next command: ").append(status.nextCommand()).append('\n');
    return success(text.toString());
  }

  CommandResult hint(HintResult result, boolean verbose) {
    return switch (result) {
      case HintResult.Revealed hint ->
          success("Hint " + hint.level() + "/3:\n" + hint.text() + "\n");
      case HintResult.CourseComplete ignored ->
          success("Course complete. No active lesson has a hint.\n");
      case HintResult.Unavailable unavailable ->
          diagnostic(exitCode(unavailable.category()), unavailable.diagnostic(), verbose, null);
    };
  }

  CommandResult list(List<LessonSummary> lessons) {
    StringBuilder text = new StringBuilder("Course route:\n");
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
                case COMPLETED -> "completed";
                case ACTIVE -> "active";
                case AVAILABLE -> "available";
                case LOCKED -> "locked";
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
                  "A correct answer to the current lesson question.",
                  incorrect.feedback(),
                  "Review the question and rerun next with another option."),
              verbose,
              null);
      case AdvanceResult.CheckFailed failed -> check(failed.outcome(), verbose);
      case AdvanceResult.GitBlocked blocked ->
          diagnostic(
              ExitCode.INCOMPLETE,
              new Diagnostic(
                  "A new local commit and a clean workspace.",
                  blocked.decision() == GitLessonGate.Decision.MISSING_COMMIT
                      ? "The lesson has no new local commit yet."
                      : "The workspace has uncommitted changes.",
                  "Run git status and git diff, commit reviewed work, then rerun next."),
              verbose,
              null);
      case AdvanceResult.PreviewRequired preview -> {
        StringBuilder text = new StringBuilder("Preview:\n");
        preview
            .plan()
            .filesToCreate()
            .forEach(file -> text.append("  create ").append(file.path().value()).append('\n'));
        preview
            .plan()
            .alreadyApplied()
            .forEach(
                file ->
                    text.append("  already disclosed ").append(file.path().value()).append('\n'));
        if (!preview.plan().filesToCreate().isEmpty()
            || !preview.plan().alreadyApplied().isEmpty()) {
          text.append("  .fruit-and-faults/transition.json (temporary journal)\n")
              .append("  .fruit-and-faults/managed-files.json\n");
        }
        text.append("  .fruit-and-faults/progress.json\n");
        advice(text, preview.advice());
        yield success(text.toString());
      }
      case AdvanceResult.Advanced advanced -> summary(advanced.progress(), "Lesson opened.");
      case AdvanceResult.Recovered recovered ->
          summary(recovered.progress(), "Transition recovered.");
      case AdvanceResult.CourseComplete complete -> {
        StringBuilder text = new StringBuilder("Course complete.\n");
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

  /**
   * Renders the complete text of the active installed lesson.
   *
   * @param result selected lesson or safe failure
   * @param verbose include bounded failure detail
   * @return lesson text on stdout or a diagnostic on stderr
   */
  public CommandResult lesson(LessonResult result, boolean verbose) {
    if (result instanceof LessonResult.Unavailable unavailable)
      return diagnostic(exitCode(unavailable.category()), unavailable.diagnostic(), verbose, null);
    if (result instanceof LessonResult.CourseComplete)
      return success("Course complete. No lesson is active.\n");
    Lesson lesson = ((LessonResult.Active) result).lesson();
    String instructions = lesson.instructions();
    if (instructions.startsWith("# ")) {
      int newline = instructions.indexOf('\n');
      if (newline >= 0) instructions = instructions.substring(newline + 1).stripLeading();
    }
    StringBuilder text = new StringBuilder(heading(lesson)).append('\n');
    text.append(clean(lesson.goal(), Integer.MAX_VALUE)).append("\n\n");
    text.append(clean(instructions.strip(), Integer.MAX_VALUE)).append("\n\n");
    text.append(label("Suggested commit:"))
        .append(' ')
        .append(clean(lesson.recommendedCommitMessage(), Integer.MAX_VALUE))
        .append('\n');
    publishingGuide(lesson, text);
    return new CommandResult(ExitCode.SUCCESS, text.toString(), "");
  }

  private CommandResult summary(CourseProgress progress, String outcome) {
    if (progress.activeLessonId().isEmpty()) return success("Course complete.\n");
    Lesson lesson =
        progress
            .course()
            .lessons()
            .get(progress.course().lessonOrder().indexOf(progress.activeLessonId().orElseThrow()));
    StringBuilder text = new StringBuilder(styled("\u001b[32m", outcome)).append("\n\n");
    text.append(heading(lesson)).append('\n');
    text.append(clean(lesson.goal(), Integer.MAX_VALUE)).append("\n\n");
    text.append(label("Next:"))
        .append("             ")
        .append(styled("\u001b[36m", "fruit-and-faults lesson"))
        .append('\n');
    text.append(label("Suggested commit:"))
        .append(' ')
        .append(clean(lesson.recommendedCommitMessage(), Integer.MAX_VALUE))
        .append('\n');
    publishingGuide(lesson, text);
    return new CommandResult(ExitCode.SUCCESS, text.toString(), "");
  }

  private String heading(Lesson lesson) {
    return styled("\u001b[1m", "LESSON · " + lesson.title() + " [" + lesson.id().value() + "]");
  }

  private String label(String value) {
    return styled("\u001b[1m", value);
  }

  private String styled(String prefix, String value) {
    String cleaned = clean(value, Integer.MAX_VALUE);
    return color ? prefix + cleaned + "\u001b[0m" : cleaned;
  }

  private void publishingGuide(Lesson lesson, StringBuilder text) {
    lesson.assets().stream()
        .map(asset -> asset.relativePath())
        .filter(path -> path.equals("docs/publishing-to-github.md"))
        .findFirst()
        .ifPresent(
            path -> text.append(label("GitHub (optional):")).append(' ').append(path).append('\n'));
  }

  private static String relative(Path root, Path path) {
    Path normalized = path.toAbsolutePath().normalize();
    if (!normalized.startsWith(root)) return "[path outside selected workspace]";
    String relative = root.relativize(normalized).toString().replace('\\', '/');
    return relative.isEmpty() ? "." : relative;
  }

  private static void advice(StringBuilder text, List<String> advice) {
    for (String value : advice) {
      if (value.contains("origin"))
        text.append("Tip: origin is absent; GitHub can be configured later.\n");
      else if (value.toLowerCase(java.util.Locale.ROOT).contains("upstream"))
        text.append("Tip: upstream is absent; publishing does not block the lesson.\n");
      else if (value.contains("metadata commit"))
        text.append("Tip: make a final metadata commit so a clone preserves course completion.\n");
      else text.append("Tip: ").append(value).append('\n');
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
    return new CommandResult(ExitCode.SUCCESS, clean(text, Integer.MAX_VALUE), "");
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
    String withoutAnsi = ANSI_CSI.matcher(text).replaceAll("");
    StringBuilder result = new StringBuilder();
    withoutAnsi
        .codePoints()
        .limit(limit)
        .filter(
            value ->
                value == '\n'
                    || (!Character.isISOControl(value)
                        && Character.getType(value) != Character.FORMAT))
        .forEach(result::appendCodePoint);
    if (withoutAnsi.codePointCount(0, withoutAnsi.length()) > limit)
      result.append("\n[output truncated]\n");
    return result.toString();
  }
}
