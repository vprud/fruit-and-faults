package org.fruitandfaults.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FruitAndFaultsTest {
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

  @Test
  void versionPrintsBuildSuppliedVersionToStdout() {
    Invocation invocation = run("--version");

    assertEquals(0, invocation.exitCode());
    assertEquals("fruit-and-faults 0.1.0\n", invocation.stdout());
    assertEquals("", invocation.stderr());
  }

  @Test
  void helpPrintsCommandSurfaceToStdout() {
    Invocation invocation = run("--help");

    assertEquals(0, invocation.exitCode());
    assertEquals(HELP, invocation.stdout());
    assertEquals("", invocation.stderr());
  }

  @Test
  void unknownCommandExplainsExpectedInvocationOnStderr() {
    Invocation invocation = run("unknown");

    assertEquals(2, invocation.exitCode());
    assertEquals("", invocation.stdout());
    assertEquals(
        "Expected a supported command; observed unknown command 'unknown'. "
            + "Run fruit-and-faults --help for available commands.\n",
        invocation.stderr());
    assertNoStackTrace(invocation);
  }

  @Test
  void missingCommandExplainsExpectedInvocationOnStderr() {
    Invocation invocation = run();

    assertEquals(2, invocation.exitCode());
    assertEquals("", invocation.stdout());
    assertEquals(
        "Expected a command; observed no command. "
            + "Run fruit-and-faults --help for available commands.\n",
        invocation.stderr());
    assertNoStackTrace(invocation);
  }

  @ParameterizedTest
  @ValueSource(strings = {"start", "status", "check", "hint", "next", "list"})
  void courseCommandsRequireTheirWorkspaceOrExplicitInvocation(String command) {
    Invocation invocation = run(command);

    assertEquals(2, invocation.exitCode());
    assertEquals("", invocation.stdout());
    assertFalse(invocation.stderr().isBlank());
    assertNoStackTrace(invocation);
  }

  @Test
  void extraArgumentsAreRejectedWithoutPrintingVersion() {
    Invocation invocation = run("--version", "unexpected");

    assertEquals(2, invocation.exitCode());
    assertEquals("", invocation.stdout());
    assertEquals(
        "Expected --version without arguments; observed extra arguments. "
            + "Run fruit-and-faults --help for available commands.\n",
        invocation.stderr());
    assertNoStackTrace(invocation);
  }

  private static Invocation run(String... args) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ByteArrayOutputStream error = new ByteArrayOutputStream();
    try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(error, true, StandardCharsets.UTF_8)) {
      int exitCode = FruitAndFaults.run(args, new ByteArrayInputStream(new byte[0]), out, err);
      return new Invocation(
          exitCode,
          output.toString(StandardCharsets.UTF_8),
          error.toString(StandardCharsets.UTF_8));
    }
  }

  private static void assertNoStackTrace(Invocation invocation) {
    assertFalse(invocation.stderr().contains("Exception"));
    assertFalse(invocation.stderr().contains("\tat "));
  }

  private record Invocation(int exitCode, String stdout, String stderr) {}
}
