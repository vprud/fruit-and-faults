package org.fruitandfaults.validation.infra;

import java.nio.file.Path;
import java.util.Objects;

import org.fruitandfaults.validation.application.BehaviorValidator;
import org.fruitandfaults.validation.domain.CheckOutcome;

/** Checks four adjacent screen-coordinate moves and the unchanged original value. */
public final class CoordinateDirectionValidator implements BehaviorValidator {
  private final CompiledGameLoader loader;

  /**
   * Uses only public constructors and methods.
   *
   * @param loader safe compiled-game loader
   */
  public CoordinateDirectionValidator(CompiledGameLoader loader) {
    this.loader = Objects.requireNonNull(loader);
  }

  @Override
  public CheckOutcome validate(Path root) {
    return loader.validate(
        root,
        "coordinate-direction",
        "All four directions to return new adjacent coordinates and preserve the original.",
        game -> {
          Class<?> coordinate = game.type("Coordinate");
          Class<?> direction = game.type("Direction");
          var move = game.method(coordinate, "move", coordinate, false, direction);
          String[] names = {"UP", "RIGHT", "DOWN", "LEFT"};
          int[][] expected = {{4, 6}, {5, 7}, {4, 8}, {3, 7}};
          for (int index = 0; index < names.length; index++) {
            Object original = game.coordinate(4, 7);
            Object moved = game.call(move, original, game.direction(direction, names[index]));
            CompiledGameLoader.Game.require(moved != original);
            game.coordinateEquals(moved, expected[index][0], expected[index][1]);
            game.coordinateEquals(original, 4, 7);
          }
        });
  }
}
