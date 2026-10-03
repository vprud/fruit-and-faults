package org.fruitandfaults.game;

/** An immutable player position and count of successful moves. */
public record GameState(Coordinate player, int successfulMoves) {
  /** Returns the next state, counting only a successful position change. */
  public GameState move(Direction direction, Board board) {
    // TODO: Use the movement outcome to decide the next complete state.
    throw new UnsupportedOperationException("Implement game-state movement.");
  }
}
