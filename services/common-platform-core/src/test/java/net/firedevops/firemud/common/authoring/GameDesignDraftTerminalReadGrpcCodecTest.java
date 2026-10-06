package net.firedevops.firemud.common.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.gamedesign.v1.GameDesignDraftTerminalReadStatus;
import net.firedevops.firemud.gamedesign.v1.ReadGameDesignDraftTerminalOutcomeResponse;
import org.junit.jupiter.api.Test;

class GameDesignDraftTerminalReadGrpcCodecTest {
  private static final UUID OPERATION_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID REQUEST_ID = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID COMMIT_ID = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID FENCE_ID = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID ACTOR_ID = uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final UUID TENANT_ID = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION_ID = uuid("22222222-2222-4222-8222-222222222222");

  @Test
  void requestCarriesDefensiveFullAccountBytesAndFreshSeparateIdentity() {
    byte[] original = originalAccountBinding();
    var request =
        new GameDesignDraftTerminalReadEvidence.Request(1, "test", freshReadId(), original);
    original[0] ^= 1;

    var wire = GameDesignDraftTerminalReadGrpcCodec.toRequest(request);
    var decoded = GameDesignDraftTerminalReadGrpcCodec.fromRequest(wire);

    assertThat(decoded).isEqualTo(request);
    assertThat(decoded.originalAccountBinding()).containsExactly(request.originalAccountBinding());
    assertThat(
            GameDesignDraftTerminalReadEvidence.Request.create(
                    "test", request.originalAccountBinding())
                .readRequestId())
        .isNotEqualTo(request.accountBinding().operationId());
    assertThatThrownBy(
            () ->
                new GameDesignDraftTerminalReadEvidence.Request(
                    1,
                    "test",
                    request.accountBinding().commitId(),
                    request.originalAccountBinding()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fresh identity");
  }

  @Test
  void unknownIsExplicitAndCarriesNoOwnerResult() {
    var request = request();
    var response = GameDesignDraftTerminalReadGrpcCodec.toResponse(request, Optional.empty());

    var evidence = GameDesignDraftTerminalReadGrpcCodec.fromResponse(request, response);

    assertThat(evidence.request()).isEqualTo(request);
    assertThat(evidence.ownerReadback()).isEmpty();
    assertThat(response.getStatus())
        .isEqualTo(
            GameDesignDraftTerminalReadStatus.GAME_DESIGN_DRAFT_TERMINAL_READ_STATUS_UNKNOWN);
    assertThat(response.getOwnerReadbackBytes()).isEmpty();
    assertThatThrownBy(
            () ->
                GameDesignDraftTerminalReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setOwnerReadbackBytes(ByteString.copyFrom(new byte[] {1}))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("UNKNOWN");
  }

  @Test
  void exactCommittedAndAbortedOwnerReadbacksRoundTripAgainstOriginalBinding() {
    var request = request();
    var binding = request.accountBinding();
    for (Outcome outcome : List.of(Outcome.COMMITTED, Outcome.DEFINITIVELY_ABORTED)) {
      byte[] result = new byte[] {3, 2, (byte) outcome.ordinal()};
      var readback =
          new DraftAuthorizationFenceBinding.OwnerReadback(
              Owner.GAME_DESIGN,
              outcome,
              binding.operationId(),
              binding.commitId(),
              binding.fenceId(),
              binding.inputDigest(),
              request.originalAccountBinding(),
              result);

      var wire = GameDesignDraftTerminalReadGrpcCodec.toResponse(request, Optional.of(readback));
      var decoded = GameDesignDraftTerminalReadGrpcCodec.fromResponse(request, wire);

      assertThat(decoded.ownerReadback()).isPresent();
      assertThat(decoded.ownerReadback().orElseThrow().canonicalBytes())
          .containsExactly(readback.canonicalBytes());
      assertThat(decoded.ownerReadback().orElseThrow().fullBinding())
          .containsExactly(request.originalAccountBinding());
      assertThat(decoded.ownerReadback().orElseThrow().result()).containsExactly(result);
      assertThat(wire.getStatus())
          .isEqualTo(
              outcome == Outcome.COMMITTED
                  ? GameDesignDraftTerminalReadStatus
                      .GAME_DESIGN_DRAFT_TERMINAL_READ_STATUS_COMMITTED
                  : GameDesignDraftTerminalReadStatus
                      .GAME_DESIGN_DRAFT_TERMINAL_READ_STATUS_DEFINITIVELY_ABORTED);
    }
  }

  @Test
  void rejectsUnknownFieldsEchoMismatchWrongOwnerAndStatusMismatch() {
    var request = request();
    var binding = request.accountBinding();
    var committed = ownerReadback(request, Outcome.COMMITTED, Owner.GAME_DESIGN);
    var response = GameDesignDraftTerminalReadGrpcCodec.toResponse(request, Optional.of(committed));

    assertThatThrownBy(
            () ->
                GameDesignDraftTerminalReadGrpcCodec.fromRequest(
                    GameDesignDraftTerminalReadGrpcCodec.toRequest(request).toBuilder()
                        .setUnknownFields(
                            UnknownFieldSet.newBuilder()
                                .addField(
                                    99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                                .build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                GameDesignDraftTerminalReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setReadRequestId("dddddddd-dddd-4ddd-8ddd-dddddddddddd")
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("changed the exact read request");
    assertThatThrownBy(
            () ->
                GameDesignDraftTerminalReadGrpcCodec.fromResponse(
                    request, response.toBuilder().setSchemaVersion(2).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("changed the exact read request");
    assertThatThrownBy(
            () ->
                GameDesignDraftTerminalReadGrpcCodec.fromResponse(
                    request, response.toBuilder().setTargetNamespace("other").build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("changed the exact read request");
    assertThatThrownBy(
            () ->
                GameDesignDraftTerminalReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setOriginalAccountBinding(ByteString.copyFrom(new byte[] {5}))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("changed the exact read request");
    assertThatThrownBy(
            () ->
                GameDesignDraftTerminalReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setStatus(
                            GameDesignDraftTerminalReadStatus
                                .GAME_DESIGN_DRAFT_TERMINAL_READ_STATUS_DEFINITIVELY_ABORTED)
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("status differs");
    var changedAccountBinding =
        DraftAuthorizationFenceBinding.fromStored(originalAccountBinding(new byte[] {9}));
    var substitutedReadback =
        new DraftAuthorizationFenceBinding.OwnerReadback(
            Owner.GAME_DESIGN,
            Outcome.COMMITTED,
            changedAccountBinding.operationId(),
            changedAccountBinding.commitId(),
            changedAccountBinding.fenceId(),
            changedAccountBinding.inputDigest(),
            changedAccountBinding.canonicalBytes(),
            new byte[] {1, 2, 3});
    var substitutedResponse =
        ReadGameDesignDraftTerminalOutcomeResponse.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setReadRequestId(request.readRequestId().toString())
            .setOriginalAccountBinding(ByteString.copyFrom(request.originalAccountBinding()))
            .setStatus(
                GameDesignDraftTerminalReadStatus.GAME_DESIGN_DRAFT_TERMINAL_READ_STATUS_COMMITTED)
            .setOwnerReadbackBytes(ByteString.copyFrom(substitutedReadback.canonicalBytes()))
            .build();
    assertThatThrownBy(
            () -> GameDesignDraftTerminalReadGrpcCodec.fromResponse(request, substitutedResponse))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("differs from complete Draft binding");
    assertThatThrownBy(
            () ->
                GameDesignDraftTerminalReadGrpcCodec.toResponse(
                    request, Optional.of(ownerReadback(request, Outcome.COMMITTED, Owner.WORLD))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Game Design response");
    var changedBinding =
        new DraftAuthorizationFenceBinding.OwnerReadback(
            Owner.GAME_DESIGN,
            Outcome.COMMITTED,
            binding.operationId(),
            binding.commitId(),
            binding.fenceId(),
            binding.inputDigest(),
            new byte[] {7},
            new byte[] {1});
    assertThatThrownBy(
            () ->
                GameDesignDraftTerminalReadGrpcCodec.toResponse(
                    request, Optional.of(changedBinding)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMissingTerminalPayloadAndUnknownResponseFields() {
    var request = request();
    var response =
        GameDesignDraftTerminalReadGrpcCodec.toResponse(
                request, Optional.of(ownerReadback(request, Outcome.COMMITTED, Owner.GAME_DESIGN)))
            .toBuilder()
            .setOwnerReadbackBytes(ByteString.EMPTY)
            .build();
    assertThatThrownBy(() -> GameDesignDraftTerminalReadGrpcCodec.fromResponse(request, response))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bytes are required");

    var malformedReadback =
        GameDesignDraftTerminalReadGrpcCodec.toResponse(
                request, Optional.of(ownerReadback(request, Outcome.COMMITTED, Owner.GAME_DESIGN)))
            .toBuilder()
            .setOwnerReadbackBytes(ByteString.copyFrom(new byte[] {0, 1, 2}))
            .build();
    assertThatThrownBy(
            () -> GameDesignDraftTerminalReadGrpcCodec.fromResponse(request, malformedReadback))
        .isInstanceOf(IllegalArgumentException.class);

    var responseWithUnknownField =
        GameDesignDraftTerminalReadGrpcCodec.toResponse(request, Optional.empty()).toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                    .build())
            .build();
    assertThatThrownBy(
            () ->
                GameDesignDraftTerminalReadGrpcCodec.fromResponse(
                    request, responseWithUnknownField))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
  }

  private static DraftAuthorizationFenceBinding.OwnerReadback ownerReadback(
      GameDesignDraftTerminalReadEvidence.Request request, Outcome outcome, Owner owner) {
    var binding = request.accountBinding();
    return new DraftAuthorizationFenceBinding.OwnerReadback(
        owner,
        outcome,
        binding.operationId(),
        binding.commitId(),
        binding.fenceId(),
        binding.inputDigest(),
        request.originalAccountBinding(),
        new byte[] {1, 2, 3});
  }

  private static GameDesignDraftTerminalReadEvidence.Request request() {
    return new GameDesignDraftTerminalReadEvidence.Request(
        1, "test", freshReadId(), originalAccountBinding());
  }

  private static UUID freshReadId() {
    return uuid("33333333-3333-4333-8333-333333333333");
  }

  private static byte[] originalAccountBinding() {
    return originalAccountBinding(new byte[] {4, 5});
  }

  private static byte[] originalAccountBinding(byte[] sourceEvidenceBytes) {
    DraftCommitBinding draft =
        DraftCommitBinding.create(
            new TargetProof(
                TENANT_ID, VERSION_ID, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW"),
            REQUEST_ID,
            COMMIT_ID,
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
            OPERATION_ID,
            REQUEST_ID,
            COMMIT_ID,
            FENCE_ID,
            ACTOR_ID,
            TENANT_ID,
            VERSION_ID,
            "base-1",
            "0",
            draftBytes,
            draftBytes,
            draft.digest(),
            List.of(
                new SourceEvidence(
                    SourceKind.GLOBAL_ROLES,
                    uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd").toString(),
                    null,
                    "1",
                    null,
                    null,
                    sourceEvidenceBytes)))
        .canonicalBytes();
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
