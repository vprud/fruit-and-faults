package org.fruitandfaults.validation.application;

import java.util.Arrays;

/** Immutable bounded process input whose diagnostic representation never exposes its contents. */
public final class ProcessInput {
  private final byte[] bytes;

  /**
   * Copies a finite input payload.
   *
   * @param bytes at most 64 KiB
   */
  public ProcessInput(byte[] bytes) {
    if (bytes.length > 65_536) {
      throw new IllegalArgumentException("Process input exceeds its bounded budget.");
    }
    this.bytes = bytes.clone();
  }

  /**
   * Returns a defensive copy, never the retained input buffer.
   *
   * @return copied bounded input
   */
  public byte[] bytes() {
    return bytes.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ProcessInput input && Arrays.equals(bytes, input.bytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(bytes);
  }

  @Override
  public String toString() {
    return "ProcessInput[redacted, length=" + bytes.length + "]";
  }
}
