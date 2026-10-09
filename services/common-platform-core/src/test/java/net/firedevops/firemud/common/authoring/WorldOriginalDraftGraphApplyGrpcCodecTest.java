package net.firedevops.firemud.common.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Structural transport fixtures only; these do not prove genuine source or owner persistence. */
class WorldOriginalDraftGraphApplyGrpcCodecTest {
  @Test
  void admittedMaximumOriginalBindingAllowsSerializedEnvelopeOverhead() {
    byte[] original = binding(true);
    var account = DraftAuthorizationFenceBinding.fromStored(original);
    var source = account.sources().get(0);
    byte[] padding =
        new byte
            [WorldOriginalDraftGraphApplyEvidence.MAX_BINDING_BYTES
                - original.length
                + source.evidence().length];
    var padded =
        new DraftAuthorizationFenceBinding(
            account.operationId(),
            account.requestId(),
            account.commitId(),
            account.fenceId(),
            account.actorAccountId(),
            account.tenantId(),
            account.versionId(),
            account.baseCommitId(),
            account.expectedDraftEpoch(),
            account.gameDesignBinding(),
            account.normalizedInput(),
            account.inputDigest(),
            java.util.List.of(
                new DraftAuthorizationFenceBinding.SourceEvidence(
                    source.kind(),
                    source.scopeId(),
                    source.generation(),
                    source.sourceVersion(),
                    source.checkpointStream(),
                    source.checkpointSequence(),
                    padding)));
    var request =
        WorldOriginalDraftGraphApplyEvidence.Request.create("test", padded.canonicalBytes());
    assertThat(request.originalAccountBinding())
        .hasSize(WorldOriginalDraftGraphApplyEvidence.MAX_BINDING_BYTES);
    var wire = WorldOriginalDraftGraphApplyGrpcCodec.toRequest(request);
    assertThat(wire.getSerializedSize())
        .isGreaterThan(WorldOriginalDraftGraphApplyEvidence.MAX_BINDING_BYTES)
        .isLessThanOrEqualTo(WorldOriginalDraftGraphApplyGrpcCodec.MAX_REQUEST_WIRE_BYTES);
    assertThat(WorldOriginalDraftGraphApplyGrpcCodec.fromRequest(wire)).isEqualTo(request);
    var response = WorldOriginalDraftGraphApplyGrpcCodec.toResponse(request, committed(request));
    assertThat(response.getSerializedSize())
        .isGreaterThan(WorldOriginalDraftGraphApplyEvidence.MAX_BINDING_BYTES)
        .isLessThanOrEqualTo(WorldOriginalDraftGraphApplyGrpcCodec.MAX_RESPONSE_WIRE_BYTES);
    assertThat(WorldOriginalDraftGraphApplyGrpcCodec.fromResponse(request, response).request())
        .isEqualTo(request);
  }

  @Test
  void ownerReadbackHasAnIndependentHardBound() {
    var request = request("test");
    var response =
        WorldOriginalDraftGraphApplyGrpcCodec.toResponse(request, committed(request)).toBuilder()
            .setOwnerReadbackBytes(
                ByteString.copyFrom(
                    new byte[WorldOriginalDraftGraphApplyEvidence.MAX_OWNER_READBACK_BYTES + 1]))
            .build();
    assertThatThrownBy(() -> WorldOriginalDraftGraphApplyGrpcCodec.fromResponse(request, response))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exactBindingAndCommittedResultRoundTripWithOriginalIdentityAndCompleteEpochs() {
    var request = request("test");
    var wire = WorldOriginalDraftGraphApplyGrpcCodec.toRequest(request);
    assertThat(WorldOriginalDraftGraphApplyGrpcCodec.fromRequest(wire)).isEqualTo(request);
    var bytes = request.originalAccountBinding();
    bytes[0] ^= 1;
    assertThat(request.originalAccountBinding())
        .containsExactly(wire.getOriginalAccountBinding().toByteArray());
    var response = WorldOriginalDraftGraphApplyGrpcCodec.toResponse(request, committed(request));
    var result = WorldOriginalDraftGraphApplyGrpcCodec.fromResponse(request, response);
    assertThat(result)
        .isEqualTo(new WorldOriginalDraftGraphApplyEvidence.Result(request, committed(request)));
    assertThat(result.resultIdentity())
        .isEqualTo("world-draft-graph-applied/v1:" + request.accountBinding().operationId());
    assertThat(result.appliedEpochs()).hasSize(2);
    assertThat(result.appliedEpochs())
        .extracting(value -> value.resultingEpoch())
        .containsExactly("8", "20");
  }

  @Test
  void rejectsUnsupportedOwnersSchemasNamespaceAndUnboundedOrUnknownInput() {
    assertThatThrownBy(
            () -> WorldOriginalDraftGraphApplyEvidence.Request.create("test", binding(false)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new WorldOriginalDraftGraphApplyEvidence.Request(2, "test", binding(true)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> request("INVALID")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                WorldOriginalDraftGraphApplyEvidence.Request.create(
                    "test", new byte[WorldOriginalDraftGraphApplyEvidence.MAX_BINDING_BYTES + 1]))
        .isInstanceOf(IllegalArgumentException.class);
    var wire = WorldOriginalDraftGraphApplyGrpcCodec.toRequest(request("test"));
    assertThatThrownBy(
            () ->
                WorldOriginalDraftGraphApplyGrpcCodec.fromRequest(
                    wire.toBuilder().setUnknownFields(unknown()).build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsEveryEchoSubstitutionEmptyOutcomeAndUnknownResponse() {
    var request = request("test");
    var response = WorldOriginalDraftGraphApplyGrpcCodec.toResponse(request, committed(request));
    for (var changed :
        java.util.List.of(
            response.toBuilder().setSchemaVersion(2).build(),
            response.toBuilder().setTargetNamespace("other").build(),
            response.toBuilder()
                .setOriginalAccountBinding(ByteString.copyFrom(new byte[] {1}))
                .build(),
            response.toBuilder().clearOwnerReadbackBytes().build(),
            response.toBuilder().setUnknownFields(unknown()).build())) {
      assertThatThrownBy(() -> WorldOriginalDraftGraphApplyGrpcCodec.fromResponse(request, changed))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsAbortOtherOwnerFullBindingAndAppliedEpochSubstitution() throws Exception {
    var request = request("test");
    var committed = committed(request);
    assertThatThrownBy(
            () ->
                WorldOriginalDraftGraphApplyGrpcCodec.toResponse(
                    request,
                    changed(
                        committed,
                        DraftAuthorizationFenceBinding.Owner.WORLD,
                        DraftAuthorizationFenceBinding.Outcome.DEFINITIVELY_ABORTED,
                        committed.result())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                WorldOriginalDraftGraphApplyGrpcCodec.toResponse(
                    request,
                    changed(
                        committed,
                        DraftAuthorizationFenceBinding.Owner.GAME_DESIGN,
                        DraftAuthorizationFenceBinding.Outcome.COMMITTED,
                        committed.result())))
        .isInstanceOf(IllegalArgumentException.class);
    var alternate = request("other");
    assertThatThrownBy(() -> WorldOriginalDraftGraphApplyGrpcCodec.toResponse(alternate, committed))
        .isInstanceOf(IllegalArgumentException.class);
    var json = new tools.jackson.databind.ObjectMapper().readTree(committed.result());
    ((tools.jackson.databind.node.ObjectNode) json.get("appliedEpochs").get(0))
        .put("resultingEpoch", "9");
    var wrongEpoch =
        net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(json.toString());
    assertThatThrownBy(
            () ->
                WorldOriginalDraftGraphApplyGrpcCodec.toResponse(
                    request,
                    changed(committed, committed.owner(), committed.outcome(), wrongEpoch)))
        .isInstanceOf(IllegalArgumentException.class);
    var otherBinding = request.accountBinding();
    var differentAccount =
        new DraftAuthorizationFenceBinding(
            UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            otherBinding.requestId(),
            otherBinding.commitId(),
            otherBinding.fenceId(),
            otherBinding.actorAccountId(),
            otherBinding.tenantId(),
            otherBinding.versionId(),
            otherBinding.baseCommitId(),
            otherBinding.expectedDraftEpoch(),
            otherBinding.gameDesignBinding(),
            otherBinding.normalizedInput(),
            otherBinding.inputDigest(),
            otherBinding.sources());
    assertThatThrownBy(
            () ->
                WorldOriginalDraftGraphApplyGrpcCodec.toResponse(
                    WorldOriginalDraftGraphApplyEvidence.Request.create(
                        "test", differentAccount.canonicalBytes()),
                    committed))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static DraftAuthorizationFenceBinding.OwnerReadback changed(
      DraftAuthorizationFenceBinding.OwnerReadback original,
      DraftAuthorizationFenceBinding.Owner owner,
      DraftAuthorizationFenceBinding.Outcome outcome,
      byte[] result) {
    return new DraftAuthorizationFenceBinding.OwnerReadback(
        owner,
        outcome,
        original.operationId(),
        original.commitId(),
        original.fenceId(),
        original.inputDigest(),
        original.fullBinding(),
        result);
  }

  static DraftAuthorizationFenceBinding.OwnerReadback committed(
      WorldOriginalDraftGraphApplyEvidence.Request request) {
    return WorldDraftTerminalReadGrpcCodecTest.committedReadback(
        WorldDraftTerminalReadEvidence.Request.create(
            request.targetNamespace(), request.originalAccountBinding()));
  }

  private static UnknownFieldSet unknown() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
        .build();
  }

  static WorldOriginalDraftGraphApplyEvidence.Request request(String namespace) {
    return WorldOriginalDraftGraphApplyEvidence.Request.create(namespace, binding(true));
  }

  static byte[] binding(boolean includeGameDesign) {
    var tenant = UUID.fromString("11111111-1111-4111-8111-111111111111");
    var version = UUID.fromString("22222222-2222-4222-8222-222222222222");
    var request = UUID.fromString("44444444-4444-4444-8444-444444444444");
    var commit = UUID.fromString("55555555-5555-4555-8555-555555555555");
    var revisions = new java.util.ArrayList<DraftCommitBinding.RevisionPayload>();
    revisions.add(
        new DraftCommitBinding.RevisionPayload(
            "0",
            UUID.fromString("66666666-6666-4666-8666-666666666666"),
            DraftCommitBinding.Owner.WORLD_MANAGEMENT,
            "{}"));
    var units = new java.util.ArrayList<DraftCommitBinding.AffectedUnit>();
    units.add(
        new DraftCommitBinding.AffectedUnit(
            DraftCommitBinding.Owner.WORLD_MANAGEMENT,
            "WORLD_TEMPLATE",
            "world-1",
            "ROOM_SCOPE",
            "room-1",
            "7"));
    units.add(
        new DraftCommitBinding.AffectedUnit(
            DraftCommitBinding.Owner.WORLD_MANAGEMENT,
            "WORLD_TEMPLATE",
            "world-1",
            "ZONE_SCOPE",
            "zone-1",
            "19"));
    if (includeGameDesign) {
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              "1",
              UUID.fromString("77777777-7777-4777-8777-777777777777"),
              DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
              "{}"));
      units.add(
          new DraftCommitBinding.AffectedUnit(
              DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
              "DESIGN",
              "design-1",
              "REVISION",
              "revision-1",
              "0"));
    }
    var draft =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                tenant, version, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW"),
            request,
            commit,
            "base-1",
            revisions,
            units);
    return new DraftAuthorizationFenceBinding(
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            request,
            commit,
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            tenant,
            version,
            "base-1",
            "0",
            draft.canonicalBytes(),
            draft.canonicalBytes(),
            draft.digest(),
            java.util.List.of(
                new DraftAuthorizationFenceBinding.SourceEvidence(
                    DraftAuthorizationFenceBinding.SourceKind.GLOBAL_ROLES,
                    "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                    null,
                    "1",
                    null,
                    null,
                    new byte[] {1})))
        .canonicalBytes();
  }
}
