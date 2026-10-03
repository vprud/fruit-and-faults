package org.fruitandfaults.validation.infra;

import java.nio.file.Path;
import java.util.Objects;

import org.fruitandfaults.validation.application.BehaviorValidator;
import org.fruitandfaults.validation.domain.CheckOutcome;

/** Validates public game-state transitions without private state or framework access. */
public final class GameStateValidator implements BehaviorValidator {
  private final CompiledGameLoader loader;

  /**
   * Uses public state values from fresh isolated main classes.
   *
   * @param loader safe compiled-game loader
   */
  public GameStateValidator(CompiledGameLoader loader) {
    this.loader = Objects.requireNonNull(loader);
  }

  @Override
  public CheckOutcome validate(Path root) {
    return loader.validate(
        root,
        "game-state",
        "Successful moves to increment successfulMoves while blocked moves preserve player and count.",
        game -> {
          Class<?> state = game.type("GameState");
          Class<?> coordinate = game.type("Coordinate");
          Class<?> direction = game.type("Direction");
          Class<?> boardType = game.type("Board");
          Object board = game.construct(boardType, new Class<?>[] {int.class, int.class}, 3, 2);
          var move = game.method(state, "move", state, false, direction, boardType);
          var player = game.method(state, "player", coordinate, false);
          var count = game.method(state, "successfulMoves", int.class, false);
          String[] names = {"UP", "RIGHT", "DOWN", "LEFT", "UP", "RIGHT", "DOWN", "LEFT"};
          int[][] cases = {
            {1, 1, 1, 0, 8},
            {1, 1, 2, 1, 8},
            {1, 0, 1, 1, 8},
            {1, 1, 0, 1, 8},
            {0, 0, 0, 0, 7},
            {2, 1, 2, 1, 7},
            {2, 1, 2, 1, 7},
            {0, 0, 0, 0, 7}
          };
          for (int index = 0; index < cases.length; index++) {
            int[] point = cases[index];
            Object original =
                game.construct(
                    state,
                    new Class<?>[] {coordinate, int.class},
                    game.coordinate(point[0], point[1]),
                    7);
            Object changed =
                game.call(move, original, game.direction(direction, names[index]), board);
            game.coordinateEquals(game.call(player, changed), point[2], point[3]);
            CompiledGameLoader.Game.require(game.call(count, changed).equals(point[4]));
            game.coordinateEquals(game.call(player, original), point[0], point[1]);
            CompiledGameLoader.Game.require(game.call(count, original).equals(7));
          }
        });
  }
}
