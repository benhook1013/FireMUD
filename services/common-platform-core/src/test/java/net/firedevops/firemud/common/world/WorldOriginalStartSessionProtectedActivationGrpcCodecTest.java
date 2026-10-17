package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import java.util.UUID;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionAcquisitionGrpcCodec;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionAcquisitionInput;
import net.firedevops.firemud.worldmanagement.v1.ActivateOriginalStartSessionProtectedRequest;
import net.firedevops.firemud.worldmanagement.v1.ActivateOriginalStartSessionProtectedResponse;
import org.junit.jupiter.api.Test;

/**
 * Closed-envelope tests; mock inputs do not prove authenticated acquisition or a terminal write.
 */
class WorldOriginalStartSessionProtectedActivationGrpcCodecTest {
  @Test
  void outerCarrierPreservesTheCompleteAccountAcquisitionRequestWithoutNarrowing() {
    var input = mockInput();
    var nested =
        AccountStartSessionActivationProtectionAcquisitionGrpcCodec.toRequest(
            input, "world-runtime");

    var request =
        WorldOriginalStartSessionProtectedActivationGrpcCodec.toRequest(input, "world-runtime");

    assertThat(request.getSchemaVersionList()).containsExactly(1);
    assertThat(request.getTargetNamespaceList()).containsExactly("world-runtime");
    assertThat(request.getAcquisitionInputList()).containsExactly(nested);
    assertThat(request.getSerializedSize())
        .isLessThanOrEqualTo(
            WorldOriginalStartSessionProtectedActivationGrpcCodec.MAX_REQUEST_WIRE_BYTES);
  }

  @Test
  void rejectsDuplicateUnknownOrMissingOuterFieldsBeforeInputUse() {
    var nested =
        AccountStartSessionActivationProtectionAcquisitionGrpcCodec.toRequest(
            mockInput(), "world-runtime");
    var duplicate =
        ActivateOriginalStartSessionProtectedRequest.newBuilder()
            .addSchemaVersion(1)
            .addSchemaVersion(1)
            .addTargetNamespace("world-runtime")
            .addAcquisitionInput(nested)
            .build();
    assertThatThrownBy(
            () ->
                WorldOriginalStartSessionProtectedActivationGrpcCodec.fromRequest(
                    duplicate, "world-runtime"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate");

    var unknown =
        ActivateOriginalStartSessionProtectedRequest.newBuilder()
            .addSchemaVersion(1)
            .addTargetNamespace("world-runtime")
            .addAcquisitionInput(nested)
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                    .build())
            .build();
    assertThatThrownBy(
            () ->
                WorldOriginalStartSessionProtectedActivationGrpcCodec.fromRequest(
                    unknown, "world-runtime"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");

    assertThatThrownBy(
            () ->
                WorldOriginalStartSessionProtectedActivationGrpcCodec.fromRequest(
                    ActivateOriginalStartSessionProtectedRequest.getDefaultInstance(),
                    "world-runtime"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMissingResponseReceiptAndOversizedResponseCarrier() {
    var empty =
        ActivateOriginalStartSessionProtectedResponse.newBuilder().addSchemaVersion(1).build();
    assertThatThrownBy(
            () ->
                WorldOriginalStartSessionProtectedActivationGrpcCodec.fromResponse(
                    mockInput(), "world-runtime", empty))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(WorldOriginalStartSessionProtectedActivationGrpcCodec.MAX_RESPONSE_WIRE_BYTES)
        .isEqualTo(WorldOriginalStartSessionActivationTerminalEvidence.MAX_CANONICAL_BYTES + 64);
  }

  private static AccountStartSessionActivationProtectionAcquisitionInput mockInput() {
    var input = mock(AccountStartSessionActivationProtectionAcquisitionInput.class);
    var activation = mock(WorldCanonicalInstanceActivation.Request.class);
    doNothing().when(input).requireTargetNamespace("world-runtime");
    when(input.originalPostAuthorizationTuple()).thenReturn(new byte[] {1, 2, 3});
    when(input.gameSessionOwnerMutationId())
        .thenReturn(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    when(input.gameSessionOwnerAttemptId())
        .thenReturn(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    when(input.gameSessionOwnerFence()).thenReturn(3L);
    when(input.worldActivationRequest()).thenReturn(activation);
    when(activation.activationRequestId())
        .thenReturn(UUID.fromString("33333333-3333-4333-8333-333333333333"));
    when(activation.preparingEvidenceBytes()).thenReturn(new byte[] {4, 5, 6});
    return input;
  }
}
