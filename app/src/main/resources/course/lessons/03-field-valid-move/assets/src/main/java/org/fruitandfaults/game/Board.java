package org.fruitandfaults.game;

/** A rectangular board whose coordinates begin at zero. */
public record Board(int width, int height) {
  /** Returns whether the coordinate lies within the board's width and height. */
  public boolean contains(Coordinate coordinate) {
    // TODO: Decide whether both coordinate components are inside the board.
    throw new UnsupportedOperationException("Implement board containment.");
  }
}
