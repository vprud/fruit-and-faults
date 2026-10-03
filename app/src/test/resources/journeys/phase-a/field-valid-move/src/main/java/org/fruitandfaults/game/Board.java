package org.fruitandfaults.game;

/** A test-only passing board implementation. */
public record Board(int width, int height) {
  /** Includes zero and excludes the width and height endpoints. */
  public boolean contains(Coordinate coordinate) {
    return coordinate.x() >= 0
        && coordinate.x() < width
        && coordinate.y() >= 0
        && coordinate.y() < height;
  }
}
