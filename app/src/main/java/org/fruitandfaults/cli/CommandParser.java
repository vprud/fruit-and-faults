package org.fruitandfaults.cli;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Parses the six-command surface using only standard-library facilities. */
public final class CommandParser {
  /**
   * Parses common flags before or after the command without reading terminal input.
   *
   * @param tokens literal process argument tokens
   * @return invocation, help/version, or typed usage failure
   */
  public Arguments parse(String[] tokens) {
    if (tokens.length == 0) return failure("Expected a command; observed no command.");
    if (tokens.length > 1024) return failure("Too many arguments.");
    Set<String> flags = new HashSet<>();
    List<String> positional = new ArrayList<>();
    Optional<String> answer = Optional.empty();
    for (int index = 0; index < tokens.length; index++) {
      String token = tokens[index];
      if (token.length() > 65_536) return failure("Argument exceeds the length limit.");
      if (!token.startsWith("-")) {
        positional.add(token);
        continue;
      }
      if (!Set.of("--help", "--version", "--verbose", "--no-color", "--yes", "--answer")
          .contains(token)) return failure("Unknown option.");
      if (!flags.add(token)) return failure("Duplicate option.");
      if (token.equals("--answer")) {
        if (++index == tokens.length
            || tokens[index].startsWith("-")
            || tokens[index].isBlank()
            || tokens[index].length() > 256)
          return failure("--answer requires a stable option ID.");
        answer = Optional.of(tokens[index]);
      }
    }
    if (flags.contains("--version")) {
      if (!positional.isEmpty()
          || flags.contains("--help")
          || flags.contains("--yes")
          || answer.isPresent())
        return failure("Expected --version without arguments; observed extra arguments.");
      return new Arguments.Version();
    }
    if (positional.isEmpty()) {
      return flags.contains("--help")
          ? new Arguments.Help()
          : failure("Expected a command; observed no command.");
    }
    Arguments.Command command;
    try {
      String name = positional.getFirst();
      if (!name.equals(name.toLowerCase(Locale.ROOT))) return failure("Unknown command.");
      command = Arguments.Command.valueOf(name.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException invalid) {
      String unknown = positional.getFirst();
      if (!unknown.matches("[a-zA-Z0-9-]{1,64}")) return failure("Unknown command.");
      return failure("Expected a supported command; observed unknown command '" + unknown + "'.");
    }
    if ((answer.isPresent() && command != Arguments.Command.NEXT)
        || (flags.contains("--yes")
            && command != Arguments.Command.START
            && command != Arguments.Command.NEXT))
      return failure("Option does not apply to this command.");
    int maximum = command == Arguments.Command.START ? 2 : 1;
    if (positional.size() > maximum) return failure("Unexpected extra arguments.");
    if (flags.contains("--help")) return new Arguments.Help();
    if (command == Arguments.Command.START
        && (positional.size() != 2 || positional.get(1).isBlank())) {
      return failure("start requires a separate workspace path.");
    }
    if (command == Arguments.Command.START
        && positional
            .get(1)
            .codePoints()
            .anyMatch(
                value ->
                    Character.isISOControl(value)
                        || Character.getType(value) == Character.FORMAT)) {
      return failure("start path contains control or hidden characters; use a visible path.");
    }
    return new Arguments.Invocation(
        command,
        command == Arguments.Command.START ? Optional.of(positional.get(1)) : Optional.empty(),
        answer,
        flags.contains("--yes"),
        flags.contains("--verbose"),
        flags.contains("--no-color"));
  }

  private static Arguments.Failure failure(String diagnostic) {
    return new Arguments.Failure(
        diagnostic + " Run fruit-and-faults --help for available commands.\n");
  }
}
