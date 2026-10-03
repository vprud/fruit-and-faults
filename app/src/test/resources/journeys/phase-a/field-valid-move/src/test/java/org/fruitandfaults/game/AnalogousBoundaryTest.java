package org.fruitandfaults.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AnalogousBoundaryTest {
  @Test
  void outwardMovementAtMyChosenEdgeIsBlocked() {
    // Arrange: x = 0 is the leftmost valid column.
    Board board = new Board(4, 3);
    Coordinate original = new Coordinate(0, 1);

    // Act: moving left would reach x = -1, outside that board.
    MoveResult blocked = Movement.move(original, Direction.LEFT, board);

    // Assert: the original position is preserved and the attempt is blocked.
    assertEquals(new Coordinate(0, 1), blocked.coordinate());
    assertEquals(MoveStatus.BLOCKED, blocked.status());
  }
}
