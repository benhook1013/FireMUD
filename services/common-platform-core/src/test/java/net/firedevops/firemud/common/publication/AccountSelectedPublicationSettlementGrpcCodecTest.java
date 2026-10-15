package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.account.v1.SettleSelectedPublicationRequest;
import net.firedevops.firemud.account.v1.SettleSelectedPublicationResponse;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import org.junit.jupiter.api.Test;

class AccountSelectedPublicationSettlementGrpcCodecTest {
  @Test
  void roundTripsBothCanonicalSixFrameReceiptsAndUsesIndependentCorrelation() throws Exception {
    var operation = WorldPublicationTerminalTestFixtures.operation();
    for (Outcome outcome : Outcome.values()) {
      var terminal = WorldPublicationTerminalTestFixtures.terminal(operation, outcome);
      var request =
          AccountSelectedPublicationSettlementGrpcCodec.Request.create(
              "test", operation.canonicalBytes());
      var decoded =
          AccountSelectedPublicationSettlementGrpcCodec.fromRequest(
              AccountSelectedPublicationSettlementGrpcCodec.toRequest(request));
      assertThat(decoded).isEqualTo(request);

      byte[] receipt = receipt(operation, terminal, null, null, null, null);
      var response = AccountSelectedPublicationSettlementGrpcCodec.toResponse(request, receipt);
      assertThat(AccountSelectedPublicationSettlementGrpcCodec.fromResponse(request, response))
          .containsExactly(receipt);

      var fresh =
          AccountSelectedPublicationSettlementGrpcCodec.Request.create(
              "test", operation.canonicalBytes());
      assertThat(fresh.requestId()).isNotEqualTo(request.requestId());
      assertThat(fresh.originalOperation()).containsExactly(request.originalOperation());
    }
  }

  @Test
  void retainsV1HistoryButRejectsItFromCurrentSettlementRequests() throws Exception {
    var operation = WorldPublicationTerminalTestFixtures.operation();
    var history = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(history, "game-design-publication-operation/v1");
    DraftAuthorizationFenceBinding.frame(history, operation.account().canonicalBytes());
    DraftAuthorizationFenceBinding.frame(history, operation.world().canonicalBytes());
    byte[] original = history.toByteArray();
    assertThat(GameDesignPublicationOperationBinding.fromStored(original).canonicalBytes())
        .containsExactly(original);

    assertThatThrownBy(
            () -> AccountSelectedPublicationSettlementGrpcCodec.Request.create("test", original))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Complete canonical original publication operation required");
    var current =
        AccountSelectedPublicationSettlementGrpcCodec.Request.create(
            "test", operation.canonicalBytes());
    var changed =
        AccountSelectedPublicationSettlementGrpcCodec.toRequest(current).toBuilder()
            .setOriginalOperation(ByteString.copyFrom(original))
            .build();
    assertThatThrownBy(() -> AccountSelectedPublicationSettlementGrpcCodec.fromRequest(changed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Account publication settlement request is invalid");
  }

  @Test
  void rejectsMalformedAndNoncanonicalRequestsAndUnknownFields() throws Exception {
    var operation = WorldPublicationTerminalTestFixtures.operation();
    var request =
        AccountSelectedPublicationSettlementGrpcCodec.Request.create(
            "test", operation.canonicalBytes());
    var wire = AccountSelectedPublicationSettlementGrpcCodec.toRequest(request);
    for (SettleSelectedPublicationRequest invalid :
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
            wire.toBuilder().setOriginalOperation(ByteString.copyFrom(new byte[] {1})).build())) {
      assertThatThrownBy(() -> AccountSelectedPublicationSettlementGrpcCodec.fromRequest(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }

    assertThatThrownBy(
            () ->
                AccountSelectedPublicationSettlementGrpcCodec.Request.create(
                    "test",
                    new byte[GameDesignPublicationOperationBinding.MAX_OPERATION_BYTES + 1]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("4 MiB");
  }

  @Test
  void rejectsChangedRequestEchoUnknownFieldsAndChangedOrNoncanonicalReceiptFrames()
      throws Exception {
    var operation = WorldPublicationTerminalTestFixtures.operation();
    var terminal = WorldPublicationTerminalTestFixtures.terminal(operation, Outcome.NO_PUBLICATION);
    var request =
        AccountSelectedPublicationSettlementGrpcCodec.Request.create(
            "test", operation.canonicalBytes());
    var changedOperation = WorldPublicationTerminalTestFixtures.operation();
    var changedTerminal =
        WorldPublicationTerminalTestFixtures.terminal(changedOperation, Outcome.PUBLISHED);
    var sameOperationOtherOutcome =
        WorldPublicationTerminalTestFixtures.terminal(operation, Outcome.PUBLISHED);
    byte[] receipt = receipt(operation, terminal, null, null, null, null);
    var response = AccountSelectedPublicationSettlementGrpcCodec.toResponse(request, receipt);
    var changedBinding = new byte[] {1, 2, 3};
    byte[] wrongOutcome = receipt(operation, terminal, "PUBLISHED", null, null, null);
    byte[] wrongBinding = receipt(operation, terminal, null, changedBinding, null, null);
    byte[] wrongOperation =
        receipt(operation, terminal, null, null, changedOperation.canonicalBytes(), null);
    byte[] wrongTerminal = receipt(operation, changedTerminal, null, null, null, null);
    byte[] wrongWorldTerminal =
        receipt(operation, terminal, null, null, null, sameOperationOtherOutcome.canonicalBytes());
    for (var invalid :
        List.of(
            response.toBuilder().setUnknownFields(unknown()).build(),
            response.toBuilder().setSchemaVersion(2).build(),
            response.toBuilder().setTargetNamespace("other").build(),
            response.toBuilder().setRequestId(UUID.randomUUID().toString()).build(),
            response.toBuilder()
                .setOriginalOperation(ByteString.copyFrom(changedOperation.canonicalBytes()))
                .build(),
            response.toBuilder().clearSettlementReceipt().build(),
            response.toBuilder().setSettlementReceipt(ByteString.copyFrom(wrongOutcome)).build(),
            response.toBuilder().setSettlementReceipt(ByteString.copyFrom(wrongBinding)).build(),
            response.toBuilder().setSettlementReceipt(ByteString.copyFrom(wrongOperation)).build(),
            response.toBuilder().setSettlementReceipt(ByteString.copyFrom(wrongTerminal)).build(),
            response.toBuilder()
                .setSettlementReceipt(ByteString.copyFrom(wrongWorldTerminal))
                .build(),
            response.toBuilder()
                .setSettlementReceipt(
                    response.getSettlementReceipt().concat(ByteString.copyFrom(new byte[] {0})))
                .build())) {
      assertThatThrownBy(
              () -> AccountSelectedPublicationSettlementGrpcCodec.fromResponse(request, invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () -> AccountSelectedPublicationSettlementGrpcCodec.toResponse(request, wrongOutcome))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void enforcesCompleteSerializedMessageBudgetBeforeReceiptDecoding() throws Exception {
    var operation = WorldPublicationTerminalTestFixtures.operation();
    var request =
        AccountSelectedPublicationSettlementGrpcCodec.Request.create(
            "test", operation.canonicalBytes());
    byte[] oversizedReceipt =
        new byte[AccountSelectedPublicationSettlementGrpcCodec.MAX_MESSAGE_BYTES];
    assertThatThrownBy(
            () ->
                AccountSelectedPublicationSettlementGrpcCodec.toResponse(request, oversizedReceipt))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32 MiB");

    var oversizedResponse =
        SettleSelectedPublicationResponse.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setRequestId(request.requestId().toString())
            .setOriginalOperation(ByteString.copyFrom(request.originalOperation()))
            .setSettlementReceipt(ByteString.copyFrom(oversizedReceipt))
            .build();
    assertThatThrownBy(
            () ->
                AccountSelectedPublicationSettlementGrpcCodec.fromResponse(
                    request, oversizedResponse))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32 MiB");
  }

  private static byte[] receipt(
      GameDesignPublicationOperationBinding operation,
      GameDesignPublicationTerminalEvidence terminal,
      String outcomeOverride,
      byte[] accountBindingOverride,
      byte[] operationOverride,
      byte[] worldTerminalOverride) {
    String outcome =
        outcomeOverride == null
            ? terminal.outcome() == Outcome.PUBLISHED ? "PUBLISHED" : "ABORTED"
            : outcomeOverride;
    byte[] accountBinding =
        accountBindingOverride == null
            ? operation.account().canonicalBytes()
            : accountBindingOverride;
    byte[] operationBytes =
        operationOverride == null ? operation.canonicalBytes() : operationOverride;
    byte[] worldTerminal =
        worldTerminalOverride == null ? terminal.canonicalBytes() : worldTerminalOverride;
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, "account-selected-publication-settlement/v1");
    DraftAuthorizationFenceBinding.frame(out, accountBinding);
    DraftAuthorizationFenceBinding.frame(out, operationBytes);
    DraftAuthorizationFenceBinding.frame(out, terminal.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, outcome);
    DraftAuthorizationFenceBinding.frame(out, worldTerminal);
    return out.toByteArray();
  }

  private static UnknownFieldSet unknown() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
        .build();
  }
}
