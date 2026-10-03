package org.fruitandfaults.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

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

      Course commands are not yet available in this build.
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
    CommandResult result = commandResult(args);
    out.print(result.stdout());
    err.print(result.stderr());
    return result.exitCode().value();
  }

  private static CommandResult commandResult(String[] args) {
    if (args.length == 0) {
      return invalidInvocation("Expected a command; observed no command.");
    }
    if (args.length > 1 && (args[0].equals("--help") || args[0].equals("--version"))) {
      return invalidInvocation(
          "Expected " + args[0] + " without arguments; observed extra arguments.");
    }
    return switch (args[0]) {
      case "--help" -> new CommandResult(ExitCode.SUCCESS, HELP, "");
      case "--version" -> versionResult();
      case "start", "status", "check", "hint", "next", "list" ->
          new CommandResult(
              ExitCode.INTERNAL_ERROR,
              "",
              "Expected an available course command; observed '"
                  + args[0]
                  + "' is not yet implemented in this build. "
                  + "Run fruit-and-faults --help for available commands.\n");
      default ->
          invalidInvocation(
              "Expected a supported command; observed unknown command '" + args[0] + "'.");
    };
  }

  private static CommandResult invalidInvocation(String diagnostic) {
    return new CommandResult(
        ExitCode.INVALID_ARGUMENTS,
        "",
        diagnostic + " Run fruit-and-faults --help for available commands.\n");
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
