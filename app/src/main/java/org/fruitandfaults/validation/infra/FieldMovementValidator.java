package org.fruitandfaults.validation.infra;

import java.nio.file.Path;
import java.util.Objects;

import org.fruitandfaults.validation.application.BehaviorValidator;
import org.fruitandfaults.validation.domain.CheckOutcome;

/** Checks inclusive lower edges, exclusive upper edges, and public movement outcomes. */
public final class FieldMovementValidator implements BehaviorValidator {
  private final CompiledGameLoader loader;

  /**
   * Uses public board and movement contracts without implementation inspection.
   *
   * @param loader safe compiled-game loader
   */
  public FieldMovementValidator(CompiledGameLoader loader) {
    this.loader = Objects.requireNonNull(loader);
  }

  @Override
  public CheckOutcome validate(Path root) {
    return loader.validate(
        root,
        "field-valid-move",
        "In-bounds moves to return MOVED and edge-crossing moves to return BLOCKED at the original coordinate.",
        game -> {
          Class<?> coordinate = game.type("Coordinate");
          Class<?> direction = game.type("Direction");
          Class<?> boardType = game.type("Board");
          Class<?> resultType = game.type("MoveResult");
          Class<?> statusType = game.type("MoveStatus");
          CompiledGameLoader.Game.require(statusType.isEnum());
          Object board = game.construct(boardType, new Class<?>[] {int.class, int.class}, 3, 2);
          var contains = game.method(boardType, "contains", boolean.class, false, coordinate);
          int[][] points = {{0, 0, 1}, {2, 1, 1}, {-1, 0, 0}, {3, 0, 0}, {0, -1, 0}, {0, 2, 0}};
          for (int[] point : points) {
            CompiledGameLoader.Game.require(
                game.call(contains, board, game.coordinate(point[0], point[1]))
                    .equals(point[2] == 1));
          }
          var move =
              game.method(
                  game.type("Movement"),
                  "move",
                  resultType,
                  true,
                  coordinate,
                  direction,
                  boardType);
          var position = game.method(resultType, "coordinate", coordinate, false);
          var status = game.method(resultType, "status", statusType, false);
          String[] names = {"UP", "RIGHT", "DOWN", "LEFT", "UP", "RIGHT", "DOWN", "LEFT"};
          int[][] cases = {
            {0, 0, 0, 0},
            {2, 1, 2, 1},
            {2, 1, 2, 1},
            {0, 0, 0, 0},
            {1, 1, 1, 0},
            {1, 0, 2, 0},
            {1, 0, 1, 1},
            {1, 0, 0, 0}
          };
          for (int index = 0; index < cases.length; index++) {
            int[] point = cases[index];
            Object original = game.coordinate(point[0], point[1]);
            Object result =
                game.call(move, null, original, game.direction(direction, names[index]), board);
            game.coordinateEquals(game.call(position, result), point[2], point[3]);
            CompiledGameLoader.Game.require(
                ((Enum<?>) game.call(status, result))
                    .name()
                    .equals(index < 4 ? "BLOCKED" : "MOVED"));
            game.coordinateEquals(original, point[0], point[1]);
          }
        });
  }
}
