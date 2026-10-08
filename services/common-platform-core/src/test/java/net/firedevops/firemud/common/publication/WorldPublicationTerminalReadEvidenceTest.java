package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import org.junit.jupiter.api.Test;

class WorldPublicationTerminalReadEvidenceTest {
  @Test
  void retainsExactRequestAndWorldOutcomeForPublishedAndAborted() throws Exception {
    var operation = GameDesignPublicationTerminalReadGrpcCodecTest.operation();
    for (Outcome producerOutcome : Outcome.values()) {
      var terminal =
          GameDesignPublicationTerminalReadGrpcCodecTest.terminal(operation, producerOutcome);
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
    var operation = GameDesignPublicationTerminalReadGrpcCodecTest.operation();
    var terminal =
        GameDesignPublicationTerminalReadGrpcCodecTest.terminal(operation, Outcome.NO_PUBLICATION);
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

    var changedOperation = GameDesignPublicationTerminalReadGrpcCodecTest.operation();
    var changedTerminal =
        GameDesignPublicationTerminalReadGrpcCodecTest.terminal(
            changedOperation, Outcome.NO_PUBLICATION);
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
