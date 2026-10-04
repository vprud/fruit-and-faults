package org.fruitandfaults.cli;

import java.util.Optional;

/** Explicit parsed invocation or a safe usage failure, without process termination. */
public sealed interface Arguments {
  /** A help request that performs no workspace access. */
  record Help() implements Arguments {}

  /** A version request that performs no workspace access. */
  record Version() implements Arguments {}

  /**
   * Invalid syntax, with no untrusted option values echoed.
   *
   * @param diagnostic actionable usage feedback
   */
  record Failure(String diagnostic) implements Arguments {}

  /**
   * One complete syntactic command; terminal capability is checked before prompts.
   *
   * @param command supported command
   * @param target literal start destination
   * @param answer stable reflection selection
   * @param yes explicit mutation confirmation
   * @param verbose bounded diagnostic detail
   * @param noColor disable terminal color
   */
  record Invocation(
      Command command,
      Optional<String> target,
      Optional<String> answer,
      boolean yes,
      boolean verbose,
      boolean noColor)
      implements Arguments {}

  /** The documented commands, independent of course lesson identities. */
  enum Command {
    /** Create or resume a separate learner workspace. */
    START,
    /** Inspect inexpensive progress and Git facts. */
    STATUS,
    /** Validate cumulative learner behavior. */
    CHECK,
    /** Reveal the next active hint. */
    HINT,
    /** Read the active lesson's complete instructions. */
    LESSON,
    /** Attempt one transactional lesson transition. */
    NEXT,
    /** Display route titles and progress states. */
    LIST
  }
}
