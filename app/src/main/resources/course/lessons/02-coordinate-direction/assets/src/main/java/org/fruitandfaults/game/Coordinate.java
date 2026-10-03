package org.fruitandfaults.game;

/** An immutable position in screen coordinates. */
public record Coordinate(int x, int y) {
  /** Returns the adjacent coordinate for the direction, preserving this value. */
  public Coordinate move(Direction direction) {
    // TODO: Calculate one adjacent position for each direction.
    throw new UnsupportedOperationException("Implement coordinate movement.");
  }
}
