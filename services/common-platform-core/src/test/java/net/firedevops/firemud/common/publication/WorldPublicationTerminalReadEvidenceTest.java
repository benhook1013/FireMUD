package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import org.junit.jupiter.api.Test;

class WorldPublicationTerminalReadEvidenceTest {
  @Test
  void rejectsAnOversizedOriginalOperationBeforeParsingTheTerminal() {
    assertThatThrownBy(
            () ->
                new WorldPublicationTerminalReadEvidence.Request(
                    1,
                    "test",
                    UUID.randomUUID(),
                    new byte[GameDesignPublicationOperationBinding.MAX_OPERATION_BYTES + 1],
                    new byte[] {1}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Complete bounded original publication operation required")
        .hasNoCause();
  }

  @Test
  void boundsCombinedWirePayloadBeforeCloningOrParsingTerminal() {
    int operationBytes = GameDesignPublicationOperationBinding.MAX_OPERATION_BYTES;
    int terminalAtBoundary =
        WorldPublicationTerminalCompletionGrpcCodec.MAX_MESSAGE_BYTES
            - WorldPublicationTerminalReadGrpcCodec.MESSAGE_ENVELOPE_OVERHEAD_RESERVE
            - operationBytes;
    byte[] operation = new byte[operationBytes];

    assertThatThrownBy(
            () ->
                new WorldPublicationTerminalReadEvidence.Request(
                    1, "test", UUID.randomUUID(), operation, new byte[terminalAtBoundary]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Canonical complete publication operation and terminal evidence required")
        .hasCauseInstanceOf(RuntimeException.class);

    assertThatThrownBy(
            () ->
                new WorldPublicationTerminalReadEvidence.Request(
                    1, "test", UUID.randomUUID(), operation, new byte[terminalAtBoundary + 1]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("World publication terminal read exceeds the 32 MiB wire budget")
        .hasNoCause();
  }

  @Test
  void wrapsMalformedOrNullWorldTerminalReadbackAsCanonicalInvalidInput() throws Exception {
    var operation = WorldPublicationTerminalTestFixtures.operation();
    var terminal = WorldPublicationTerminalTestFixtures.terminal(operation, Outcome.PUBLISHED);
    var request =
        WorldPublicationTerminalReadEvidence.Request.create(
            "test", operation.canonicalBytes(), terminal.canonicalBytes());

    for (byte[] malformed : new byte[][] {new byte[] {1}, null}) {
      assertThatThrownBy(
              () ->
                  WorldPublicationTerminalReadEvidence.fromOwnerRead(
                      request,
                      WorldPublicationTerminalReadEvidence.WorldOutcome.PUBLISHED,
                      malformed))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Canonical complete publication operation and terminal evidence required")
          .hasCauseInstanceOf(RuntimeException.class);
    }
  }

  @Test
  void retainsExactRequestAndWorldOutcomeForPublishedAndAborted() throws Exception {
    var operation = WorldPublicationTerminalTestFixtures.operation();
    for (Outcome producerOutcome : Outcome.values()) {
      var terminal = WorldPublicationTerminalTestFixtures.terminal(operation, producerOutcome);
      byte[] operationBytes = operation.canonicalBytes();
      byte[] terminalBytes = terminal.canonicalBytes();
      var request =
          WorldPublicationTerminalReadEvidence.Request.create(
              "test", operationBytes, terminalBytes);
      operationBytes[0] = 0;
      terminalBytes[0] = 0;

      var worldOutcome =
          producerOutcome == Outcome.PUBLISHED
              ? WorldPublicationTerminalReadEvidence.WorldOutcome.PUBLISHED
              : WorldPublicationTerminalReadEvidence.WorldOutcome.ABORTED;
      var evidence =
          WorldPublicationTerminalReadEvidence.fromOwnerRead(
              request, worldOutcome, terminal.canonicalBytes());
      assertThat(evidence.request()).isEqualTo(request);
      assertThat(evidence.worldOutcome()).isEqualTo(worldOutcome);
      assertThat(evidence.terminalEvidence().outcome()).isEqualTo(producerOutcome);
      assertThat(evidence.worldTerminalEvidence()).containsExactly(terminal.canonicalBytes());
      assertThat(request.originalOperation()).containsExactly(operation.canonicalBytes());
      assertThat(request.expectedGameDesignTerminalEvidence())
          .containsExactly(terminal.canonicalBytes());
      assertThat(request.readRequestId()).isNotEqualTo(new UUID(0L, 0L));
    }
  }

  @Test
  void rejectsInvalidRequestBindingsAndAWorldOutcomeThatDoesNotMatchTheStoredPhase()
      throws Exception {
    var operation = WorldPublicationTerminalTestFixtures.operation();
    var terminal = WorldPublicationTerminalTestFixtures.terminal(operation, Outcome.NO_PUBLICATION);
    var request =
        WorldPublicationTerminalReadEvidence.Request.create(
            "test", operation.canonicalBytes(), terminal.canonicalBytes());

    assertThatThrownBy(
            () ->
                new WorldPublicationTerminalReadEvidence.Request(
                    2,
                    "test",
                    UUID.randomUUID(),
                    operation.canonicalBytes(),
                    terminal.canonicalBytes()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new WorldPublicationTerminalReadEvidence.Request(
                    1,
                    "Test",
                    UUID.randomUUID(),
                    operation.canonicalBytes(),
                    terminal.canonicalBytes()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new WorldPublicationTerminalReadEvidence.Request(
                    1,
                    "test",
                    new UUID(0L, 0L),
                    operation.canonicalBytes(),
                    terminal.canonicalBytes()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new WorldPublicationTerminalReadEvidence.Request(
                    1, "test", UUID.randomUUID(), new byte[] {1}, terminal.canonicalBytes()))
        .isInstanceOf(IllegalArgumentException.class);

    var changedOperation = WorldPublicationTerminalTestFixtures.operation();
    var changedTerminal =
        WorldPublicationTerminalTestFixtures.terminal(changedOperation, Outcome.NO_PUBLICATION);
    assertThatThrownBy(
            () ->
                new WorldPublicationTerminalReadEvidence.Request(
                    1,
                    "test",
                    UUID.randomUUID(),
                    operation.canonicalBytes(),
                    changedTerminal.canonicalBytes()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                WorldPublicationTerminalReadEvidence.fromOwnerRead(
                    request,
                    WorldPublicationTerminalReadEvidence.WorldOutcome.PUBLISHED,
                    terminal.canonicalBytes()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
