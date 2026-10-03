package org.fruitandfaults.workspace.application;

import java.nio.file.Path;

/**
 * A chosen destination and authorization to apply its exact preview.
 *
 * @param target learner-selected destination
 * @param confirmed whether the caller obtained interactive confirmation or explicit --yes
 */
public record StartRequest(Path target, boolean confirmed) {
  /** Normalizes the selected path before passing it to adapters. */
  public StartRequest {
    target = target.toAbsolutePath().normalize();
  }
}
