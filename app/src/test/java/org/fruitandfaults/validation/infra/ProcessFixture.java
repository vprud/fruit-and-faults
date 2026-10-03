package org.fruitandfaults.validation.infra;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Cross-platform local process fixtures using bounded waits instead of sleeps or a shell. */
public final class ProcessFixture {
  private ProcessFixture() {}

  /**
   * Executes a deterministic process scenario selected by the test.
   *
   * @param args scenario and literal arguments
   * @throws Exception fixture setup or synchronization failed
   */
  public static void main(String[] args) throws Exception {
    switch (args[0]) {
      case "environment" ->
          write(System.out, java.util.Objects.requireNonNull(System.getenv("FAF_TEST_OPTION")));
      case "echo" -> {
        write(System.out, Path.of("").toAbsolutePath() + "\n" + args[1] + "\n");
        write(System.err, "stderr message\n");
      }
      case "failure" -> {
        write(System.out, "useful stdout\n");
        write(System.err, "useful stderr\n");
        System.exit(23);
      }
      case "flood" -> {
        Thread error =
            new Thread(
                () -> {
                  write(System.err, "e".repeat(2_000_000) + "stderr useful tail\n");
                });
        error.start();
        write(System.out, "o".repeat(2_000_000) + "stdout useful tail\n");
        error.join(TimeUnit.SECONDS.toMillis(10));
        if (error.isAlive()) {
          throw new IllegalStateException("Error stream was not drained");
        }
      }
      case "unicode" -> write(System.out, "я".repeat(20) + "\n");
      case "stdin" ->
          write(System.out, new String(System.in.readAllBytes(), StandardCharsets.UTF_8));
      case "ignore-input" -> {
        Files.writeString(Path.of("input-ready"), "ready");
        new CountDownLatch(1).await(1, TimeUnit.MINUTES);
      }
      case "hold" -> new CountDownLatch(1).await(1, TimeUnit.MINUTES);
      case "child" -> {
        Process child =
            new ProcessBuilder(
                    List.of(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        ProcessFixture.class.getName(),
                        "hold"))
                .start();
        Path pidFile = Path.of(args[1]);
        Path staging = pidFile.resolveSibling("child.pid.tmp");
        Files.writeString(staging, Long.toString(child.pid()));
        Files.move(staging, pidFile, StandardCopyOption.ATOMIC_MOVE);
        new CountDownLatch(1).await(1, TimeUnit.MINUTES);
      }
      default -> throw new IllegalArgumentException("Unknown fixture");
    }
  }

  private static void write(PrintStream stream, String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    stream.write(bytes, 0, bytes.length);
    stream.flush();
  }
}
