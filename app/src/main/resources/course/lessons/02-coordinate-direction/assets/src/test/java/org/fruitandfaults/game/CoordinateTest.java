package org.fruitandfaults.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CoordinateTest {
  @ParameterizedTest
  @CsvSource({"UP, 2, 2", "RIGHT, 3, 3", "DOWN, 2, 4", "LEFT, 1, 3"})
  void movesOneStepAndPreservesTheOriginal(Direction direction, int expectedX, int expectedY) {
    // Arrange
    Coordinate original = new Coordinate(2, 3);

    // Act
    Coordinate moved = original.move(direction);

    // Assert
    assertEquals(new Coordinate(expectedX, expectedY), moved);
    assertEquals(new Coordinate(2, 3), original);
    assertNotSame(original, moved);
  }
}
