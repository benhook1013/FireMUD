package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import net.firedevops.firemud.worldmanagement.v1.CompleteVersionPublicationRequest;
import org.junit.jupiter.api.Test;

class WorldPublicationTerminalCompletionGrpcCodecTest {
  @Test
  void roundTripsBothTerminalOutcomesAndUsesIndependentTransportCorrelation() throws Exception {
    var operation = GameDesignPublicationTerminalReadGrpcCodecTest.operation();
    for (Outcome outcome : Outcome.values()) {
      var terminal = GameDesignPublicationTerminalReadGrpcCodecTest.terminal(operation, outcome);
      var request =
          WorldPublicationTerminalCompletionGrpcCodec.Request.create(
              "test", operation.canonicalBytes(), terminal.canonicalBytes());
      var decoded =
          WorldPublicationTerminalCompletionGrpcCodec.fromRequest(
              WorldPublicationTerminalCompletionGrpcCodec.toRequest(request));
      assertThat(decoded).isEqualTo(request);

      var response = WorldPublicationTerminalCompletionGrpcCodec.toResponse(request, terminal);
      var first = WorldPublicationTerminalCompletionGrpcCodec.fromResponse(request, response);
      var retry = WorldPublicationTerminalCompletionGrpcCodec.fromResponse(request, response);
      assertThat(first.canonicalBytes()).containsExactly(terminal.canonicalBytes());
      assertThat(retry.outcome()).isEqualTo(outcome);
      assertThat(response.getRequestId()).isEqualTo(request.requestId().toString());

      var fresh =
          WorldPublicationTerminalCompletionGrpcCodec.Request.create(
              "test", operation.canonicalBytes(), terminal.canonicalBytes());
      assertThat(fresh.requestId()).isNotEqualTo(request.requestId());
      assertThat(fresh.originalOperation()).containsExactly(request.originalOperation());
    }
  }

  @Test
  void rejectsMalformedAndNoncanonicalRequestsAndUnknownFields() throws Exception {
    var operation = GameDesignPublicationTerminalReadGrpcCodecTest.operation();
    var terminal =
        GameDesignPublicationTerminalReadGrpcCodecTest.terminal(operation, Outcome.NO_PUBLICATION);
    var request =
        WorldPublicationTerminalCompletionGrpcCodec.Request.create(
            "test", operation.canonicalBytes(), terminal.canonicalBytes());
    var wire = WorldPublicationTerminalCompletionGrpcCodec.toRequest(request);
    for (CompleteVersionPublicationRequest invalid :
        List.of(
            wire.toBuilder().setUnknownFields(unknown()).build(),
            wire.toBuilder().setSchemaVersion(2).build(),
            wire.toBuilder().setTargetNamespace("Test").build(),
            wire.toBuilder().setRequestId("00000000-0000-0000-0000-000000000000").build(),
            wire.toBuilder().setRequestId("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA").build(),
            wire.toBuilder().setRequestId("1-1-1-1-1").build(),
            wire.toBuilder().clearOriginalOperation().build(),
            wire.toBuilder()
                .setOriginalOperation(
                    wire.getOriginalOperation().concat(ByteString.copyFrom(new byte[] {0})))
                .build(),
            wire.toBuilder()
                .setGameDesignTerminalEvidence(
                    wire.getGameDesignTerminalEvidence()
                        .concat(ByteString.copyFrom(new byte[] {0})))
                .build(),
            wire.toBuilder()
                .setGameDesignTerminalEvidence(ByteString.copyFrom(new byte[] {1}))
                .build())) {
      assertThatThrownBy(() -> WorldPublicationTerminalCompletionGrpcCodec.fromRequest(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }

    assertThatThrownBy(
            () ->
                new WorldPublicationTerminalCompletionGrpcCodec.Request(
                    2,
                    "test",
                    UUID.randomUUID(),
                    operation.canonicalBytes(),
                    terminal.canonicalBytes()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                WorldPublicationTerminalCompletionGrpcCodec.Request.create(
                    "test", operation.canonicalBytes(), new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsChangedEchoStoredTerminalAndOverBudgetCompleteMessages() throws Exception {
    var operation = GameDesignPublicationTerminalReadGrpcCodecTest.operation();
    var changedOperation = GameDesignPublicationTerminalReadGrpcCodecTest.operation();
    var terminal =
        GameDesignPublicationTerminalReadGrpcCodecTest.terminal(operation, Outcome.NO_PUBLICATION);
    var changedTerminal =
        GameDesignPublicationTerminalReadGrpcCodecTest.terminal(
            changedOperation, Outcome.PUBLISHED);
    var request =
        WorldPublicationTerminalCompletionGrpcCodec.Request.create(
            "test", operation.canonicalBytes(), terminal.canonicalBytes());
    var response = WorldPublicationTerminalCompletionGrpcCodec.toResponse(request, terminal);
    for (var invalid :
        List.of(
            response.toBuilder().setUnknownFields(unknown()).build(),
            response.toBuilder().setSchemaVersion(2).build(),
            response.toBuilder().setTargetNamespace("other").build(),
            response.toBuilder().setRequestId(UUID.randomUUID().toString()).build(),
            response.toBuilder()
                .setOriginalOperation(ByteString.copyFrom(changedOperation.canonicalBytes()))
                .build(),
            response.toBuilder()
                .setGameDesignTerminalEvidence(
                    ByteString.copyFrom(changedTerminal.canonicalBytes()))
                .build(),
            response.toBuilder().clearStoredTerminalEvidence().build(),
            response.toBuilder()
                .setStoredTerminalEvidence(ByteString.copyFrom(changedTerminal.canonicalBytes()))
                .build(),
            response.toBuilder()
                .setStoredTerminalEvidence(
                    response
                        .getStoredTerminalEvidence()
                        .concat(ByteString.copyFrom(new byte[] {0})))
                .build())) {
      assertThatThrownBy(
              () -> WorldPublicationTerminalCompletionGrpcCodec.fromResponse(request, invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () -> WorldPublicationTerminalCompletionGrpcCodec.toResponse(request, changedTerminal))
        .isInstanceOf(IllegalArgumentException.class);

    var oversizedRequest =
        CompleteVersionPublicationRequest.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setRequestId(request.requestId().toString())
            .setOriginalOperation(ByteString.copyFrom(request.originalOperation()))
            .setGameDesignTerminalEvidence(
                ByteString.copyFrom(
                    new byte[WorldPublicationTerminalCompletionGrpcCodec.MAX_MESSAGE_BYTES]))
            .build();
    assertThatThrownBy(
            () -> WorldPublicationTerminalCompletionGrpcCodec.fromRequest(oversizedRequest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32 MiB");

    var oversizedResponse =
        response.toBuilder()
            .setStoredTerminalEvidence(
                ByteString.copyFrom(
                    new byte[WorldPublicationTerminalCompletionGrpcCodec.MAX_MESSAGE_BYTES]))
            .build();
    assertThatThrownBy(
            () ->
                WorldPublicationTerminalCompletionGrpcCodec.fromResponse(
                    request, oversizedResponse))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32 MiB");
  }

  @Test
  void requestMayFitWhenItsRepeatedTerminalResponseDoesNot() throws Exception {
    var operation = GameDesignPublicationTerminalReadGrpcCodecTest.operation();
    var base =
        GameDesignPublicationTerminalReadGrpcCodecTest.terminal(operation, Outcome.PUBLISHED);
    var release = base.releaseContent();
    var largeRelease =
        new GameDesignPublicationTerminalEvidence.ReleaseContent(
            release.canonicalTenantId(),
            release.canonicalVersionId(),
            release.publishedReleaseBundleRef(),
            release.versionNumber(),
            release.attestationSchemaVersion(),
            release.publishWorkflowId(),
            release.manifestHash(),
            release.manifestSchemaVersion(),
            release.artifactDigests(),
            release.requiredManifestAssetKeys(),
            release.participantDigests(),
            List.of("x".repeat(17 * 1024 * 1024)),
            release.generationConfigRevision(),
            release.worldStartLocationEvidence());
    var largeTerminal =
        new GameDesignPublicationTerminalEvidence(
            operation.canonicalBytes(),
            Outcome.PUBLISHED,
            largeRelease,
            base.publicationVersionStateEpoch());
    var request =
        WorldPublicationTerminalCompletionGrpcCodec.Request.create(
            "test", operation.canonicalBytes(), largeTerminal.canonicalBytes());

    assertThat(WorldPublicationTerminalCompletionGrpcCodec.toRequest(request).getSerializedSize())
        .isLessThan(WorldPublicationTerminalCompletionGrpcCodec.MAX_MESSAGE_BYTES);
    assertThatThrownBy(
            () -> WorldPublicationTerminalCompletionGrpcCodec.toResponse(request, largeTerminal))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("response exceeds");
  }

  private static UnknownFieldSet unknown() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
        .build();
  }
}
