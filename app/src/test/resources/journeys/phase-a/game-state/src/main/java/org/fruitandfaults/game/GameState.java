package org.fruitandfaults.game;

/** A test-only passing game-state implementation. */
public record GameState(Coordinate player, int successfulMoves) {
  /** Counts successful movement while retaining blocked state values. */
  public GameState move(Direction direction, Board board) {
    MoveResult movement = Movement.move(player, direction, board);
    return switch (movement.status()) {
      case MOVED -> new GameState(movement.coordinate(), successfulMoves + 1);
      case BLOCKED -> this;
    };
  }
}
