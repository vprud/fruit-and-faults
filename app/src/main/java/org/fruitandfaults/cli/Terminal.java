package org.fruitandfaults.cli;

import java.io.IOException;
import java.util.Optional;

/** Injectable line-oriented terminal capability, owned only by the CLI adapter. */
public interface Terminal {
  /**
   * Whether reading a prompt is permitted.
   *
   * @return explicit interactive capability
   */
  boolean interactive();

  /**
   * Reads a bounded line only when interactive; EOF cancels the prompt.
   *
   * @param prompt human-oriented prompt
   * @return selected input or absence without any noninteractive read
   * @throws IOException on input failure or oversized input
   */
  Optional<String> readLine(String prompt) throws IOException;

  /**
   * Writes human output and diagnostics to their respective streams.
   *
   * @param result one rendered response
   */
  void write(CommandResult result);
}
