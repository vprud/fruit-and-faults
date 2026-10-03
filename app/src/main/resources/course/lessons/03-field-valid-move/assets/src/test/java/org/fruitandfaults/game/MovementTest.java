package org.fruitandfaults.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MovementTest {
  @ParameterizedTest
  @CsvSource({
    "0, 0, true",
    "2, 1, true",
    "-1, 0, false",
    "3, 0, false",
    "0, -1, false",
    "0, 2, false"
  })
  void containsOnlyCoordinatesInsideTheBoard(int x, int y, boolean expected) {
    // Arrange
    Board board = new Board(3, 2);
    Coordinate coordinate = new Coordinate(x, y);

    // Act
    boolean contained = board.contains(coordinate);

    // Assert
    assertEquals(expected, contained);
  }

  @ParameterizedTest
  @CsvSource({
    "0, 0, UP, 0, 0, BLOCKED",
    "2, 1, RIGHT, 2, 1, BLOCKED",
    "2, 1, DOWN, 2, 1, BLOCKED",
    "0, 0, LEFT, 0, 0, BLOCKED",
    "1, 1, RIGHT, 2, 1, MOVED",
    "1, 0, DOWN, 1, 1, MOVED"
  })
  void returnsAnAllowedPositionOrTheOriginalAtEachEdge(
      int x, int y, Direction direction, int expectedX, int expectedY, MoveStatus expectedStatus) {
    // Arrange
    Board board = new Board(3, 2);
    Coordinate original = new Coordinate(x, y);

    // Act
    MoveResult result = Movement.move(original, direction, board);

    // Assert
    assertEquals(new Coordinate(expectedX, expectedY), result.coordinate());
    assertEquals(expectedStatus, result.status());
    assertEquals(new Coordinate(x, y), original);
  }
}
