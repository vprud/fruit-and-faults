package org.fruitandfaults.validation.infra;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Fixed, authenticated completion frames; the key is delivered once on private process stdin. */
final class WorkerProtocol {
  private static final String PREFIX = "FRUIT_WORKER ";

  private WorkerProtocol() {}

  static byte[] consumeSecret() throws IOException {
    byte[] secret;
    try (var input = System.in) {
      secret = input.readNBytes(33);
    }
    System.setIn(java.io.InputStream.nullInputStream());
    if (secret.length != 32) {
      throw new IOException("Invalid worker input.");
    }
    return secret;
  }

  static String frame(byte[] secret, String payload) {
    return PREFIX + payload + " " + HexFormat.of().formatHex(signature(secret, payload));
  }

  static java.util.Optional<String> verified(ProcessResultView output, byte[] secret) {
    if (output.exitCode() != 0) {
      return java.util.Optional.empty();
    }
    String last = output.stdout().lines().reduce((previous, next) -> next).orElse("");
    if (!last.startsWith(PREFIX) || last.length() > 4096) {
      return java.util.Optional.empty();
    }
    int split = last.lastIndexOf(' ');
    if (split < PREFIX.length() || last.length() - split != 65) {
      return java.util.Optional.empty();
    }
    String payload = last.substring(PREFIX.length(), split);
    try {
      byte[] claimed = HexFormat.of().parseHex(last.substring(split + 1));
      return MessageDigest.isEqual(claimed, signature(secret, payload))
          ? java.util.Optional.of(payload)
          : java.util.Optional.empty();
    } catch (IllegalArgumentException malformed) {
      return java.util.Optional.empty();
    }
  }

  private static byte[] signature(byte[] secret, String payload) {
    try {
      var mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"));
      return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
    } catch (GeneralSecurityException unavailable) {
      throw new IllegalStateException("Worker authentication unavailable.", unavailable);
    }
  }

  record ProcessResultView(int exitCode, String stdout) {}
}
