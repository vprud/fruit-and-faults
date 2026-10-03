package org.fruitandfaults.game;

/** A test-only passing implementation for cumulative journey verification. */
public record Coordinate(int x, int y) {
  /** Returns a new adjacent position in screen coordinates. */
  public Coordinate move(Direction direction) {
    return switch (direction) {
      case UP -> new Coordinate(x, y - 1);
      case RIGHT -> new Coordinate(x + 1, y);
      case DOWN -> new Coordinate(x, y + 1);
      case LEFT -> new Coordinate(x - 1, y);
    };
  }
}
