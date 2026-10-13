package net.firedevops.firemud.common.account.startsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.UUID;
import net.firedevops.firemud.account.v1.SettleOriginalStartSessionActivationProtectionResponse;
import org.junit.jupiter.api.Test;

/** Closed selector and response-binding denials; no authenticated terminal producer proof. */
class AccountStartSessionActivationProtectionSettlementGrpcCodecTest {
  private static final String NAMESPACE = "world-runtime";

  @Test
  void roundTripsOnlyExactLookupIdentity() {
    var input = input();
    var request = AccountStartSessionActivationProtectionSettlementGrpcCodec.toRequest(input);
    var decoded =
        AccountStartSessionActivationProtectionSettlementGrpcCodec.fromRequest(request, NAMESPACE);

    assertThat(decoded).isEqualTo(input);
    assertThat(request.getSerializedSize())
        .isLessThanOrEqualTo(
            AccountStartSessionActivationProtectionSettlementGrpcCodec.MAX_REQUEST_WIRE_BYTES);
  }

  @Test
  void rejectsDuplicateUnknownChangedAndNoncanonicalRequestFields() {
    var valid = AccountStartSessionActivationProtectionSettlementGrpcCodec.toRequest(input());
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionSettlementGrpcCodec.fromRequest(
                    valid.toBuilder().addAccountProtectionFence("18").build(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionSettlementGrpcCodec.fromRequest(
                    valid.toBuilder().setTargetNamespace(0, "other-runtime").build(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionSettlementGrpcCodec.fromRequest(
                    valid.toBuilder().setAccountProtectionFence(0, "018").build(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionSettlementGrpcCodec.fromRequest(
                    valid.toBuilder()
                        .setUnknownFields(
                            UnknownFieldSet.newBuilder()
                                .addField(
                                    99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                                .build())
                        .build(),
                    NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionSettlementGrpcCodec.fromRequest(
                    valid.toBuilder()
                        .setSettlementRequestId(0, "00000000-0000-0000-0000-000000000000")
                        .build(),
                    NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void responseMustEchoRequestIdentityAndContainCompleteTerminalBytes() {
    var input = input();
    var validShape =
        SettleOriginalStartSessionActivationProtectionResponse.newBuilder()
            .addSchemaVersion(1)
            .addSettlementRequestId(input.settlementRequestId().toString())
            .addAccountProtectionId(input.accountProtectionId().toString())
            .addAccountProtectionFence(Long.toString(input.accountProtectionFence()))
            .addSettledAt("2026-10-11T00:00:00.000001Z")
            .addTerminalEvidenceCanonicalBytes(ByteString.copyFromUtf8("{}"))
            .build();

    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionSettlementGrpcCodec.fromResponse(
                    input,
                    validShape.toBuilder()
                        .setSettlementRequestId(0, UUID.randomUUID().toString())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("identity");
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionSettlementGrpcCodec.fromResponse(
                    input, validShape.toBuilder().setAccountProtectionFence(0, "19").build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("identity");
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionSettlementGrpcCodec.fromResponse(
                    input,
                    validShape.toBuilder()
                        .setAccountProtectionId(0, UUID.randomUUID().toString())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("identity");
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionSettlementGrpcCodec.fromResponse(
                    input,
                    validShape.toBuilder().addSettledAt("2026-10-11T00:00:00.000001Z").build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("identity");
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionSettlementGrpcCodec.fromResponse(
                    input,
                    validShape.toBuilder()
                        .setUnknownFields(
                            UnknownFieldSet.newBuilder()
                                .addField(
                                    99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                                .build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionSettlementGrpcCodec.fromResponse(
                    input,
                    validShape.toBuilder()
                        .setTerminalEvidenceCanonicalBytes(0, ByteString.EMPTY)
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static AccountStartSessionActivationProtectionSettlementInput input() {
    return new AccountStartSessionActivationProtectionSettlementInput(
        NAMESPACE,
        UUID.fromString("9a7b466e-8db0-4dfc-9244-8047d5b2f662"),
        UUID.fromString("51f0c814-5f0c-4c75-9de8-77dc6b265c4a"),
        18L);
  }
}
