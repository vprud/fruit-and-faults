package org.fruitandfaults.validation.infra;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.fruitandfaults.validation.application.ProcessRequest;
import org.fruitandfaults.validation.application.ProcessResult;
import org.fruitandfaults.validation.application.ProcessRunner;
import org.fruitandfaults.validation.domain.CheckOutcome;
import org.fruitandfaults.validation.domain.Diagnostic;

/** One owned worker invocation with a private one-time completion authentication key. */
final class WorkerProcess {
  private WorkerProcess() {}

  static Reply run(ProcessRunner runner, Path root, Class<?> worker, List<String> arguments) {
    byte[] secret = new byte[32];
    new SecureRandom().nextBytes(secret);
    try {
      Path executable =
          Path.of(
              System.getProperty("java.home"),
              "bin",
              System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
      Path code =
          Path.of(
                  Objects.requireNonNull(worker.getProtectionDomain().getCodeSource())
                      .getLocation()
                      .toURI())
              .toAbsolutePath()
              .normalize();
      var command =
          new ArrayList<>(
              List.of(
                  executable.toString(),
                  "-Xmx128m",
                  "-XX:MaxMetaspaceSize=64m",
                  "-cp",
                  code.toString(),
                  worker.getName()));
      command.addAll(arguments);
      ProcessResult result =
          runner.run(new ProcessRequest(command, root, Duration.ofSeconds(10), 16_384, secret));
      var payload =
          result instanceof ProcessResult.Exited exited
              ? WorkerProtocol.verified(
                  new WorkerProtocol.ProcessResultView(exited.exitCode(), exited.output().stdout()),
                  secret)
              : java.util.Optional.<String>empty();
      return new Reply(result, payload);
    } catch (URISyntaxException | RuntimeException failure) {
      return new Reply(
          new ProcessResult.Failed(
              ProcessResult.FailureReason.UNAVAILABLE,
              "The isolated worker could not be launched.",
              ProcessResult.Output.empty()),
          java.util.Optional.empty());
    } finally {
      java.util.Arrays.fill(secret, (byte) 0);
    }
  }

  record Reply(ProcessResult result, java.util.Optional<String> payload) {}

  static CheckOutcome.Failed withCleanup(
      CheckOutcome.Failed outcome, ProcessResult.Cleanup cleanup) {
    if (cleanup == ProcessResult.Cleanup.COMPLETE) {
      return outcome;
    }
    var diagnostics = new ArrayList<>(outcome.diagnostics());
    diagnostics.add(
        new Diagnostic(
            "All owned worker processes and I/O tasks to terminate.",
            "Worker cleanup remained incomplete or could not be verified.",
            "Inspect remaining local processes before retrying check."));
    return new CheckOutcome.Failed(outcome.category(), diagnostics);
  }
}
