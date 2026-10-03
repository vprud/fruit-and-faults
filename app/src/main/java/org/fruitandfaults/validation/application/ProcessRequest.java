package org.fruitandfaults.validation.application;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Literal process arguments, a selected directory, and finite resource limits.
 *
 * @param arguments executable followed by separate literal arguments, never shell text
 * @param workingDirectory selected existing real directory
 * @param timeout deadline including output collection, at most one hour
 * @param maxCapturedBytes aggregate retained stdout/stderr budget, at most 16 MiB
 */
public record ProcessRequest(
    List<String> arguments, Path workingDirectory, Duration timeout, int maxCapturedBytes) {
  /** Copies arguments, normalizes the directory, and rejects invalid or excessive limits. */
  public ProcessRequest {
    arguments = List.copyOf(arguments);
    workingDirectory = workingDirectory.toAbsolutePath().normalize();
    Objects.requireNonNull(timeout);
    if (arguments.isEmpty()
        || arguments.getFirst().isBlank()
        || arguments.size() > 1024
        || arguments.stream()
            .anyMatch(argument -> argument.indexOf('\0') >= 0 || argument.length() > 65_536)
        || timeout.isNegative()
        || timeout.isZero()
        || timeout.compareTo(Duration.ofHours(1)) > 0
        || maxCapturedBytes < 1
        || maxCapturedBytes > 16_777_216) {
      throw new IllegalArgumentException(
          "Expected literal arguments and bounded positive process limits.");
    }
  }
}
