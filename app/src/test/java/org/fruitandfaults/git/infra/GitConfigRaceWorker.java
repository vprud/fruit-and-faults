package org.fruitandfaults.git.infra;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test worker that deterministically changes a regular config to a FIFO after attributes. */
public final class GitConfigRaceWorker {
  private GitConfigRaceWorker() {}

  /**
   * Runs the blocking anchored-open race under the production scanner.
   *
   * @param arguments selected config name, retained path, and ready marker
   */
  public static void main(String[] arguments) throws Exception {
    Path root = Path.of("").toAbsolutePath().normalize();
    Path target = root.resolve(".git").resolve(arguments[0]);
    Path retained = Path.of(arguments[1]);
    Path ready = Path.of(arguments[2]);
    AtomicBoolean swapped = new AtomicBoolean();
    ProcessGitRepository.inspectLocalConfigurations(
        root,
        (name, attributes) -> {
          if (name.toString().equals(arguments[0]) && swapped.compareAndSet(false, true)) {
            Files.move(target, retained);
            createFifo(target);
            Files.writeString(ready, Long.toString(ProcessHandle.current().pid()));
          }
          return attributes.fileKey();
        });
  }

  private static void createFifo(Path target) throws IOException {
    Process fifo = new ProcessBuilder("mkfifo", target.toString()).start();
    try {
      if (!await(fifo) || fifo.exitValue() != 0) {
        throw new IOException("FIFO fixture creation failed");
      }
    } finally {
      fifo.destroyForcibly();
      await(fifo);
    }
  }

  private static boolean await(Process process) throws IOException {
    try {
      return process.waitFor(5, TimeUnit.SECONDS);
    } catch (InterruptedException cancelled) {
      Thread.currentThread().interrupt();
      throw new IOException("FIFO fixture interrupted", cancelled);
    }
  }
}
