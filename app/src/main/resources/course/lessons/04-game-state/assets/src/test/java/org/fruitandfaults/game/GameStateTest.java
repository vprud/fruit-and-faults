package org.fruitandfaults.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GameStateTest {
  @Test
  void successfulMovementChangesThePlayerAndIncrementsTheCount() {
    // Arrange
    Board board = new Board(3, 2);
    GameState original = new GameState(new Coordinate(1, 1), 4);

    // Act
    GameState moved = original.move(Direction.RIGHT, board);

    // Assert
    assertEquals(new GameState(new Coordinate(2, 1), 5), moved);
    assertEquals(new GameState(new Coordinate(1, 1), 4), original);
  }

  @Test
  void blockedMovementPreservesEveryStateValue() {
    // Arrange
    Board board = new Board(3, 2);
    GameState original = new GameState(new Coordinate(2, 1), 5);

    // Act
    GameState blocked = original.move(Direction.RIGHT, board);

    // Assert
    assertEquals(new GameState(new Coordinate(2, 1), 5), blocked);
    assertEquals(new GameState(new Coordinate(2, 1), 5), original);
  }

  @Test
  void aSequenceCountsOnlyAllowedCommandsAndPreservesEarlierStates() {
    // Arrange
    Board board = new Board(3, 2);
    GameState start = new GameState(new Coordinate(1, 1), 0);

    // Act
    GameState atEdge = start.move(Direction.RIGHT, board);
    GameState blocked = atEdge.move(Direction.RIGHT, board);
    GameState back = blocked.move(Direction.LEFT, board);

    // Assert
    assertEquals(new GameState(new Coordinate(1, 1), 0), start);
    assertEquals(new GameState(new Coordinate(2, 1), 1), atEdge);
    assertEquals(new GameState(new Coordinate(2, 1), 1), blocked);
    assertEquals(new GameState(new Coordinate(1, 1), 2), back);
  }
}
