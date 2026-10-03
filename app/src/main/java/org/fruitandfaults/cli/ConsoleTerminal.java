package org.fruitandfaults.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** UTF-8 line prompts with explicit capability; caller-owned streams remain open. */
public final class ConsoleTerminal implements Terminal {
  private final Reader input;
  private final PrintStream out;
  private final PrintStream err;
  private final boolean interactive;
  private boolean skipLineFeed;

  /**
   * Connects streams without reading them or inferring interactivity from available bytes.
   *
   * @param input learner input
   * @param out human output
   * @param err diagnostics
   * @param interactive explicit console/TTY capability
   */
  public ConsoleTerminal(InputStream input, PrintStream out, PrintStream err, boolean interactive) {
    this(new InputStreamReader(input, StandardCharsets.UTF_8), out, err, interactive);
  }

  /**
   * Connects the native console reader so platform input encoding is retained.
   *
   * @param input decoded native console characters
   * @param out human output
   * @param err diagnostics
   * @param interactive explicit console/TTY capability
   */
  public ConsoleTerminal(Reader input, PrintStream out, PrintStream err, boolean interactive) {
    this.input = input;
    this.out = out;
    this.err = err;
    this.interactive = interactive;
  }

  @Override
  public boolean interactive() {
    return interactive;
  }

  @Override
  public Optional<String> readLine(String prompt) throws IOException {
    if (!interactive) return Optional.empty();
    if (Thread.currentThread().isInterrupted()) throw new IOException("Input interrupted.");
    out.print(prompt);
    out.flush();
    StringBuilder line = new StringBuilder();
    for (int count = 0; count < 258; count++) {
      int character = input.read();
      if (character == -1) return line.isEmpty() ? Optional.empty() : Optional.of(line.toString());
      if (skipLineFeed) {
        skipLineFeed = false;
        if (character == '\n') continue;
      }
      if (character == '\n') return Optional.of(line.toString());
      if (character == '\r') {
        skipLineFeed = true;
        return Optional.of(line.toString());
      }
      if (line.length() == 256) throw new IOException("Input exceeded its limit.");
      line.append((char) character);
    }
    throw new IOException("Input exceeded its limit.");
  }

  @Override
  public void write(CommandResult result) {
    out.print(result.stdout());
    err.print(result.stderr());
    out.flush();
    err.flush();
  }
}
