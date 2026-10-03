package org.fruitandfaults.workspace.infra;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.TimeUnit;

import org.fruitandfaults.workspace.application.WorkspaceFiles;

/** Deterministic test-only replacement between anchored attributes and opening the entry. */
public final class FifoFixtureFiles {
  private FifoFixtureFiles() {}

  /**
   * Creates an adapter that replaces the selected regular target with a FIFO at its final key read.
   *
   * @param root fixture workspace
   * @param target relative selected target
   * @return real anchored adapter with a deterministic race hook
   * @throws IOException fixture setup failed
   */
  public static WorkspaceFiles swapping(Path root, String target) throws IOException {
    Path file = root.resolve(target);
    Object key = Files.readAttributes(file, BasicFileAttributes.class).fileKey();
    return new SafeWorkspaceFiles(
        SafeWorkspaceFiles::writeFlushed,
        SafeWorkspaceFiles::createNewChannel,
        attributes -> {
          if (attributes.isRegularFile() && java.util.Objects.equals(key, attributes.fileKey())) {
            try {
              Files.delete(file);
              Process fifo = new ProcessBuilder("mkfifo", file.toString()).start();
              try {
                if (!fifo.waitFor(5, TimeUnit.SECONDS) || fifo.exitValue() != 0) {
                  throw new IOException("FIFO fixture creation failed.");
                }
              } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                throw new IOException("FIFO fixture creation interrupted.", cancelled);
              } finally {
                fifo.destroyForcibly();
                try {
                  fifo.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException cancelled) {
                  Thread.currentThread().interrupt();
                }
              }
              Files.writeString(root.resolve("fifo-ready"), "ready");
            } catch (IOException failure) {
              throw new java.io.UncheckedIOException(failure);
            }
          }
          return attributes.fileKey();
        });
  }
}
