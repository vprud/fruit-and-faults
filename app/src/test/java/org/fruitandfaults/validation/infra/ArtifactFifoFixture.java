package org.fruitandfaults.validation.infra;

import java.nio.file.Path;

import org.fruitandfaults.workspace.infra.FifoFixtureFiles;

/** Executes the production artifact worker with a deterministic pre-open FIFO replacement. */
public final class ArtifactFifoFixture {
  private ArtifactFifoFixture() {}

  /**
   * Runs the anchored worker on the selected fixture entry.
   *
   * @param arguments selected logical artifact path
   * @throws Exception fixture setup failed
   */
  public static void main(String[] arguments) throws Exception {
    ArtifactReadWorker.run(FifoFixtureFiles.swapping(Path.of(""), arguments[0]), arguments);
  }
}
