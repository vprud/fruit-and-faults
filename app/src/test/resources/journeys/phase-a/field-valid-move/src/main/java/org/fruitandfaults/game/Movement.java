package org.fruitandfaults.game;

/** A test-only passing movement implementation. */
public final class Movement {
  private Movement() {}

  /** Keeps the original coordinate when its neighbor falls outside the board. */
  public static MoveResult move(Coordinate current, Direction direction, Board board) {
    Coordinate candidate = current.move(direction);
    return board.contains(candidate)
        ? new MoveResult(candidate, MoveStatus.MOVED)
        : new MoveResult(current, MoveStatus.BLOCKED);
  }
}
