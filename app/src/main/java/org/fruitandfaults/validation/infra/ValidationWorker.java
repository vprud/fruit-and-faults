package org.fruitandfaults.validation.infra;

import java.nio.file.Path;

import org.fruitandfaults.validation.application.BehaviorValidator;
import org.fruitandfaults.validation.domain.CheckOutcome;
import org.fruitandfaults.workspace.infra.SafeWorkspaceFiles;

/** Internal bounded-process entry point; learner bytecode runs only in this owned worker JVM. */
public final class ValidationWorker {
  private ValidationWorker() {}

  /**
   * Checks one known criterion and emits only a fixed result token after validation returns.
   *
   * @param arguments criterion identity; authentication input is consumed before loading game code
   */
  public static void main(String[] arguments) {
    if (arguments.length != 1) {
      return;
    }
    byte[] secret;
    try {
      secret = WorkerProtocol.consumeSecret();
    } catch (java.io.IOException invalidInput) {
      return;
    }
    String token;
    try {
      CompiledGameLoader loader = CompiledGameLoader.workerLoader(new SafeWorkspaceFiles());
      BehaviorValidator validator =
          switch (arguments[0]) {
            case "starter-public-result" -> new FirstRunValidator(loader);
            case "coordinate-direction" -> new CoordinateDirectionValidator(loader);
            case "field-valid-move" -> new FieldMovementValidator(loader);
            case "game-state" -> new GameStateValidator(loader);
            default -> throw new IllegalArgumentException("Unknown worker criterion");
          };
      CheckOutcome outcome = validator.validate(Path.of(""));
      token =
          switch (outcome) {
            case CheckOutcome.Passed ignored -> "PASSED";
            case CheckOutcome.Failed failed -> failed.category().name();
          };
    } catch (RuntimeException | Error workerFailure) {
      token = "INTERNAL_ERROR";
    }
    System.out.println(WorkerProtocol.frame(secret, token));
    java.util.Arrays.fill(secret, (byte) 0);
  }
}
