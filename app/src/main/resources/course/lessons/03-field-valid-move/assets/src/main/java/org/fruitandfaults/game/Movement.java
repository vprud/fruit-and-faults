package org.fruitandfaults.game;

/** Calculates whether an adjacent position can be reached on a board. */
public final class Movement {
  private Movement() {}

  /** Returns the allowed candidate or the original coordinate with a blocked status. */
  public static MoveResult move(Coordinate current, Direction direction, Board board) {
    // TODO: Combine the coordinate and board public contracts.
    throw new UnsupportedOperationException("Implement valid movement.");
  }
}
