package org.fruitandfaults.course.application;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import org.fruitandfaults.lesson.ReflectionAnswer;

/**
 * Explicit next invocation with no terminal interaction inside the use case.
 *
 * @param root selected workspace
 * @param answer stable selection, absent when requesting the prompt or recovering a journal
 * @param confirmed authorization to apply the displayed disclosure
 */
public record AdvanceRequest(Path root, Optional<ReflectionAnswer> answer, boolean confirmed) {
  /**
   * Normalizes the selected root and requires explicit answer presence.
   *
   * @param root selected workspace
   * @param answer stable selection or absence
   * @param confirmed explicit disclosure confirmation
   */
  public AdvanceRequest {
    root = root.toAbsolutePath().normalize();
    Objects.requireNonNull(answer);
  }
}
