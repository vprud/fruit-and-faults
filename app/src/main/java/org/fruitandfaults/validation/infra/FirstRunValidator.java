package org.fruitandfaults.validation.infra;

import java.nio.file.Path;
import java.util.Objects;

import org.fruitandfaults.validation.application.BehaviorValidator;
import org.fruitandfaults.validation.domain.CheckOutcome;

/** Validates the first lesson's public starter result. */
public final class FirstRunValidator implements BehaviorValidator {
  private final CompiledGameLoader loader;

  /**
   * Uses fresh isolated main classes for every check.
   *
   * @param loader safe compiled-game loader
   */
  public FirstRunValidator(CompiledGameLoader loader) {
    this.loader = Objects.requireNonNull(loader);
  }

  @Override
  public CheckOutcome validate(Path root) {
    return loader.validate(
        root,
        "starter-public-result",
        "Starter.message() to return Ready to play.",
        game -> {
          var starter = game.type("Starter");
          var message = game.method(starter, "message", String.class, true);
          CompiledGameLoader.Game.require(game.call(message, null).equals("Ready to play."));
        });
  }
}
