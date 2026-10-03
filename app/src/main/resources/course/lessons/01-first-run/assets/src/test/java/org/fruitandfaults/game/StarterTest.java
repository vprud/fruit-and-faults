package org.fruitandfaults.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StarterTest {
  @Test
  void repairedStarterReportsItsPublicMessage() {
    // Act
    String result = Starter.message();

    // Assert
    assertEquals("Ready to play.", result);
  }
}
