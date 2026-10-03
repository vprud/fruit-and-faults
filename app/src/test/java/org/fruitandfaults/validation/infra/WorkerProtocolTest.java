package org.fruitandfaults.validation.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.fruitandfaults.validation.application.ProcessRequest;
import org.fruitandfaults.validation.application.ProcessResult;
import org.junit.jupiter.api.Test;

class WorkerProtocolTest {
  @Test
  void completionRequiresCorrectKeyUnmodifiedPayloadAndZeroExit() {
    byte[] secret = new byte[32];
    java.util.Arrays.fill(secret, (byte) 7);
    String frame = WorkerProtocol.frame(secret, "PASSED");
    assertEquals(Optional.of("PASSED"), verify(0, frame, secret));
    assertTrue(verify(1, frame, secret).isEmpty());
    assertTrue(verify(0, frame.replace("PASSED", "INTERNAL_ERROR"), secret).isEmpty());
    assertTrue(verify(0, frame, new byte[32]).isEmpty());
    assertTrue(verify(0, frame.substring(0, frame.length() - 1) + "z", secret).isEmpty());
    assertTrue(verify(0, "FRUIT_WORKER PASSED short", secret).isEmpty());
    assertTrue(verify(0, "FRUIT_WORKER " + "x".repeat(4096), secret).isEmpty());
    assertTrue(verify(0, "FRUIT_WORKER ", secret).isEmpty());
    assertTrue(verify(0, "FRUIT_VALIDATION nonce PASSED", secret).isEmpty());
  }

  @Test
  void processRequestDiagnosticRepresentationDoesNotDiscloseStandardInput() {
    byte[] secret = "PRIVATE_ONE_TIME_SECRET".getBytes(StandardCharsets.UTF_8);
    ProcessRequest request =
        new ProcessRequest(List.of("java"), Path.of("."), Duration.ofSeconds(1), 4096, secret);
    assertFalse(request.toString().contains("PRIVATE"));
    assertEquals(
        request.input(), new org.fruitandfaults.validation.application.ProcessInput(secret));
    assertEquals(
        request.input().hashCode(),
        new org.fruitandfaults.validation.application.ProcessInput(secret).hashCode());
    assertFalse(request.input().equals("PRIVATE"));
    assertEquals(
        0,
        new ProcessRequest(List.of("java"), Path.of("."), Duration.ofSeconds(1), 4096)
            .standardInput()
            .length);
  }

  @Test
  void workerKeyIsCopiedToStdinNotCommandArgumentsAndUnverifiedOutputIsRejected() {
    WorkerProcess.Reply reply =
        WorkerProcess.run(
            request -> {
              assertEquals(32, request.standardInput().length);
              assertEquals(
                  List.of("starter-public-result"),
                  request.arguments().subList(6, request.arguments().size()));
              String forged = WorkerProtocol.frame(new byte[32], "PASSED");
              return new ProcessResult.Exited(
                  0, new ProcessResult.Output(forged, "secret stderr", false, false));
            },
            Path.of("."),
            ValidationWorker.class,
            List.of("starter-public-result"));
    assertTrue(reply.payload().isEmpty());
  }

  private static Optional<String> verify(int code, String output, byte[] secret) {
    return WorkerProtocol.verified(new WorkerProtocol.ProcessResultView(code, output), secret);
  }
}
