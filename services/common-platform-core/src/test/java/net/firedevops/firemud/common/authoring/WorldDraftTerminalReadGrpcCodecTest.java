package net.firedevops.firemud.common.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldDraftTerminalOutcomeResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldDraftTerminalReadStatus;
import org.junit.jupiter.api.Test;

public class WorldDraftTerminalReadGrpcCodecTest {
  private static final UUID REQUEST_ID = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID COMMIT_ID = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID TENANT_ID = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION_ID = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID REVISION_ID = uuid("66666666-6666-4666-8666-666666666666");
  private static final tools.jackson.databind.ObjectMapper JSON =
      new tools.jackson.databind.ObjectMapper();

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
  void independentlyDecodedByteEvidenceUsesCanonicalValueEquality() {
    var request = request();
    var binding = request.accountBinding();
    var decodedBinding = DraftAuthorizationFenceBinding.fromStored(binding.canonicalBytes());
    assertThat(decodedBinding).isEqualTo(binding);
    assertThat(decodedBinding.hashCode()).isEqualTo(binding.hashCode());
    assertThat(new HashSet<>(List.of(binding, decodedBinding))).hasSize(1);

    var source = binding.sources().getFirst();
    var decodedSource = SourceEvidence.fromStored(source.canonicalBytes());
    assertThat(decodedSource).isEqualTo(source);
    assertThat(decodedSource.hashCode()).isEqualTo(source.hashCode());
    assertThat(new HashSet<>(List.of(source, decodedSource))).hasSize(1);

    var changedSource =
        new SourceEvidence(
            source.kind(),
            source.scopeId(),
            source.generation(),
            source.sourceVersion(),
            source.checkpointStream(),
            source.checkpointSequence(),
            new byte[] {9});
    assertThat(changedSource).isNotEqualTo(source);
    var changedSourceBinding =
        new DraftAuthorizationFenceBinding(
            binding.operationId(),
            binding.requestId(),
            binding.commitId(),
            binding.fenceId(),
            binding.actorAccountId(),
            binding.tenantId(),
            binding.versionId(),
            binding.baseCommitId(),
            binding.expectedDraftEpoch(),
            binding.gameDesignBinding(),
            binding.normalizedInput(),
            binding.inputDigest(),
            List.of(changedSource),
            binding.schemaVersion(),
            binding.requiredOwners());
    assertThat(changedSourceBinding).isNotEqualTo(binding);

    var readback = AuthoringFixtures.committedReadback(request);
    var decodedReadback =
        DraftAuthorizationFenceBinding.OwnerReadback.fromStored(readback.canonicalBytes());
    assertThat(decodedReadback).isEqualTo(readback);
    assertThat(decodedReadback.hashCode()).isEqualTo(readback.hashCode());
    assertThat(new HashSet<>(List.of(readback, decodedReadback))).hasSize(1);
    assertThat(
            DraftAuthorizationFenceBinding.OwnerReadback.fromStored(
                new DraftAuthorizationFenceBinding.OwnerReadback(
                        Owner.WORLD,
                        Outcome.COMMITTED,
                        readback.operationId(),
                        readback.commitId(),
                        readback.fenceId(),
                        readback.inputDigest(),
                        readback.fullBinding(),
                        new byte[] {9})
                    .canonicalBytes()))
        .isNotEqualTo(readback);
  }

  @Test
  void unknownIsExplicitAndCarriesNoOwnerResult() {
    var request = request();
    var response = WorldDraftTerminalReadGrpcCodec.toResponse(request, Optional.empty());

    var evidence = WorldDraftTerminalReadGrpcCodec.fromResponse(request, response);

    assertThat(evidence.request()).isEqualTo(request);
    assertThat(evidence.ownerReadback()).isEmpty();
    assertThatThrownBy(evidence::appliedIntakeRequestId)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("committed terminal readback");
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
    assertThatThrownBy(evidence::appliedIntakeRequestId)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("committed terminal readback");
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
    var committed = AuthoringFixtures.committedReadback(request);
    var response = WorldDraftTerminalReadGrpcCodec.toResponse(request, Optional.of(committed));
    assertThat(response.getStatus())
        .isEqualTo(WorldDraftTerminalReadStatus.WORLD_DRAFT_TERMINAL_READ_STATUS_COMMITTED);
    var evidence = WorldDraftTerminalReadGrpcCodec.fromResponse(request, response);
    assertThat(evidence.ownerReadback().orElseThrow().canonicalBytes())
        .containsExactly(committed.canonicalBytes());
    assertThat(evidence.appliedIntakeRequestId())
        .isEqualTo(uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));
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
    var committed = AuthoringFixtures.committedReadback(request);
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

  @Test
  void exactDeclaredV2CarrierRoundTripsWithItsOriginalRoomAndCompleteGraphDeclaration()
      throws Exception {
    var request = AuthoringFixtures.freshGraphRequest();
    var committed = AuthoringFixtures.committedFreshGraphReadback(request);
    byte[] graphBytes =
        Base64.getDecoder().decode(object(committed.result()).get("graphBytesBase64").textValue());
    assertThat(java.util.Arrays.equals(graphBytes, canonical(JSON.readTree(graphBytes)))).isFalse();
    var response = WorldDraftTerminalReadGrpcCodec.toResponse(request, Optional.of(committed));

    assertThat(response.getStatus())
        .isEqualTo(WorldDraftTerminalReadStatus.WORLD_DRAFT_TERMINAL_READ_STATUS_COMMITTED);
    assertThat(
            WorldDraftTerminalReadGrpcCodec.fromResponse(request, response)
                .ownerReadback()
                .orElseThrow()
                .canonicalBytes())
        .containsExactly(committed.canonicalBytes());
  }

  @Test
  void v2RejectsMalformedOrDigestSubstitutedOriginalOwnerGraphBytes() throws Exception {
    var request = AuthoringFixtures.freshGraphRequest();
    var committed = AuthoringFixtures.committedFreshGraphReadback(request);
    var result = object(committed.result());
    result.put("graphDigest", "sha256:" + "0".repeat(64));
    assertResultRejected(request, committed, canonical(result));

    result = object(committed.result());
    byte[] malformedGraph = Base64.getDecoder().decode(result.get("graphBytesBase64").textValue());
    malformedGraph[0] = (byte) 0xff;
    String graphDigest = sha256(malformedGraph);
    result.put("graphBytesBase64", Base64.getEncoder().encodeToString(malformedGraph));
    result.put("graphDigest", graphDigest);
    var draft =
        DraftCommitBinding.fromStored(
            new String(request.accountBinding().gameDesignBinding(), StandardCharsets.UTF_8),
            request.accountBinding().inputDigest());
    var changedReceipt = AuthoringFixtures.receipt(request, draft, graphDigest);
    byte[] changedReceiptBytes = canonical(changedReceipt);
    result.put(
        "startLocationReceiptBase64", Base64.getEncoder().encodeToString(changedReceiptBytes));
    result.put("startLocationReceiptDigest", changedReceipt.get("receiptDigest").textValue());
    assertResultRejected(request, committed, canonical(result));
  }

  @Test
  void v2ReceiptRejectsChangedSelectorScopeRequestCommitFenceAndDigests() throws Exception {
    var request = AuthoringFixtures.freshGraphRequest();
    var committed = AuthoringFixtures.committedFreshGraphReadback(request);
    for (java.util.function.Consumer<tools.jackson.databind.node.ObjectNode> change :
        List.<java.util.function.Consumer<tools.jackson.databind.node.ObjectNode>>of(
            receipt ->
                selector(receipt)
                    .put("roomTemplateId", uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").toString()),
            receipt ->
                selector(receipt)
                    .put("tenantId", uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").toString()),
            receipt ->
                selector(receipt)
                    .put("versionId", uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").toString()),
            receipt ->
                receipt.put("requestId", uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").toString()),
            receipt ->
                receipt.put("commitId", uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").toString()),
            receipt ->
                receipt.put(
                    "authorizationFenceId",
                    uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").toString()),
            receipt -> receipt.put("accountBindingDigest", "sha256:" + "0".repeat(64)),
            receipt -> receipt.put("bindingDigest", "sha256:" + "0".repeat(64)),
            receipt -> receipt.put("graphDigest", "sha256:" + "0".repeat(64)),
            receipt -> receipt.put("receiptDigest", "sha256:" + "0".repeat(64)))) {
      assertReceiptChangeRejected(request, committed, change);
    }
    assertReceiptChangeRejected(
        request, committed, receipt -> receipt.put("unsupported", "new-field"));
    var changedTopLevel = object(committed.result());
    changedTopLevel.put("unsupported", "new-field");
    assertResultRejected(request, committed, canonical(changedTopLevel));
    changedTopLevel = object(committed.result());
    changedTopLevel.put("startLocationReceiptDigest", "sha256:" + "0".repeat(64));
    assertResultRejected(request, committed, canonical(changedTopLevel));
  }

  @Test
  void v2RejectsNoncanonicalReceiptBytesAndGraphWithoutTheSelectedRoomRow() throws Exception {
    var request = AuthoringFixtures.freshGraphRequest();
    var committed = AuthoringFixtures.committedFreshGraphReadback(request);
    var result = object(committed.result());
    byte[] receiptBytes =
        Base64.getDecoder().decode(result.get("startLocationReceiptBase64").textValue());
    String receiptJson = new String(receiptBytes, StandardCharsets.UTF_8);
    result.put(
        "startLocationReceiptBase64",
        Base64.getEncoder().encodeToString((" " + receiptJson).getBytes(StandardCharsets.UTF_8)));
    assertResultRejected(request, committed, canonical(result));

    result = object(committed.result());
    byte[] graphBytes = Base64.getDecoder().decode(result.get("graphBytesBase64").textValue());
    var graph = object(graphBytes);
    var rows = (tools.jackson.databind.node.ArrayNode) graph.get("rows");
    for (tools.jackson.databind.JsonNode row : rows) {
      if ("ROOM".equals(row.get("mapping").get("family").textValue())) {
        ((tools.jackson.databind.node.ObjectNode) row.get("mapping"))
            .put("template_id", uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").toString());
      }
    }
    byte[] changedGraph = canonical(graph);
    String graphDigest = sha256(changedGraph);
    result.put("graphBytesBase64", Base64.getEncoder().encodeToString(changedGraph));
    result.put("graphDigest", graphDigest);
    var binding = request.accountBinding();
    var commitBinding =
        DraftCommitBinding.fromStored(
            new String(binding.gameDesignBinding(), StandardCharsets.UTF_8), binding.inputDigest());
    var changedReceipt = AuthoringFixtures.receipt(request, commitBinding, graphDigest);
    byte[] changedReceiptBytes = canonical(JSON.valueToTree(changedReceipt));
    result.put(
        "startLocationReceiptBase64", Base64.getEncoder().encodeToString(changedReceiptBytes));
    result.put("startLocationReceiptDigest", changedReceipt.get("receiptDigest").textValue());
    assertResultRejected(request, committed, canonical(result));
  }

  @Test
  void declaredV2CannotDowngradeToV1AndV1CannotClaimUndeclaredHistoryAsV2() throws Exception {
    var declaredRequest = AuthoringFixtures.freshGraphRequest();
    var declaredResult = AuthoringFixtures.committedFreshGraphReadback(declaredRequest);
    var downgraded = object(declaredResult.result());
    downgraded.put("schema", "world-draft-graph-applied/v1");
    downgraded.remove("startLocationReceiptBase64");
    downgraded.remove("startLocationReceiptDigest");
    assertResultRejected(declaredRequest, declaredResult, canonical(downgraded));

    var historicalRequest = request();
    var historicalResult = AuthoringFixtures.committedReadback(historicalRequest);
    var upgraded = object(historicalResult.result());
    upgraded.put("schema", "world-draft-graph-applied/v2");
    upgraded.put(
        "startLocationReceiptBase64",
        Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8)));
    upgraded.put("startLocationReceiptDigest", "sha256:" + "0".repeat(64));
    assertResultRejected(historicalRequest, historicalResult, canonical(upgraded));
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
    return AuthoringFixtures.accountBinding(draft);
  }

  private static tools.jackson.databind.node.ObjectNode selector(
      tools.jackson.databind.node.ObjectNode receipt) {
    return (tools.jackson.databind.node.ObjectNode) receipt.get("startLocation");
  }

  private static void assertReceiptChangeRejected(
      WorldDraftTerminalReadEvidence.Request request,
      DraftAuthorizationFenceBinding.OwnerReadback committed,
      java.util.function.Consumer<tools.jackson.databind.node.ObjectNode> change)
      throws Exception {
    var result = object(committed.result());
    byte[] bytes = Base64.getDecoder().decode(result.get("startLocationReceiptBase64").textValue());
    var receipt = object(bytes);
    change.accept(receipt);
    result.put(
        "startLocationReceiptBase64", Base64.getEncoder().encodeToString(canonical(receipt)));
    assertResultRejected(request, committed, canonical(result));
  }

  private static void assertResultRejected(
      WorldDraftTerminalReadEvidence.Request request,
      DraftAuthorizationFenceBinding.OwnerReadback committed,
      byte[] resultBytes) {
    var baseline = WorldDraftTerminalReadGrpcCodec.toResponse(request, Optional.of(committed));
    var substituted = readbackWithResult(committed, resultBytes);
    var response =
        baseline.toBuilder()
            .setOwnerReadbackBytes(
                com.google.protobuf.ByteString.copyFrom(substituted.canonicalBytes()))
            .build();
    assertThatThrownBy(() -> WorldDraftTerminalReadGrpcCodec.fromResponse(request, response))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static DraftAuthorizationFenceBinding.OwnerReadback readbackWithResult(
      DraftAuthorizationFenceBinding.OwnerReadback original, byte[] result) {
    return new DraftAuthorizationFenceBinding.OwnerReadback(
        Owner.WORLD,
        Outcome.COMMITTED,
        original.operationId(),
        original.commitId(),
        original.fenceId(),
        original.inputDigest(),
        original.fullBinding(),
        result);
  }

  private static tools.jackson.databind.node.ObjectNode object(byte[] bytes) throws Exception {
    return (tools.jackson.databind.node.ObjectNode) JSON.readTree(bytes);
  }

  private static byte[] canonical(tools.jackson.databind.JsonNode value) throws Exception {
    return net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
        JSON.writeValueAsString(value));
  }

  private static String sha256(byte[] bytes) throws Exception {
    return "sha256:"
        + java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
