package net.firedevops.firemud.common.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import net.firedevops.firemud.worldmanagement.v1.ReadWorldDraftTerminalOutcomeResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldDraftTerminalReadStatus;
import org.junit.jupiter.api.Test;

class WorldDraftTerminalReadGrpcCodecTest {
  private static final UUID OPERATION_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID REQUEST_ID = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID COMMIT_ID = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID FENCE_ID = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID ACTOR_ID = uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final UUID TENANT_ID = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION_ID = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID REVISION_ID = uuid("66666666-6666-4666-8666-666666666666");

  @Test
  void requestCarriesAndDefensivelyRetainsTheCompleteOriginalBinding() {
    byte[] original = originalBinding();
    var request =
        new WorldDraftTerminalReadEvidence.Request(
            1, "test", uuid("33333333-3333-4333-8333-333333333333"), original);
    original[0] ^= 1;

    var wire = WorldDraftTerminalReadGrpcCodec.toRequest(request);
    var decoded = WorldDraftTerminalReadGrpcCodec.fromRequest(wire);
    assertThat(decoded).isEqualTo(request);
    assertThat(decoded.originalAccountBinding()).containsExactly(request.originalAccountBinding());
    assertThat(
            WorldDraftTerminalReadEvidence.Request.create("test", request.originalAccountBinding())
                .readRequestId())
        .isNotEqualTo(request.accountBinding().operationId());
  }

  @Test
  void unknownIsExplicitAndCarriesNoOwnerResult() {
    var request = request();
    var response = WorldDraftTerminalReadGrpcCodec.toResponse(request, Optional.empty());

    var evidence = WorldDraftTerminalReadGrpcCodec.fromResponse(request, response);

    assertThat(evidence.request()).isEqualTo(request);
    assertThat(evidence.ownerReadback()).isEmpty();
    assertThat(response.getStatus())
        .isEqualTo(WorldDraftTerminalReadStatus.WORLD_DRAFT_TERMINAL_READ_STATUS_UNKNOWN);
    assertThat(response.getOwnerReadbackBytes()).isEmpty();
  }

  @Test
  void exactAbortMustBeWorldAndMustEchoEveryRequestFieldAndOriginalBinding() {
    var request = request();
    var binding = request.accountBinding();
    var abort =
        new DraftAuthorizationFenceBinding.OwnerReadback(
            Owner.WORLD,
            Outcome.DEFINITIVELY_ABORTED,
            binding.operationId(),
            binding.commitId(),
            binding.fenceId(),
            binding.inputDigest(),
            request.originalAccountBinding(),
            new byte[] {1, 2, 3});

    var response = WorldDraftTerminalReadGrpcCodec.toResponse(request, Optional.of(abort));
    var evidence = WorldDraftTerminalReadGrpcCodec.fromResponse(request, response);

    var decodedAbort = evidence.ownerReadback().orElseThrow();
    assertThat(decodedAbort.canonicalBytes()).containsExactly(abort.canonicalBytes());
    assertThat(decodedAbort.fullBinding()).containsExactly(request.originalAccountBinding());
    assertThat(decodedAbort.result()).containsExactly(1, 2, 3);
    assertThatThrownBy(
            () ->
                WorldDraftTerminalReadGrpcCodec.toResponse(
                    request,
                    Optional.of(
                        new DraftAuthorizationFenceBinding.OwnerReadback(
                            Owner.GAME_DESIGN,
                            Outcome.DEFINITIVELY_ABORTED,
                            binding.operationId(),
                            binding.commitId(),
                            binding.fenceId(),
                            binding.inputDigest(),
                            request.originalAccountBinding(),
                            new byte[] {1}))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("World terminal response");

    assertThatThrownBy(
            () ->
                WorldDraftTerminalReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setReadRequestId(uuid("77777777-7777-4777-8777-777777777777").toString())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("changed the exact read request");
    assertThatThrownBy(
            () ->
                WorldDraftTerminalReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setOriginalAccountBinding(
                            com.google.protobuf.ByteString.copyFrom(new byte[] {9}))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("changed the exact read request");
  }

  @Test
  void rejectsUnknownFieldsAndContradictoryStatusPayloads() {
    var request = request();
    var encoded = WorldDraftTerminalReadGrpcCodec.toRequest(request);
    assertThatThrownBy(
            () ->
                WorldDraftTerminalReadGrpcCodec.fromRequest(
                    encoded.toBuilder()
                        .setUnknownFields(
                            UnknownFieldSet.newBuilder()
                                .addField(
                                    99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                                .build())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");

    ReadWorldDraftTerminalOutcomeResponse unknownWithBytes =
        WorldDraftTerminalReadGrpcCodec.toResponse(request, Optional.empty()).toBuilder()
            .setOwnerReadbackBytes(com.google.protobuf.ByteString.copyFrom(new byte[] {1}))
            .build();
    assertThatThrownBy(
            () -> WorldDraftTerminalReadGrpcCodec.fromResponse(request, unknownWithBytes))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("UNKNOWN");
  }

  private static WorldDraftTerminalReadEvidence.Request request() {
    return new WorldDraftTerminalReadEvidence.Request(
        1, "test", uuid("33333333-3333-4333-8333-333333333333"), originalBinding());
  }

  private static byte[] originalBinding() {
    DraftCommitBinding draft =
        DraftCommitBinding.create(
            new TargetProof(
                TENANT_ID, VERSION_ID, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW"),
            REQUEST_ID,
            COMMIT_ID,
            "base-1",
            List.of(
                new RevisionPayload(
                    "0", REVISION_ID, DraftCommitBinding.Owner.WORLD_MANAGEMENT, "{}")),
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
                    new byte[] {4, 5})))
        .canonicalBytes();
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
