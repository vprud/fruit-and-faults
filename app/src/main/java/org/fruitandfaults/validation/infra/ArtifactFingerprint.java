package org.fruitandfaults.validation.infra;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Fixed-size byte and whitespace-insensitive fingerprints, never learner source text.
 *
 * @param sha256 original byte hash
 * @param textSha256 hash after removing Unicode whitespace from UTF-8 text
 */
record ArtifactFingerprint(String sha256, String textSha256) {
  static ArtifactFingerprint of(byte[] bytes) {
    StringBuilder normalized = new StringBuilder();
    new String(bytes, StandardCharsets.UTF_8)
        .codePoints()
        .filter(character -> !Character.isWhitespace(character))
        .forEach(normalized::appendCodePoint);
    return new ArtifactFingerprint(
        hash(bytes), hash(normalized.toString().getBytes(StandardCharsets.UTF_8)));
  }

  private static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("Java must provide SHA-256.", unavailable);
    }
  }
}
