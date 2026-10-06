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

  @Test
  void exactCommittedCarrierRoundTripsAndRejectsStatusOwnerResultAndFullBindingSubstitution() {
    var request = request();
    var committed = committedReadback(request);
    var response = WorldDraftTerminalReadGrpcCodec.toResponse(request, Optional.of(committed));
    assertThat(response.getStatus())
        .isEqualTo(WorldDraftTerminalReadStatus.WORLD_DRAFT_TERMINAL_READ_STATUS_COMMITTED);
    assertThat(
            WorldDraftTerminalReadGrpcCodec.fromResponse(request, response)
                .ownerReadback()
                .orElseThrow()
                .canonicalBytes())
        .containsExactly(committed.canonicalBytes());
    assertThatThrownBy(
            () ->
                WorldDraftTerminalReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setStatus(
                            WorldDraftTerminalReadStatus
                                .WORLD_DRAFT_TERMINAL_READ_STATUS_DEFINITIVELY_ABORTED)
                        .build()))
        .hasMessageContaining("status differs");
    var abort =
        new DraftAuthorizationFenceBinding.OwnerReadback(
            Owner.WORLD,
            Outcome.DEFINITIVELY_ABORTED,
            committed.operationId(),
            committed.commitId(),
            committed.fenceId(),
            committed.inputDigest(),
            committed.fullBinding(),
            new byte[] {1});
    assertThatThrownBy(
            () ->
                WorldDraftTerminalReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setOwnerReadbackBytes(
                            com.google.protobuf.ByteString.copyFrom(abort.canonicalBytes()))
                        .build()))
        .hasMessageContaining("status differs");
    var wrongOwner =
        new DraftAuthorizationFenceBinding.OwnerReadback(
            Owner.GAME_DESIGN,
            Outcome.COMMITTED,
            committed.operationId(),
            committed.commitId(),
            committed.fenceId(),
            committed.inputDigest(),
            committed.fullBinding(),
            committed.result());
    assertThatThrownBy(
            () ->
                WorldDraftTerminalReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setOwnerReadbackBytes(
                            com.google.protobuf.ByteString.copyFrom(wrongOwner.canonicalBytes()))
                        .build()))
        .hasMessageContaining("World terminal response");
    var substituted =
        new DraftAuthorizationFenceBinding.OwnerReadback(
            Owner.WORLD,
            Outcome.COMMITTED,
            committed.operationId(),
            committed.commitId(),
            committed.fenceId(),
            committed.inputDigest(),
            committed.fullBinding(),
            new byte[] {1, 2, 3});
    assertThatThrownBy(
            () ->
                WorldDraftTerminalReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setOwnerReadbackBytes(
                            com.google.protobuf.ByteString.copyFrom(substituted.canonicalBytes()))
                        .build()))
        .hasMessageContaining("substituted canonical APPLIED result");
    assertThatThrownBy(
            () ->
                WorldDraftTerminalReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setOriginalAccountBinding(
                            com.google.protobuf.ByteString.copyFrom(new byte[] {9}))
                        .build()))
        .hasMessageContaining("changed the exact read request");
    assertThatThrownBy(
            () ->
                WorldDraftTerminalReadGrpcCodec.fromResponse(
                    request, response.toBuilder().clearOwnerReadbackBytes().build()))
        .hasMessageContaining("bytes are required");
  }

  @Test
  void committedCarrierRejectsEmbeddedOperationGraphDigestEpochAndUnknownFieldChanges()
      throws Exception {
    var request = request();
    var committed = committedReadback(request);
    var mapper = new tools.jackson.databind.ObjectMapper();
    for (String field :
        List.of("operationBytesBase64", "graphDigest", "appliedEpochs", "unknown")) {
      var json = (tools.jackson.databind.node.ObjectNode) mapper.readTree(committed.result());
      switch (field) {
        case "operationBytesBase64" ->
            json.put(field, java.util.Base64.getEncoder().encodeToString(new byte[] {1}));
        case "graphDigest" -> json.put(field, "sha256:" + "0".repeat(64));
        case "appliedEpochs" -> json.set(field, mapper.createArrayNode());
        default -> json.put(field, "unsupported");
      }
      var bytes =
          net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
              mapper.writeValueAsString(json));
      var changed =
          new DraftAuthorizationFenceBinding.OwnerReadback(
              Owner.WORLD,
              Outcome.COMMITTED,
              committed.operationId(),
              committed.commitId(),
              committed.fenceId(),
              committed.inputDigest(),
              committed.fullBinding(),
              bytes);
      assertThatThrownBy(
              () -> WorldDraftTerminalReadGrpcCodec.toResponse(request, Optional.of(changed)))
          .hasMessageContaining("substituted canonical APPLIED result");
    }
  }

  /**
   * Transport-only stipulated carrier; real persistence and six-family semantics are World proof.
   */
  static DraftAuthorizationFenceBinding.OwnerReadback committedReadback(
      WorldDraftTerminalReadEvidence.Request request) {
    try {
      var account = request.accountBinding();
      var draft =
          DraftCommitBinding.fromStored(
              new String(account.gameDesignBinding(), java.nio.charset.StandardCharsets.UTF_8),
              account.inputDigest());
      var operation = new java.io.ByteArrayOutputStream();
      var frames = new java.io.DataOutputStream(operation);
      java.util.function.Consumer<byte[]> frame =
          bytes -> {
            try {
              frames.writeInt(bytes.length);
              frames.write(bytes);
            } catch (java.io.IOException impossible) {
              throw new AssertionError(impossible);
            }
          };
      java.util.function.Consumer<String> text =
          value -> frame.accept(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      text.accept("world-draft-terminal-operation/v1");
      for (UUID id :
          List.of(
              account.operationId(),
              account.requestId(),
              account.commitId(),
              account.fenceId(),
              account.tenantId(),
              account.versionId())) text.accept(id.toString());
      frame.accept(draft.canonicalBytes());
      for (String value :
          List.of(
              request.targetNamespace(),
              account.tenantId().toString(),
              account.versionId().toString(),
              "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
              Long.toString(draft.target().gameDesignVersionRowId()),
              "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
              "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
              "a".repeat(64),
              "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
              "b".repeat(64),
              "c".repeat(64))) text.accept(value);
      text.accept(
          "sha256:"
              + java.util.HexFormat.of()
                  .formatHex(
                      java.security.MessageDigest.getInstance("SHA-256")
                          .digest(account.canonicalBytes())));
      frame.accept(account.canonicalBytes());
      byte[] graph =
          ("{\"schemaVersion\":\"2\",\"canonicalTenantId\":\""
                  + account.tenantId()
                  + "\",\"canonicalVersionId\":\""
                  + account.versionId()
                  + "\",\"rows\":[{}]}")
              .getBytes(java.nio.charset.StandardCharsets.UTF_8);
      var result = new java.util.LinkedHashMap<String, Object>();
      result.put("schema", "world-draft-graph-applied/v1");
      result.put("status", "APPLIED");
      result.put(
          "operationBytesBase64",
          java.util.Base64.getEncoder().encodeToString(operation.toByteArray()));
      result.put("graphBytesBase64", java.util.Base64.getEncoder().encodeToString(graph));
      result.put(
          "graphDigest",
          "sha256:"
              + java.util.HexFormat.of()
                  .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(graph)));
      result.put(
          "appliedEpochs",
          draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
              .map(
                  unit ->
                      java.util.Map.of(
                          "aggregateType",
                          unit.aggregateType(),
                          "aggregateId",
                          unit.aggregateId(),
                          "scopeType",
                          unit.scopeType(),
                          "scopeId",
                          unit.scopeId(),
                          "expectedEpoch",
                          unit.expectedEpoch(),
                          "resultingEpoch",
                          new java.math.BigInteger(unit.expectedEpoch())
                              .add(java.math.BigInteger.ONE)
                              .toString()))
              .toList());
      var bytes =
          net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
              new tools.jackson.databind.ObjectMapper().writeValueAsString(result));
      return new DraftAuthorizationFenceBinding.OwnerReadback(
          Owner.WORLD,
          Outcome.COMMITTED,
          account.operationId(),
          account.commitId(),
          account.fenceId(),
          account.inputDigest(),
          account.canonicalBytes(),
          bytes);
    } catch (java.io.IOException | java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
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
