package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.account.v1.HeldPublicationAuthorizationStatus;
import net.firedevops.firemud.account.v1.ReadHeldPublicationAuthorizationResponse;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.PublishIntent;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.VisibilityFence;
import org.junit.jupiter.api.Test;

class AccountPublicationAuthorizationReadGrpcCodecTest {
  @Test
  void mapsCompleteCanonicalBindingDigestAndStableReadIdentity() {
    var request = request();

    var decoded =
        AccountPublicationAuthorizationReadGrpcCodec.fromRequest(
            AccountPublicationAuthorizationReadGrpcCodec.toRequest(request));
    var evidence =
        AccountPublicationAuthorizationReadGrpcCodec.fromResponse(
            request, AccountPublicationAuthorizationReadGrpcCodec.toHeldResponse(request));

    assertThat(decoded).isEqualTo(request);
    assertThat(evidence.request()).isEqualTo(request);
    assertThat(evidence.request().binding().canonicalBytes())
        .containsExactly(request.originalPublicationAuthorizationBinding());
    assertThat(evidence.request().binding().operationId())
        .isEqualTo(request.binding().operationId());
  }

  @Test
  void rejectsMalformedRequestsUnknownFieldsAndChangedBindingDigest() {
    var request = AccountPublicationAuthorizationReadGrpcCodec.toRequest(request());
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();

    assertThatThrownBy(
            () ->
                AccountPublicationAuthorizationReadGrpcCodec.fromRequest(
                    request.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                AccountPublicationAuthorizationReadGrpcCodec.fromRequest(
                    request.toBuilder().setReadRequestId("not-a-uuid").build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountPublicationAuthorizationReadGrpcCodec.fromRequest(
                    request.toBuilder().setSchemaVersion(2).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountPublicationAuthorizationReadGrpcCodec.fromRequest(
                    request.toBuilder()
                        .setPublicationAuthorizationDigest("sha256:" + "0".repeat(64))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Account publication authorization read request is invalid")
        .hasRootCauseMessage(
            "Account publication authorization digest differs from its canonical binding");
  }

  @Test
  void rejectsResponseSubstitutionUnknownFieldsAndNonHeldStatuses() {
    var request = request();
    var held = AccountPublicationAuthorizationReadGrpcCodec.toHeldResponse(request);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();

    for (ReadHeldPublicationAuthorizationResponse changed :
        List.of(
            held.toBuilder().setTargetNamespace("other").build(),
            held.toBuilder().setReadRequestId(UUID.randomUUID().toString()).build(),
            held.toBuilder()
                .setOriginalPublicationAuthorizationBinding(ByteString.copyFrom(new byte[] {1}))
                .build(),
            held.toBuilder().setPublicationAuthorizationDigest("sha256:" + "0".repeat(64)).build(),
            held.toBuilder()
                .setStatus(
                    HeldPublicationAuthorizationStatus
                        .HELD_PUBLICATION_AUTHORIZATION_STATUS_UNSPECIFIED)
                .build(),
            held.toBuilder().setUnknownFields(unknown).build())) {
      assertThatThrownBy(
              () -> AccountPublicationAuthorizationReadGrpcCodec.fromResponse(request, changed))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  static AccountPublicationAuthorizationReadEvidence.Request request() {
    var binding = binding();
    return AccountPublicationAuthorizationReadEvidence.Request.create("test", binding);
  }

  static AccountPublicationAuthorizationBinding binding() {
    UUID actor = uuid("11111111-1111-4111-8111-111111111111");
    UUID tenant = uuid("22222222-2222-4222-8222-222222222222");
    UUID version = uuid("33333333-3333-4333-8333-333333333333");
    var target =
        new DraftCommitBinding.TargetProof(
            tenant, version, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW");
    var commit =
        DraftCommitBinding.create(
            target,
            uuid("44444444-4444-4444-8444-444444444444"),
            uuid("55555555-5555-4555-8555-555555555555"),
            "base-1",
            List.of(
                new RevisionPayload(
                    "0",
                    uuid("66666666-6666-4666-8666-666666666666"),
                    Owner.WORLD_MANAGEMENT,
                    "{}")),
            List.of(
                new AffectedUnit(
                    Owner.WORLD_MANAGEMENT,
                    "WORLD_TEMPLATE",
                    "world-1",
                    "ROOM_SCOPE",
                    "room-1",
                    "0")));
    var selected =
        AuthoredDraftPublishSelectionBinding.capture(
            new PublishIntent(
                tenant,
                version,
                "publication-request",
                "5",
                "test selection",
                commit.requestId(),
                commit.commitId(),
                commit.digest()),
            target,
            commit,
            new VisibilityFence(
                target,
                commit.requestId(),
                commit.commitId(),
                commit.digest(),
                "[]",
                OffsetDateTime.parse("2026-10-01T00:00:00Z")));
    return new AccountPublicationAuthorizationBinding(
        uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
        uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
        new AccountPublicationAuthorizationBinding.PreallocationInput(actor, selected),
        List.of(
            new SourceEvidence(
                SourceKind.ACCOUNT, actor.toString(), null, "1", null, null, new byte[] {1})));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
