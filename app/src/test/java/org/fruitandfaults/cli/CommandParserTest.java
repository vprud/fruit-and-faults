package org.fruitandfaults.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class CommandParserTest {
  @ParameterizedTest
  @ValueSource(strings = {"start", "status", "check", "hint", "lesson", "next", "list"})
  void commonFlagsAreAcceptedOnEitherSideOfEveryCommand(String command) {
    String[] tokens =
        command.equals("start")
            ? new String[] {"--verbose", "--no-color", command, "Моя игра with spaces", "--yes"}
            : new String[] {"--no-color", command, "--verbose"};
    var parsed =
        assertInstanceOf(
            org.fruitandfaults.cli.Arguments.Invocation.class, new CommandParser().parse(tokens));
    assertEquals(command, parsed.command().name().toLowerCase(java.util.Locale.ROOT));
    assertTrue(parsed.verbose());
    assertTrue(parsed.noColor());
    assertEquals(
        command.equals("start") ? Optional.of("Моя игра with spaces") : Optional.empty(),
        parsed.target());
  }

  @Test
  void answerUsesItsStableIdentityAndPathTokensRemainLiteral() {
    var next =
        assertInstanceOf(
            org.fruitandfaults.cli.Arguments.Invocation.class,
            new CommandParser()
                .parse(new String[] {"--answer", "compile-before-tests", "--yes", "next"}));
    assertEquals(Optional.of("compile-before-tests"), next.answer());
    assertTrue(next.yes());
    var start =
        assertInstanceOf(
            org.fruitandfaults.cli.Arguments.Invocation.class,
            new CommandParser()
                .parse(new String[] {"start", "C:\\Моя игра\\new project", "--yes"}));
    assertEquals(Optional.of("C:\\Моя игра\\new project"), start.target());
  }

  @ParameterizedTest
  @MethodSource("invalidInvocations")
  void invalidInvocationsReturnTypedFailures(String[] tokens) {
    var failure =
        assertInstanceOf(
            org.fruitandfaults.cli.Arguments.Failure.class, new CommandParser().parse(tokens));
    assertTrue(failure.diagnostic().contains("--help"));
  }

  static Stream<Arguments> invalidInvocations() {
    return Stream.of(
            new String[] {},
            new String[] {"unknown"},
            new String[] {"start"},
            new String[] {"start", "one", "two"},
            new String[] {"status", "path"},
            new String[] {"start", "workspace\nconcealed"},
            new String[] {"start", "workspace\u202econcealed"},
            new String[] {"next", "--answer"},
            new String[] {"next", "--answer", "--yes"},
            new String[] {"next", "--answer", ""},
            new String[] {"next", "--answer", "one", "--answer", "two"},
            new String[] {"check", "--verbose", "--verbose"},
            new String[] {"check", "--no-color", "--no-color"},
            new String[] {"next", "--yes", "--yes"},
            new String[] {"hint", "--yes"},
            new String[] {"lesson", "--yes"},
            new String[] {"lesson", "--answer", "one"},
            new String[] {"lesson", "path"},
            new String[] {"start", "path", "--answer", "one"},
            new String[] {"check", "--unknown"},
            new String[] {"--help", "--help"},
            new String[] {"--version", "unexpected"},
            new String[] {"status", "--version"},
            new String[] {"--help", "--version"})
        .map(tokens -> Arguments.of((Object) tokens));
  }

  @Test
  void helpSupportsCommandContextWithoutRequiringCommandValues() {
    assertInstanceOf(
        org.fruitandfaults.cli.Arguments.Help.class,
        new CommandParser().parse(new String[] {"start", "--help"}));
    assertInstanceOf(
        org.fruitandfaults.cli.Arguments.Help.class,
        new CommandParser().parse(new String[] {"--help", "--no-color"}));
    assertInstanceOf(
        org.fruitandfaults.cli.Arguments.Version.class,
        new CommandParser().parse(new String[] {"--verbose", "--version"}));
  }

  @Test
  void parserErrorsUseEnglishWithoutEchoingUnsafeInput() {
    var failure =
        assertInstanceOf(
            org.fruitandfaults.cli.Arguments.Failure.class,
            new CommandParser().parse(new String[] {"lesson", "--unknown"}));
    assertTrue(failure.diagnostic().startsWith("Unknown option."));
    assertTrue(failure.diagnostic().contains("--help"));
  }
}
