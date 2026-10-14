package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.Acknowledgement;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.OwnerFreezePhase;
import net.firedevops.firemud.worldmanagement.v1.BeginVersionPublicationFreezeResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldSelectedDraftPublicationFreezePhase;
import org.junit.jupiter.api.Test;

class WorldSelectedDraftPublicationFreezeGrpcCodecTest {
  @Test
  void roundTripsTheClosedRequestAndCompleteFrozenAcknowledgement() {
    var request = request();
    var decoded =
        WorldSelectedDraftPublicationFreezeGrpcCodec.fromRequest(
            WorldSelectedDraftPublicationFreezeGrpcCodec.toRequest(request));
    var acknowledgement = acknowledgement(request);
    var evidence =
        WorldSelectedDraftPublicationFreezeGrpcCodec.fromResponse(
            request, WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(acknowledgement));

    assertThat(decoded).isEqualTo(request);
    assertThat(evidence.request()).isEqualTo(request);
    assertThat(evidence.acknowledgement()).isEqualTo(acknowledgement);
    assertThat(evidence.acknowledgement().requestDigest()).isEqualTo(request.requestDigest());
    assertThat(evidence.acknowledgement().intakeRequestId())
        .isEqualTo(uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));
  }

  @Test
  void rejectsUnknownFieldsMalformedBindingsAndNoncanonicalRequestValues() {
    var wire = WorldSelectedDraftPublicationFreezeGrpcCodec.toRequest(request());
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();

    assertThatThrownBy(
            () ->
                WorldSelectedDraftPublicationFreezeGrpcCodec.fromRequest(
                    wire.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    for (var changed :
        List.of(
            wire.toBuilder().setSchemaVersion(2).build(),
            wire.toBuilder().setTargetNamespace("Test").build(),
            wire.toBuilder().setCanonicalTenantId("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA").build(),
            wire.toBuilder().setCanonicalVersionId("00000000-0000-0000-0000-000000000000").build(),
            wire.toBuilder().setExpectedVersionStateEpoch(0).build(),
            wire.toBuilder().setRequestDigest("A".repeat(64)).build(),
            wire.toBuilder().setAccountPublicationAuthorizationBinding(ByteString.EMPTY).build(),
            wire.toBuilder()
                .setAccountPublicationAuthorizationBinding(
                    ByteString.copyFrom(
                        new byte
                            [WorldSelectedDraftPublicationFreezeEvidence.Request
                                    .MAX_ACCOUNT_BINDING_BYTES
                                + 1]))
                .build())) {
      assertThatThrownBy(() -> WorldSelectedDraftPublicationFreezeGrpcCodec.fromRequest(changed))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsEchoSubstitutionUnknownFieldsAndNonFrozenOrIncompleteAcknowledgements() {
    var request = request();
    var response =
        WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(acknowledgement(request));
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();

    for (BeginVersionPublicationFreezeResponse changed :
        List.of(
            response.toBuilder().setTargetNamespace("other").build(),
            response.toBuilder()
                .setCanonicalTenantId("11111111-1111-4111-8111-111111111111")
                .build(),
            response.toBuilder().setPublicationRequestId("different").build(),
            response.toBuilder().setExpectedVersionStateEpoch(2).build(),
            response.toBuilder().setVersionStateEpoch(2).build(),
            response.toBuilder().setRequestDigest("0".repeat(64)).build(),
            response.toBuilder()
                .setAccountPublicationAuthorizationBinding(ByteString.copyFrom(new byte[] {1}))
                .build(),
            response.toBuilder().setPublicationFence("not-a-uuid").build(),
            response.toBuilder()
                .setOwnerFreezePhase(
                    WorldSelectedDraftPublicationFreezePhase
                        .WORLD_SELECTED_DRAFT_PUBLICATION_FREEZE_PHASE_UNSPECIFIED)
                .build(),
            response.toBuilder().setOwnerFreezePhaseValue(77).build(),
            response.toBuilder().setAppliedCommitId(UUID.randomUUID().toString()).build(),
            response.toBuilder().setContentDigest("not-a-digest").build(),
            response.toBuilder().setDigestSchemaVersion(0).build(),
            response.toBuilder().setWorldIntakeRequestId("").build(),
            response.toBuilder()
                .setWorldIntakeRequestId("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA")
                .build(),
            response.toBuilder()
                .setWorldIntakeRequestId("00000000-0000-0000-0000-000000000000")
                .build(),
            response.toBuilder().setUnknownFields(unknown).build())) {
      assertThatThrownBy(
              () -> WorldSelectedDraftPublicationFreezeGrpcCodec.fromResponse(request, changed))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  static WorldSelectedDraftPublicationFreezeEvidence.Request request() {
    var selection = AuthoredDraftPublishSelectionReadGrpcCodecTest.binding();
    UUID actor = uuid("77777777-7777-4777-8777-777777777777");
    var account =
        new AccountPublicationAuthorizationBinding(
            uuid("88888888-8888-4888-8888-888888888888"),
            uuid("99999999-9999-4999-8999-999999999999"),
            new AccountPublicationAuthorizationBinding.PreallocationInput(actor, selection),
            List.of(
                new DraftAuthorizationFenceBinding.SourceEvidence(
                    DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                    actor.toString(),
                    "1",
                    "1",
                    null,
                    null,
                    new byte[] {1, 2, 3})));
    return WorldSelectedDraftPublicationFreezeEvidence.Request.create(
        "test",
        selection.intent().canonicalTenantId(),
        selection.intent().canonicalVersionId(),
        selection.intent().publishRequestId(),
        Long.parseLong(selection.intent().expectedVersionStateEpoch()),
        selection.digest().substring("sha256:".length()),
        account);
  }

  private static Acknowledgement acknowledgement(
      WorldSelectedDraftPublicationFreezeEvidence.Request request) {
    return new Acknowledgement(
        request,
        uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
        request.expectedVersionStateEpoch(),
        uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
        OwnerFreezePhase.FROZEN,
        request.accountBinding().input().selection().selectedCommit().commitId().toString(),
        "a".repeat(64),
        3);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
