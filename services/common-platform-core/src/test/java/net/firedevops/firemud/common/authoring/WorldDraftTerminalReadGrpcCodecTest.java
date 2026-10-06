package net.firedevops.firemud.common.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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

public class WorldDraftTerminalReadGrpcCodecTest {
  private static final UUID OPERATION_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID REQUEST_ID = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID COMMIT_ID = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID FENCE_ID = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID ACTOR_ID = uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final UUID TENANT_ID = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION_ID = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID REVISION_ID = uuid("66666666-6666-4666-8666-666666666666");
  private static final UUID REGION_TEMPLATE_ID = uuid("88888888-8888-4888-8888-888888888888");
  private static final UUID ZONE_TEMPLATE_ID = uuid("99999999-9999-4999-8999-999999999999");
  private static final UUID ROOM_TEMPLATE_ID = uuid("77777777-7777-4777-8777-777777777777");
  private static final UUID REGION_REVISION_ID = uuid("12345678-1234-4234-8234-123456789001");
  private static final UUID ZONE_REVISION_ID = uuid("12345678-1234-4234-8234-123456789002");
  private static final UUID ROOM_REVISION_ID = uuid("12345678-1234-4234-8234-123456789003");
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

  @Test
  void exactDeclaredV2CarrierRoundTripsWithItsOriginalRoomAndCompleteGraphDeclaration()
      throws Exception {
    var request = freshGraphRequest();
    var committed = committedReadback(request, true);
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
    var request = freshGraphRequest();
    var committed = committedReadback(request, true);
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
    var changedReceipt = receipt(request, draft, graphDigest);
    byte[] changedReceiptBytes = canonical(changedReceipt);
    result.put(
        "startLocationReceiptBase64", Base64.getEncoder().encodeToString(changedReceiptBytes));
    result.put("startLocationReceiptDigest", changedReceipt.get("receiptDigest").textValue());
    assertResultRejected(request, committed, canonical(result));
  }

  @Test
  void v2ReceiptRejectsChangedSelectorScopeRequestCommitFenceAndDigests() throws Exception {
    var request = freshGraphRequest();
    var committed = committedReadback(request, true);
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
    var request = freshGraphRequest();
    var committed = committedReadback(request, true);
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
    var changedReceipt = receipt(request, commitBinding, graphDigest);
    byte[] changedReceiptBytes = canonical(JSON.valueToTree(changedReceipt));
    result.put(
        "startLocationReceiptBase64", Base64.getEncoder().encodeToString(changedReceiptBytes));
    result.put("startLocationReceiptDigest", changedReceipt.get("receiptDigest").textValue());
    assertResultRejected(request, committed, canonical(result));
  }

  @Test
  void declaredV2CannotDowngradeToV1AndV1CannotClaimUndeclaredHistoryAsV2() throws Exception {
    var declaredRequest = freshGraphRequest();
    var declaredResult = committedReadback(declaredRequest, true);
    var downgraded = object(declaredResult.result());
    downgraded.put("schema", "world-draft-graph-applied/v1");
    downgraded.remove("startLocationReceiptBase64");
    downgraded.remove("startLocationReceiptDigest");
    assertResultRejected(declaredRequest, declaredResult, canonical(downgraded));

    var historicalRequest = request();
    var historicalResult = committedReadback(historicalRequest);
    var upgraded = object(historicalResult.result());
    upgraded.put("schema", "world-draft-graph-applied/v2");
    upgraded.put(
        "startLocationReceiptBase64",
        Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8)));
    upgraded.put("startLocationReceiptDigest", "sha256:" + "0".repeat(64));
    assertResultRejected(historicalRequest, historicalResult, canonical(upgraded));
  }

  /**
   * Transport-only stipulated carrier; real persistence and six-family semantics are World proof.
   */
  static DraftAuthorizationFenceBinding.OwnerReadback committedReadback(
      WorldDraftTerminalReadEvidence.Request request) {
    try {
      return committedReadback(request, false);
    } catch (Exception impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static DraftAuthorizationFenceBinding.OwnerReadback committedReadback(
      WorldDraftTerminalReadEvidence.Request request, boolean freshGraph) throws Exception {
    var account = request.accountBinding();
    var draft =
        DraftCommitBinding.fromStored(
            new String(account.gameDesignBinding(), StandardCharsets.UTF_8), account.inputDigest());
    var operation = new ByteArrayOutputStream();
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
        value -> frame.accept(value.getBytes(StandardCharsets.UTF_8));
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
    text.accept(sha256(account.canonicalBytes()));
    frame.accept(account.canonicalBytes());
    byte[] graph = freshGraph ? graphBytes(request, draft) : historicalGraphBytes(account);
    String graphDigest = sha256(graph);
    var result = new java.util.LinkedHashMap<String, Object>();
    result.put(
        "schema", freshGraph ? "world-draft-graph-applied/v2" : "world-draft-graph-applied/v1");
    result.put("status", "APPLIED");
    result.put("operationBytesBase64", Base64.getEncoder().encodeToString(operation.toByteArray()));
    result.put("graphBytesBase64", Base64.getEncoder().encodeToString(graph));
    result.put("graphDigest", graphDigest);
    if (freshGraph) {
      var receipt = receipt(request, draft, graphDigest);
      byte[] receiptBytes = canonical(receipt);
      result.put("startLocationReceiptBase64", Base64.getEncoder().encodeToString(receiptBytes));
      result.put("startLocationReceiptDigest", receipt.get("receiptDigest").textValue());
    }
    result.put(
        "appliedEpochs",
        draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    Map.of(
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
    return new DraftAuthorizationFenceBinding.OwnerReadback(
        Owner.WORLD,
        Outcome.COMMITTED,
        account.operationId(),
        account.commitId(),
        account.fenceId(),
        account.inputDigest(),
        account.canonicalBytes(),
        canonical(JSON.valueToTree(result)));
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
    return accountBinding(draft);
  }

  /** Test-only exact fresh graph request shared with the published selector carrier proof. */
  public static WorldDraftTerminalReadEvidence.Request
      freshGraphRequestForStartLocationEvidenceTest() throws Exception {
    return freshGraphRequest();
  }

  /** Test-only complete World APPLIED-v2 readback for the exact fresh graph request. */
  public static DraftAuthorizationFenceBinding.OwnerReadback
      committedFreshGraphReadbackForStartLocationEvidenceTest(
          WorldDraftTerminalReadEvidence.Request request) {
    try {
      return committedReadback(request, true);
    } catch (Exception impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static WorldDraftTerminalReadEvidence.Request freshGraphRequest() throws Exception {
    DraftCommitBinding draft = freshGraphBinding();
    return new WorldDraftTerminalReadEvidence.Request(
        1, "test", uuid("33333333-3333-4333-8333-333333333333"), accountBinding(draft));
  }

  private static DraftCommitBinding freshGraphBinding() throws Exception {
    String declaration =
        JSON.writeValueAsString(
            Map.of(
                "tenantId", TENANT_ID.toString(),
                "versionId", VERSION_ID.toString(),
                "startLocation",
                    Map.of(
                        "tenantId", TENANT_ID.toString(),
                        "versionId", VERSION_ID.toString(),
                        "roomTemplateId", ROOM_TEMPLATE_ID.toString()),
                "familyCounts",
                    List.of(
                        Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_REGION", "count", 1),
                        Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_ZONE", "count", 1),
                        Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_ROOM", "count", 1),
                        Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT", "count", 0),
                        Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE", "count", 0),
                        Map.of(
                            "family",
                            "WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING",
                            "count",
                            0))));
    return DraftCommitBinding.create(
        new TargetProof(
            TENANT_ID, VERSION_ID, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW"),
        REQUEST_ID,
        COMMIT_ID,
        "base-1",
        List.of(
            new RevisionPayload(
                "0",
                REGION_REVISION_ID,
                DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                worldRevisionPayload(
                    REGION_REVISION_ID,
                    "WORLD_DESIGN_AGGREGATE_TYPE_REGION",
                    REGION_TEMPLATE_ID,
                    declaration)),
            new RevisionPayload(
                "1",
                ZONE_REVISION_ID,
                DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                worldRevisionPayload(
                    ZONE_REVISION_ID, "WORLD_DESIGN_AGGREGATE_TYPE_ZONE", ZONE_TEMPLATE_ID, null)),
            new RevisionPayload(
                "2",
                ROOM_REVISION_ID,
                DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                worldRevisionPayload(
                    ROOM_REVISION_ID, "WORLD_DESIGN_AGGREGATE_TYPE_ROOM", ROOM_TEMPLATE_ID, null))),
        List.of(
                affected("REGION", REGION_TEMPLATE_ID, "REGION_SUBTREE", REGION_TEMPLATE_ID),
                affected("ZONE", ZONE_TEMPLATE_ID, "REGION_SUBTREE", REGION_TEMPLATE_ID),
                affected("ROOM", ROOM_TEMPLATE_ID, "ZONE_SUBTREE", ZONE_TEMPLATE_ID))
            .stream()
            .flatMap(List::stream)
            .toList());
  }

  private static String worldRevisionPayload(
      UUID revisionId, String family, UUID templateId, String declaration) throws Exception {
    var payload = new java.util.LinkedHashMap<String, Object>();
    payload.put("logicalRevisionId", revisionId.toString());
    payload.put("commitId", COMMIT_ID.toString());
    payload.put("aggregateType", family);
    payload.put("aggregateId", templateId.toString());
    if (declaration != null) payload.put("freshGraphDeclaration", JSON.readTree(declaration));
    return JSON.writeValueAsString(payload);
  }

  private static List<AffectedUnit> affected(
      String family, UUID templateId, String scopeType, UUID scopeId) {
    return List.of(
        new AffectedUnit(
            DraftCommitBinding.Owner.WORLD_MANAGEMENT,
            family,
            templateId.toString(),
            "AGGREGATE",
            templateId.toString(),
            "0"),
        new AffectedUnit(
            DraftCommitBinding.Owner.WORLD_MANAGEMENT,
            family,
            templateId.toString(),
            scopeType,
            scopeId.toString(),
            "0"));
  }

  private static byte[] accountBinding(DraftCommitBinding draft) {
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

  private static byte[] historicalGraphBytes(DraftAuthorizationFenceBinding binding) {
    return ("{\"schemaVersion\":\"2\",\"canonicalTenantId\":\""
            + binding.tenantId()
            + "\",\"canonicalVersionId\":\""
            + binding.versionId()
            + "\",\"rows\":[{}]}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] graphBytes(
      WorldDraftTerminalReadEvidence.Request request, DraftCommitBinding draft) throws Exception {
    var root = new java.util.LinkedHashMap<String, Object>();
    root.put("schemaVersion", "2");
    root.put("canonicalTenantId", draft.target().canonicalTenantId().toString());
    root.put("canonicalVersionId", draft.target().canonicalVersionId().toString());
    var rows = new java.util.ArrayList<Map<String, Object>>();
    int mappingId = 1;
    long privateRowKey = 101;
    for (RevisionPayload revision : draft.revisions()) {
      if (revision.owner() != DraftCommitBinding.Owner.WORLD_MANAGEMENT) continue;
      var mutation = JSON.readTree(revision.payload());
      String family =
          mutation.get("aggregateType").textValue().replace("WORLD_DESIGN_AGGREGATE_TYPE_", "");
      var mapping = new java.util.LinkedHashMap<String, Object>();
      mapping.put("id", mappingId++);
      mapping.put("target_namespace", request.targetNamespace());
      mapping.put("canonical_tenant_id", draft.target().canonicalTenantId().toString());
      mapping.put("canonical_version_id", draft.target().canonicalVersionId().toString());
      mapping.put("family", family);
      mapping.put("template_id", mutation.get("aggregateId").textValue());
      mapping.put("private_row_key", privateRowKey++);
      mapping.put("tenant_id", 11);
      mapping.put("version_id", 19);
      mapping.put("version_identity_operation_id", OPERATION_ID.toString());
      mapping.put("request_id", draft.requestId().toString());
      mapping.put("commit_id", draft.commitId().toString());
      mapping.put("revision_id", revision.revisionId().toString());
      mapping.put("revision_order", revision.revisionOrder());
      rows.add(Map.of("mapping", mapping, "content", Map.of()));
    }
    root.put("rows", rows);
    return JSON.writeValueAsBytes(root);
  }

  private static tools.jackson.databind.node.ObjectNode receipt(
      WorldDraftTerminalReadEvidence.Request request, DraftCommitBinding draft, String graphDigest)
      throws Exception {
    var account = request.accountBinding();
    var selector =
        Map.of(
            "tenantId", TENANT_ID.toString(),
            "versionId", VERSION_ID.toString(),
            "roomTemplateId", ROOM_TEMPLATE_ID.toString());
    String accountBindingDigest = sha256(request.originalAccountBinding());
    String receiptDigest =
        startLocationReceiptDigest(
            request.targetNamespace(),
            account.operationId(),
            account.requestId(),
            account.commitId(),
            account.fenceId(),
            accountBindingDigest,
            draft.digest(),
            TENANT_ID,
            VERSION_ID,
            ROOM_TEMPLATE_ID,
            graphDigest);
    var value = new java.util.LinkedHashMap<String, Object>();
    value.put("schema", "world-draft-start-location-receipt/v1");
    value.put("targetNamespace", request.targetNamespace());
    value.put("operationId", account.operationId().toString());
    value.put("requestId", account.requestId().toString());
    value.put("commitId", account.commitId().toString());
    value.put("authorizationFenceId", account.fenceId().toString());
    value.put("accountBindingDigest", accountBindingDigest);
    value.put("bindingDigest", draft.digest());
    value.put("startLocation", selector);
    value.put("graphDigest", graphDigest);
    value.put("receiptDigest", receiptDigest);
    return (tools.jackson.databind.node.ObjectNode) JSON.valueToTree(value);
  }

  private static String startLocationReceiptDigest(
      String targetNamespace,
      UUID operationId,
      UUID requestId,
      UUID commitId,
      UUID fenceId,
      String accountBindingDigest,
      String bindingDigest,
      UUID tenantId,
      UUID versionId,
      UUID roomTemplateId,
      String graphDigest)
      throws Exception {
    var framed = new ByteArrayOutputStream();
    for (String value :
        List.of(
            "world-draft-start-location-receipt/v1",
            targetNamespace,
            operationId.toString(),
            requestId.toString(),
            commitId.toString(),
            fenceId.toString(),
            accountBindingDigest,
            bindingDigest,
            tenantId.toString(),
            versionId.toString(),
            roomTemplateId.toString(),
            graphDigest)) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      framed.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
      framed.writeBytes(bytes);
    }
    return sha256(framed.toByteArray());
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
