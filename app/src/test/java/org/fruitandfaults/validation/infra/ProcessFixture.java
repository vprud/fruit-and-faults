package org.fruitandfaults.validation.infra;

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
      case "echo" -> {
        System.out.println(Path.of("").toAbsolutePath());
        System.out.println(args[1]);
        System.err.println("stderr message");
      }
      case "failure" -> {
        System.out.println("useful stdout");
        System.err.println("useful stderr");
        System.exit(23);
      }
      case "flood" -> {
        Thread error =
            new Thread(
                () -> {
                  System.err.print("e".repeat(2_000_000));
                  System.err.println("stderr useful tail");
                });
        error.start();
        System.out.print("o".repeat(2_000_000));
        System.out.println("stdout useful tail");
        error.join(TimeUnit.SECONDS.toMillis(10));
        if (error.isAlive()) {
          throw new IllegalStateException("Error stream was not drained");
        }
      }
      case "unicode" -> System.out.println("я".repeat(20));
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
}
