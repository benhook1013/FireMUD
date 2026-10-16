package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import net.firedevops.firemud.common.publication.WorldPublicationTerminalReadEvidence.WorldOutcome;
import net.firedevops.firemud.worldmanagement.v1.ReadPublicationTerminalRequest;
import net.firedevops.firemud.worldmanagement.v1.WorldPublicationTerminalReadOutcome;
import org.junit.jupiter.api.Test;

class WorldPublicationTerminalReadGrpcCodecTest {
  @Test
  void roundTripsExactRequestAndWorldOwnedOutcomeForBothTerminalPhases() throws Exception {
    var operation = WorldPublicationTerminalTestFixtures.operation();
    for (Outcome producerOutcome : Outcome.values()) {
      var terminal = WorldPublicationTerminalTestFixtures.terminal(operation, producerOutcome);
      var request =
          WorldPublicationTerminalReadEvidence.Request.create(
              "test", operation.canonicalBytes(), terminal.canonicalBytes());
      assertThat(
              WorldPublicationTerminalReadGrpcCodec.fromRequest(
                  WorldPublicationTerminalReadGrpcCodec.toRequest(request)))
          .isEqualTo(request);

      var response = WorldPublicationTerminalReadGrpcCodec.toResponse(request, terminal);
      var first = WorldPublicationTerminalReadGrpcCodec.fromResponse(request, response);
      var retry = WorldPublicationTerminalReadGrpcCodec.fromResponse(request, response);
      WorldOutcome expectedWorldOutcome =
          producerOutcome == Outcome.PUBLISHED ? WorldOutcome.PUBLISHED : WorldOutcome.ABORTED;
      assertThat(first.request()).isEqualTo(request);
      assertThat(first.worldOutcome()).isEqualTo(expectedWorldOutcome);
      assertThat(first.terminalEvidence().outcome()).isEqualTo(producerOutcome);
      assertThat(retry.worldTerminalEvidence()).containsExactly(terminal.canonicalBytes());
      assertThat(response.getWorldOutcome())
          .isEqualTo(
              producerOutcome == Outcome.PUBLISHED
                  ? WorldPublicationTerminalReadOutcome
                      .WORLD_PUBLICATION_TERMINAL_READ_OUTCOME_PUBLISHED
                  : WorldPublicationTerminalReadOutcome
                      .WORLD_PUBLICATION_TERMINAL_READ_OUTCOME_ABORTED);
    }
  }

  @Test
  void rejectsUnknownOrNoncanonicalRequestAndEveryChangedEchoOrOutcome() throws Exception {
    var operation = WorldPublicationTerminalTestFixtures.operation();
    var terminal = WorldPublicationTerminalTestFixtures.terminal(operation, Outcome.NO_PUBLICATION);
    var request =
        WorldPublicationTerminalReadEvidence.Request.create(
            "test", operation.canonicalBytes(), terminal.canonicalBytes());
    var wireRequest = WorldPublicationTerminalReadGrpcCodec.toRequest(request);
    for (ReadPublicationTerminalRequest invalid :
        List.of(
            wireRequest.toBuilder().setUnknownFields(unknown()).build(),
            wireRequest.toBuilder().setSchemaVersion(2).build(),
            wireRequest.toBuilder().setTargetNamespace("Test").build(),
            wireRequest.toBuilder()
                .setReadRequestId("00000000-0000-0000-0000-000000000000")
                .build(),
            wireRequest.toBuilder()
                .setReadRequestId("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA")
                .build(),
            wireRequest.toBuilder().setReadRequestId("1-1-1-1-1").build(),
            wireRequest.toBuilder().setOriginalOperation(ByteString.EMPTY).build(),
            wireRequest.toBuilder()
                .setExpectedGameDesignTerminalEvidence(ByteString.copyFrom(new byte[] {1}))
                .build())) {
      assertThatThrownBy(() -> WorldPublicationTerminalReadGrpcCodec.fromRequest(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }

    var response = WorldPublicationTerminalReadGrpcCodec.toResponse(request, terminal);
    var changedOperation = WorldPublicationTerminalTestFixtures.operation();
    var changedTerminal =
        WorldPublicationTerminalTestFixtures.terminal(changedOperation, Outcome.NO_PUBLICATION);
    for (var invalid :
        List.of(
            response.toBuilder().setUnknownFields(unknown()).build(),
            response.toBuilder().setSchemaVersion(2).build(),
            response.toBuilder().setTargetNamespace("other").build(),
            response.toBuilder().setReadRequestId(UUID.randomUUID().toString()).build(),
            response.toBuilder()
                .setOriginalOperation(ByteString.copyFrom(changedOperation.canonicalBytes()))
                .build(),
            response.toBuilder()
                .setExpectedGameDesignTerminalEvidence(
                    ByteString.copyFrom(changedTerminal.canonicalBytes()))
                .build(),
            response.toBuilder()
                .setWorldOutcome(
                    WorldPublicationTerminalReadOutcome
                        .WORLD_PUBLICATION_TERMINAL_READ_OUTCOME_PUBLISHED)
                .build(),
            response.toBuilder()
                .setWorldOutcome(
                    WorldPublicationTerminalReadOutcome
                        .WORLD_PUBLICATION_TERMINAL_READ_OUTCOME_UNSPECIFIED)
                .build(),
            response.toBuilder().clearWorldTerminalEvidence().build(),
            response.toBuilder()
                .setWorldTerminalEvidence(ByteString.copyFrom(changedTerminal.canonicalBytes()))
                .build())) {
      assertThatThrownBy(() -> WorldPublicationTerminalReadGrpcCodec.fromResponse(request, invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () -> WorldPublicationTerminalReadGrpcCodec.toResponse(request, changedTerminal))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsOversizedWireMessagesBeforeDecodingTheirByteFields() throws Exception {
    int oversized = WorldPublicationTerminalCompletionGrpcCodec.MAX_MESSAGE_BYTES;
    var oversizedRequest =
        ReadPublicationTerminalRequest.newBuilder()
            .setOriginalOperation(ByteString.copyFrom(new byte[oversized]))
            .build();
    assertThatThrownBy(() -> WorldPublicationTerminalReadGrpcCodec.fromRequest(oversizedRequest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("World publication terminal read exceeds the 32 MiB wire budget");

    var oversizedResponse =
        net.firedevops.firemud.worldmanagement.v1.ReadPublicationTerminalResponse.newBuilder()
            .setWorldTerminalEvidence(ByteString.copyFrom(new byte[oversized]))
            .build();
    var operation = WorldPublicationTerminalTestFixtures.operation();
    var terminal = WorldPublicationTerminalTestFixtures.terminal(operation, Outcome.NO_PUBLICATION);
    var request =
        WorldPublicationTerminalReadEvidence.Request.create(
            "test", operation.canonicalBytes(), terminal.canonicalBytes());
    assertThatThrownBy(
            () -> WorldPublicationTerminalReadGrpcCodec.fromResponse(request, oversizedResponse))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("World publication terminal read exceeds the 32 MiB wire budget");
  }

  private static UnknownFieldSet unknown() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
        .build();
  }
}
