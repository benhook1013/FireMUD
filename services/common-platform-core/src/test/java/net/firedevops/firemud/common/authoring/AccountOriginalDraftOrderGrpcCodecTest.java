package net.firedevops.firemud.common.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountOriginalDraftOrderGrpcCodecTest {
  private static final String CREDENTIAL = "original.secret.credential";

  @Test
  void exactCanonicalOriginalOrderAndCorrelationRoundTripWithoutCredentialEcho() {
    var original = original();
    var request = AccountOriginalDraftOrderGrpcCodec.Request.create("test", original, CREDENTIAL);
    var wire = AccountOriginalDraftOrderGrpcCodec.toRequest(request);
    var decoded = AccountOriginalDraftOrderGrpcCodec.fromRequest(wire, CREDENTIAL);
    assertThat(decoded.exactlyMatches(request)).isTrue();
    assertThat(wire.toString()).doesNotContain(CREDENTIAL);
    assertThat(request.toString()).doesNotContain(CREDENTIAL);
    var response = AccountOriginalDraftOrderGrpcCodec.toResponse(decoded);
    assertThat(response.toString()).doesNotContain(CREDENTIAL);
    var evidence = AccountOriginalDraftOrderGrpcCodec.fromResponse(request, response);
    assertThat(evidence.matches(request)).isTrue();
    // The response echoes only these fields; credentials are request custody, not evidence.
    assertThat(
            evidence.matches(
                new AccountOriginalDraftOrderGrpcCodec.Request(
                    request.schemaVersion(),
                    request.targetNamespace(),
                    request.requestId(),
                    request.originalAccountBinding(),
                    "another.secret.credential")))
        .isTrue();
    assertThat(
            evidence.matches(
                AccountOriginalDraftOrderGrpcCodec.Request.create("test", original, CREDENTIAL)))
        .isFalse();
    assertThat(
            java.util.Arrays.stream(AccountOriginalDraftOrderEvidence.class.getDeclaredFields())
                .noneMatch(
                    field -> field.getType() == AccountOriginalDraftOrderGrpcCodec.Request.class))
        .isTrue();
    assertThat(evidence.original().canonicalBytes()).isEqualTo(original.canonicalBytes());
    assertThat(evidence.toString()).doesNotContain(CREDENTIAL);
    var retry = AccountOriginalDraftOrderGrpcCodec.Request.create("test", original, CREDENTIAL);
    assertThat(retry.requestId()).isNotEqualTo(request.requestId());
    assertThat(retry.originalAccountBinding()).isEqualTo(request.originalAccountBinding());
    assertThatThrownBy(() -> AccountOriginalDraftOrderGrpcCodec.fromResponse(retry, response))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsUnknownNoncanonicalOversizedOrDurableCorrelationRequest() {
    var original = original();
    var request = AccountOriginalDraftOrderGrpcCodec.Request.create("test", original, CREDENTIAL);
    var wire = AccountOriginalDraftOrderGrpcCodec.toRequest(request);
    for (var invalid :
        List.of(
            wire.toBuilder().setSchemaVersion(2).build(),
            wire.toBuilder().setTargetNamespace("Test").build(),
            wire.toBuilder().setRequestId("1-1-1-1-1").build(),
            wire.toBuilder().setRequestId(original.operationId().toString()).build(),
            wire.toBuilder().setRequestId(original.requestId().toString()).build(),
            wire.toBuilder().setRequestId(original.commitId().toString()).build(),
            wire.toBuilder().setRequestId(original.fenceId().toString()).build(),
            wire.toBuilder().setUnknownFields(unknown()).build(),
            wire.toBuilder().clearOriginalAccountBinding().build(),
            wire.toBuilder()
                .setOriginalAccountBinding(ByteString.copyFrom(new byte[4194305]))
                .build())) {
      assertThatThrownBy(() -> AccountOriginalDraftOrderGrpcCodec.fromRequest(invalid, CREDENTIAL))
          .isInstanceOf(IllegalArgumentException.class)
          .hasNoCause()
          .hasMessageNotContaining(CREDENTIAL);
    }
  }

  @Test
  void rejectsAllChangedResponseBindingsAndNonCommitOrderStatus() {
    var request = AccountOriginalDraftOrderGrpcCodec.Request.create("test", original(), CREDENTIAL);
    var response = AccountOriginalDraftOrderGrpcCodec.toResponse(request);
    for (var invalid :
        List.of(
            response.toBuilder().setSchemaVersion(2).build(),
            response.toBuilder().setTargetNamespace("other").build(),
            response.toBuilder().setRequestId(UUID.randomUUID().toString()).build(),
            response.toBuilder()
                .setOriginalAccountBinding(ByteString.copyFrom(original().canonicalBytes()))
                .build(),
            response.toBuilder().clearOriginalAccountBinding().build(),
            response.toBuilder().clearStatus().build(),
            response.toBuilder().setStatusValue(99).build(),
            response.toBuilder().setUnknownFields(unknown()).build())) {
      assertThatThrownBy(() -> AccountOriginalDraftOrderGrpcCodec.fromResponse(request, invalid))
          .isInstanceOf(IllegalArgumentException.class)
          .hasNoCause()
          .hasMessageNotContaining(CREDENTIAL);
    }
  }

  static DraftAuthorizationFenceBinding original() {
    var target =
        new DraftCommitBinding.TargetProof(
            UUID.randomUUID(), UUID.randomUUID(), 1, "tenant-key", 2, "tenant-key", "NEW_GAME_ROW");
    var binding =
        DraftCommitBinding.create(
            target,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "{}")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "GAMEPLAY_RULES",
                    target.canonicalVersionId().toString(),
                    "GAMEPLAY_RULES",
                    "all",
                    "0")));
    return new DraftAuthorizationFenceBinding(
            UUID.randomUUID(),
            binding.requestId(),
            binding.commitId(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            binding.baseCommitId(),
            "0",
            binding.canonicalBytes(),
            binding.canonicalBytes(),
            binding.digest(),
            List.of(
                new DraftAuthorizationFenceBinding.SourceEvidence(
                    DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                    UUID.randomUUID().toString(),
                    "1",
                    "1",
                    "account",
                    "1",
                    new byte[] {1})))
        .withRequiredOwners();
  }

  private static UnknownFieldSet unknown() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
        .build();
  }
}
