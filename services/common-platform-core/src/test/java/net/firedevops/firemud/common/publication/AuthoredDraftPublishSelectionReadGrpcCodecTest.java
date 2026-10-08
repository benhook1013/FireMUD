package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.PublishIntent;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.VisibilityFence;
import net.firedevops.firemud.gamedesign.v1.ReadSelectedDraftPublicationResponse;
import net.firedevops.firemud.gamedesign.v1.SelectedDraftPublicationStatus;
import org.junit.jupiter.api.Test;

class AuthoredDraftPublishSelectionReadGrpcCodecTest {
  @Test
  void rejectsNilNoncanonicalIdentityAndMalformedOrNoncanonicalSelectionBytes() {
    var request = AuthoredDraftPublishSelectionReadGrpcCodec.toRequest(request());
    for (String invalidId :
        List.of(
            "00000000-0000-0000-0000-000000000000",
            "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA",
            "1-1-1-1-1")) {
      assertThatThrownBy(
              () ->
                  AuthoredDraftPublishSelectionReadGrpcCodec.fromRequest(
                      request.toBuilder().setReadRequestId(invalidId).build()))
          .isInstanceOf(IllegalArgumentException.class);
    }
    String canonical = binding().canonicalJson();
    for (byte[] invalidBytes :
        List.of(
            new byte[0],
            new byte[] {(byte) 0xff},
            (" " + canonical).getBytes(StandardCharsets.UTF_8),
            (canonical.substring(0, canonical.length() - 1) + ",\"unknown\":\"value\"}")
                .getBytes(StandardCharsets.UTF_8))) {
      assertThatThrownBy(
              () ->
                  AuthoredDraftPublishSelectionReadGrpcCodec.fromRequest(
                      request.toBuilder()
                          .setOriginalSelection(ByteString.copyFrom(invalidBytes))
                          .build()))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (String namespace : List.of("", "Test", "test.other")) {
      assertThatThrownBy(
              () ->
                  AuthoredDraftPublishSelectionReadGrpcCodec.fromRequest(
                      request.toBuilder().setTargetNamespace(namespace).build()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void retainsDefensiveCopiesOfTheExactOriginalBytes() {
    var binding = binding();
    byte[] original = binding.canonicalBytes();
    var request =
        new AuthoredDraftPublishSelectionReadEvidence.Request(
            1, "test", UUID.randomUUID(), original, binding.digest());
    original[0] = 0;
    byte[] returned = request.originalSelection();
    returned[0] = 0;
    assertThat(request.originalSelection()).containsExactly(binding.canonicalBytes());
    assertThat(request.binding()).isEqualTo(binding);
  }

  @Test
  void mapsCompleteCanonicalBindingDigestAndStableReadIdentity() {
    var request = request();

    var decoded =
        AuthoredDraftPublishSelectionReadGrpcCodec.fromRequest(
            AuthoredDraftPublishSelectionReadGrpcCodec.toRequest(request));
    var evidence =
        AuthoredDraftPublishSelectionReadGrpcCodec.fromResponse(
            request, AuthoredDraftPublishSelectionReadGrpcCodec.toSelectedResponse(request));

    assertThat(decoded).isEqualTo(request);
    assertThat(evidence.request()).isEqualTo(request);
    assertThat(evidence.request().binding().canonicalBytes())
        .containsExactly(request.originalSelection());
    assertThat(evidence.request().binding().intent()).isEqualTo(request.binding().intent());
  }

  @Test
  void rejectsMalformedRequestsUnknownFieldsAndChangedBindingDigest() {
    var request = AuthoredDraftPublishSelectionReadGrpcCodec.toRequest(request());
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();

    assertThatThrownBy(
            () ->
                AuthoredDraftPublishSelectionReadGrpcCodec.fromRequest(
                    request.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                AuthoredDraftPublishSelectionReadGrpcCodec.fromRequest(
                    request.toBuilder().setReadRequestId("not-a-uuid").build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AuthoredDraftPublishSelectionReadGrpcCodec.fromRequest(
                    request.toBuilder().setSchemaVersion(2).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AuthoredDraftPublishSelectionReadGrpcCodec.fromRequest(
                    request.toBuilder().setSelectionDigest("sha256:" + "0".repeat(64)).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Selected Draft publication read request is invalid")
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsResponseSubstitutionUnknownFieldsAndNonSelectedStatuses() {
    var request = request();
    var held = AuthoredDraftPublishSelectionReadGrpcCodec.toSelectedResponse(request);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();

    for (ReadSelectedDraftPublicationResponse changed :
        List.of(
            held.toBuilder().setSchemaVersion(2).build(),
            held.toBuilder().setTargetNamespace("other").build(),
            held.toBuilder().setReadRequestId(UUID.randomUUID().toString()).build(),
            held.toBuilder().setOriginalSelection(ByteString.copyFrom(new byte[] {1})).build(),
            held.toBuilder().setSelectionDigest("sha256:" + "0".repeat(64)).build(),
            held.toBuilder()
                .setStatus(
                    SelectedDraftPublicationStatus.SELECTED_DRAFT_PUBLICATION_STATUS_UNSPECIFIED)
                .build(),
            held.toBuilder().setStatusValue(99).build(),
            held.toBuilder().setUnknownFields(unknown).build())) {
      assertThatThrownBy(
              () -> AuthoredDraftPublishSelectionReadGrpcCodec.fromResponse(request, changed))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  static AuthoredDraftPublishSelectionReadEvidence.Request request() {
    var binding = binding();
    return AuthoredDraftPublishSelectionReadEvidence.Request.create("test", binding);
  }

  static AuthoredDraftPublishSelectionBinding binding() {
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
    return selected;
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
