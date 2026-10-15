package net.firedevops.firemud.common.account.startsession;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AcquireOriginalStartSessionActivationProtectionRequest;
import org.junit.jupiter.api.Test;

/** Closed-envelope denial tests; no World or Account producer authentication is claimed. */
class AccountStartSessionActivationProtectionAcquisitionGrpcCodecTest {
  private static final String NAMESPACE = "world-runtime";
  private static final String MUTATION = "11111111-1111-4111-8111-111111111111";
  private static final String ATTEMPT = "22222222-2222-4222-8222-222222222222";
  private static final String ACTIVATION = "33333333-3333-4333-8333-333333333333";

  @Test
  void rejectsDuplicateAndUnknownRequestFieldsBeforeDecodingOwnerBytes() {
    var duplicate =
        AcquireOriginalStartSessionActivationProtectionRequest.newBuilder()
            .addSchemaVersion(1)
            .addSchemaVersion(1)
            .build();
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionAcquisitionGrpcCodec.fromRequest(
                    duplicate, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate field");

    var unknown =
        AcquireOriginalStartSessionActivationProtectionRequest.newBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionAcquisitionGrpcCodec.fromRequest(
                    unknown, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
  }

  @Test
  void rejectsNonCanonicalFenceTextAndRawDuplicateOccurrences() {
    var nonCanonicalFence = minimallyPopulatedRequest("01");
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionAcquisitionGrpcCodec.fromRequest(
                    nonCanonicalFence, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical positive unsigned decimal");

    var duplicateSchemaWire =
        AcquireOriginalStartSessionActivationProtectionRequest.newBuilder()
            .addSchemaVersion(1)
            .addSchemaVersion(1)
            .build()
            .toByteArray();
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionAcquisitionGrpcCodec.fromRequestWireBytes(
                    duplicateSchemaWire, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate field");
  }

  @Test
  void rejectsMismatchedTargetAndUnusableOriginalEvidence() {
    var request =
        AcquireOriginalStartSessionActivationProtectionRequest.newBuilder()
            .addSchemaVersion(1)
            .addTargetNamespace("other-runtime")
            .addOriginalPostAuthorizationTuple(ByteString.copyFrom(new byte[] {1}))
            .addGameSessionOwnerMutationId(MUTATION)
            .addGameSessionOwnerAttemptId(ATTEMPT)
            .addGameSessionOwnerFence("7")
            .addActivationRequestId(ACTIVATION)
            .addWorldPreparingEvidence(ByteString.copyFrom(new byte[] {1}))
            .build();
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionAcquisitionGrpcCodec.fromRequest(
                    request, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("target namespace");

    assertThatThrownBy(
            () ->
                new AccountStartSessionActivationProtectionAcquisitionInput(
                    new byte[] {1}, UUID.fromString(MUTATION), UUID.fromString(ATTEMPT), 7L, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static AcquireOriginalStartSessionActivationProtectionRequest minimallyPopulatedRequest(
      String fence) {
    return AcquireOriginalStartSessionActivationProtectionRequest.newBuilder()
        .addSchemaVersion(1)
        .addTargetNamespace(NAMESPACE)
        .addOriginalPostAuthorizationTuple(ByteString.copyFrom(new byte[] {1}))
        .addGameSessionOwnerMutationId(MUTATION)
        .addGameSessionOwnerAttemptId(ATTEMPT)
        .addGameSessionOwnerFence(fence)
        .addActivationRequestId(ACTIVATION)
        .addWorldPreparingEvidence(ByteString.copyFrom(new byte[] {1}))
        .build();
  }
}
