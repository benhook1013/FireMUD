package net.firedevops.firemud.common.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.account.v1.HeldOriginalCommitOrderStatus;
import net.firedevops.firemud.account.v1.ReadHeldOriginalCommitOrderResponse;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import org.junit.jupiter.api.Test;

class DraftCommitOrderReadGrpcCodecTest {
  @Test
  void mapsTheCompleteCanonicalBindingAndStableReadIdentity() {
    var request = request();

    var decoded =
        DraftCommitOrderReadGrpcCodec.fromRequest(DraftCommitOrderReadGrpcCodec.toRequest(request));
    var evidence =
        DraftCommitOrderReadGrpcCodec.fromResponse(
            request, DraftCommitOrderReadGrpcCodec.toHeldResponse(request));

    assertThat(decoded).isEqualTo(request);
    assertThat(evidence.request()).isEqualTo(request);
    assertThat(decoded.originalAccountBinding()).containsExactly(request.originalAccountBinding());
  }

  @Test
  void rejectsMalformedRequestsUnknownFieldsAndNonCanonicalReadIdentity() {
    var request = DraftCommitOrderReadGrpcCodec.toRequest(request());
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();

    assertThatThrownBy(
            () ->
                DraftCommitOrderReadGrpcCodec.fromRequest(
                    request.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                DraftCommitOrderReadGrpcCodec.fromRequest(
                    request.toBuilder().setReadRequestId("not-a-uuid").build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                DraftCommitOrderReadGrpcCodec.fromRequest(
                    request.toBuilder().setSchemaVersion(2).build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsResponseSubstitutionUnknownFieldsAndNonHeldStatuses() {
    var request = request();
    var held = DraftCommitOrderReadGrpcCodec.toHeldResponse(request);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();

    for (ReadHeldOriginalCommitOrderResponse changed :
        List.of(
            held.toBuilder().setTargetNamespace("other").build(),
            held.toBuilder().setReadRequestId(UUID.randomUUID().toString()).build(),
            held.toBuilder().setOriginalAccountBinding(ByteString.copyFrom(new byte[] {1})).build(),
            held.toBuilder()
                .setStatus(
                    HeldOriginalCommitOrderStatus.HELD_ORIGINAL_COMMIT_ORDER_STATUS_UNSPECIFIED)
                .build(),
            held.toBuilder().setUnknownFields(unknown).build())) {
      assertThatThrownBy(() -> DraftCommitOrderReadGrpcCodec.fromResponse(request, changed))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  static DraftCommitOrderReadEvidence.Request request() {
    byte[] binding = accountBinding();
    return new DraftCommitOrderReadEvidence.Request(
        1, "test", uuid("33333333-3333-4333-8333-333333333333"), binding);
  }

  static byte[] accountBinding() {
    UUID tenant = uuid("11111111-1111-4111-8111-111111111111");
    UUID version = uuid("22222222-2222-4222-8222-222222222222");
    UUID request = uuid("44444444-4444-4444-8444-444444444444");
    UUID commit = uuid("55555555-5555-4555-8555-555555555555");
    DraftCommitBinding draft =
        DraftCommitBinding.create(
            new TargetProof(tenant, version, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW"),
            request,
            commit,
            "base-1",
            List.of(
                new RevisionPayload(
                    "0",
                    uuid("66666666-6666-4666-8666-666666666666"),
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "{}")),
            List.of(
                new AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "WORLD_TEMPLATE",
                    "world-1",
                    "ROOM_SCOPE",
                    "room-1",
                    "0")));
    byte[] draftBytes = draft.canonicalBytes();
    return new DraftAuthorizationFenceBinding(
            uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            request,
            commit,
            uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            tenant,
            version,
            "base-1",
            "0",
            draftBytes,
            draftBytes,
            draft.digest(),
            List.of(
                new SourceEvidence(
                    SourceKind.GLOBAL_ROLES,
                    "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                    null,
                    "1",
                    null,
                    null,
                    new byte[] {1})))
        .canonicalBytes();
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
