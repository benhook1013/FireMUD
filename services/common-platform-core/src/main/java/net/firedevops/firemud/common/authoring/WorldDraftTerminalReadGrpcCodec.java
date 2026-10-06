package net.firedevops.firemud.common.authoring;

import com.google.protobuf.Message;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldDraftTerminalOutcomeRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldDraftTerminalOutcomeResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldDraftTerminalReadStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Closed request mapping and exact echo/outcome validation for authenticated World readback. */
public final class WorldDraftTerminalReadGrpcCodec {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String APPLIED_V1 = "world-draft-graph-applied/v1";
  private static final String APPLIED_V2 = "world-draft-graph-applied/v2";
  private static final String START_LOCATION_RECEIPT_V1 = "world-draft-start-location-receipt/v1";
  private static final java.util.List<String> FRESH_GRAPH_FAMILIES =
      java.util.List.of(
          "WORLD_DESIGN_AGGREGATE_TYPE_REGION",
          "WORLD_DESIGN_AGGREGATE_TYPE_ZONE",
          "WORLD_DESIGN_AGGREGATE_TYPE_ROOM",
          "WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT",
          "WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE",
          "WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING");
  private static final Set<String> APPLIED_V1_FIELDS =
      Set.of(
          "schema",
          "status",
          "operationBytesBase64",
          "graphBytesBase64",
          "graphDigest",
          "appliedEpochs");
  private static final Set<String> APPLIED_V2_FIELDS =
      Set.of(
          "schema",
          "status",
          "operationBytesBase64",
          "graphBytesBase64",
          "graphDigest",
          "startLocationReceiptBase64",
          "startLocationReceiptDigest",
          "appliedEpochs");
  private static final Set<String> FRESH_GRAPH_DECLARATION_FIELDS =
      Set.of("tenantId", "versionId", "startLocation", "familyCounts");
  private static final Set<String> ROOM_TEMPLATE_REF_FIELDS =
      Set.of("tenantId", "versionId", "roomTemplateId");
  private static final Set<String> FAMILY_COUNT_FIELDS = Set.of("family", "count");
  private static final Set<String> GRAPH_ROW_FIELDS = Set.of("mapping", "content");
  private static final Set<String> GRAPH_MAPPING_FIELDS =
      Set.of(
          "id",
          "target_namespace",
          "canonical_tenant_id",
          "canonical_version_id",
          "family",
          "template_id",
          "private_row_key",
          "tenant_id",
          "version_id",
          "version_identity_operation_id",
          "request_id",
          "commit_id",
          "revision_id",
          "revision_order");
  private static final Set<String> WORLD_MUTATION_FIELDS =
      Set.of(
          "logicalRevisionId",
          "commitId",
          "operation",
          "aggregateType",
          "aggregateId",
          "expectedDraftRevisionEpoch",
          "scopeType",
          "scopeId",
          "expectedDraftScopeRevisionEpoch",
          "scopeMutationPolicy",
          "freshGraphDeclaration",
          "region",
          "zone",
          "room",
          "roomExit",
          "generationRule",
          "worldEntitySpawnBinding",
          "worldGenerationSubtree");
  private static final tools.jackson.databind.ObjectMapper JSON =
      JsonMapper.builder()
          .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private WorldDraftTerminalReadGrpcCodec() {}

  public static ReadWorldDraftTerminalOutcomeRequest toRequest(
      WorldDraftTerminalReadEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    return ReadWorldDraftTerminalOutcomeRequest.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setReadRequestId(request.readRequestId().toString())
        .setOriginalAccountBinding(
            com.google.protobuf.ByteString.copyFrom(request.originalAccountBinding()))
        .build();
  }

  /** Decodes only after the receiver has authenticated its exact same-namespace Account peer. */
  public static WorldDraftTerminalReadEvidence.Request fromRequest(
      ReadWorldDraftTerminalOutcomeRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoUnknownFields(request, "ReadWorldDraftTerminalOutcomeRequest");
    try {
      return new WorldDraftTerminalReadEvidence.Request(
          request.getSchemaVersion(),
          request.getTargetNamespace(),
          parseCanonicalNonNilUuid(request.getReadRequestId(), "readRequestId"),
          request.getOriginalAccountBinding().toByteArray());
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("World terminal read request is invalid", exception);
    }
  }

  /** Emits only UNKNOWN or exact canonical World committed/definitive-abort readback. */
  public static ReadWorldDraftTerminalOutcomeResponse toResponse(
      WorldDraftTerminalReadEvidence.Request request,
      Optional<DraftAuthorizationFenceBinding.OwnerReadback> ownerReadback) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(ownerReadback, "ownerReadback");
    var builder =
        ReadWorldDraftTerminalOutcomeResponse.newBuilder()
            .setSchemaVersion(request.schemaVersion())
            .setTargetNamespace(request.targetNamespace())
            .setReadRequestId(request.readRequestId().toString())
            .setOriginalAccountBinding(
                com.google.protobuf.ByteString.copyFrom(request.originalAccountBinding()));
    if (ownerReadback.isEmpty()) {
      return builder
          .setStatus(WorldDraftTerminalReadStatus.WORLD_DRAFT_TERMINAL_READ_STATUS_UNKNOWN)
          .build();
    }
    var readback = ownerReadback.orElseThrow();
    requireExactOwnerReadback(request, readback);
    return builder
        .setStatus(
            readback.outcome() == Outcome.COMMITTED
                ? WorldDraftTerminalReadStatus.WORLD_DRAFT_TERMINAL_READ_STATUS_COMMITTED
                : WorldDraftTerminalReadStatus
                    .WORLD_DRAFT_TERMINAL_READ_STATUS_DEFINITIVELY_ABORTED)
        .setOwnerReadbackBytes(com.google.protobuf.ByteString.copyFrom(readback.canonicalBytes()))
        .build();
  }

  /** Validates the complete echo and independently framed World owner result. */
  public static WorldDraftTerminalReadEvidence fromResponse(
      WorldDraftTerminalReadEvidence.Request request,
      ReadWorldDraftTerminalOutcomeResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "ReadWorldDraftTerminalOutcomeResponse");
    if (response.getSchemaVersion() != request.schemaVersion()
        || !response.getTargetNamespace().equals(request.targetNamespace())
        || !response.getReadRequestId().equals(request.readRequestId().toString())
        || !Arrays.equals(
            response.getOriginalAccountBinding().toByteArray(), request.originalAccountBinding())) {
      throw new IllegalArgumentException("World terminal response changed the exact read request");
    }
    Optional<DraftAuthorizationFenceBinding.OwnerReadback> readback;
    switch (response.getStatus()) {
      case WORLD_DRAFT_TERMINAL_READ_STATUS_UNKNOWN -> {
        if (!response.getOwnerReadbackBytes().isEmpty()) {
          throw new IllegalArgumentException("UNKNOWN World readback cannot carry a result");
        }
        readback = Optional.empty();
      }
      case WORLD_DRAFT_TERMINAL_READ_STATUS_DEFINITIVELY_ABORTED,
          WORLD_DRAFT_TERMINAL_READ_STATUS_COMMITTED -> {
        if (response.getOwnerReadbackBytes().isEmpty()) {
          throw new IllegalArgumentException("World terminal readback bytes are required");
        }
        var decoded =
            DraftAuthorizationFenceBinding.OwnerReadback.fromStored(
                response.getOwnerReadbackBytes().toByteArray());
        requireExactOwnerReadback(request, decoded);
        Outcome expected =
            response.getStatus()
                    == WorldDraftTerminalReadStatus.WORLD_DRAFT_TERMINAL_READ_STATUS_COMMITTED
                ? Outcome.COMMITTED
                : Outcome.DEFINITIVELY_ABORTED;
        if (decoded.outcome() != expected) {
          throw new IllegalArgumentException(
              "World terminal status differs from canonical owner outcome");
        }
        readback = Optional.of(decoded);
      }
      case WORLD_DRAFT_TERMINAL_READ_STATUS_UNSPECIFIED, UNRECOGNIZED ->
          throw new IllegalArgumentException("Unsupported World terminal read status");
      default -> throw new IllegalArgumentException("Unsupported World terminal read status");
    }
    return new WorldDraftTerminalReadEvidence(request, readback);
  }

  private static void requireExactOwnerReadback(
      WorldDraftTerminalReadEvidence.Request request,
      DraftAuthorizationFenceBinding.OwnerReadback readback) {
    DraftAuthorizationFenceBinding binding = request.accountBinding();
    if (readback.owner() != Owner.WORLD
        || (readback.outcome() != Outcome.DEFINITIVELY_ABORTED
            && readback.outcome() != Outcome.COMMITTED)) {
      throw new IllegalArgumentException(
          "World terminal response is not an exact World terminal outcome");
    }
    readback.requireBinding(binding);
    if (!readback.operationId().equals(binding.operationId())
        || !readback.commitId().equals(binding.commitId())
        || !readback.fenceId().equals(binding.fenceId())
        || !readback.inputDigest().equals(binding.inputDigest())
        || !Arrays.equals(readback.fullBinding(), request.originalAccountBinding())
        || readback.result().length == 0) {
      throw new IllegalArgumentException(
          "World terminal response differs from the complete original Account binding");
    }
    if (readback.outcome() == Outcome.COMMITTED) requireCommittedResult(request, readback);
  }

  /**
   * Checks this World carrier only; mutation/source semantics remain at the authenticated owner.
   */
  static void requireCommittedResult(
      WorldDraftTerminalReadEvidence.Request request,
      DraftAuthorizationFenceBinding.OwnerReadback readback) {
    try {
      byte[] bytes = readback.result();
      String json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
              .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      if (!Arrays.equals(bytes, Rfc8785CanonicalJson.canonicalizeUtf8(json)))
        throw new IllegalArgumentException("Noncanonical World APPLIED result");
      JsonNode result = JSON.readTree(bytes);
      String schema = text(result, "schema");
      boolean v2 = APPLIED_V2.equals(schema);
      if (!v2 && !APPLIED_V1.equals(schema)) {
        throw new IllegalArgumentException("Unsupported World APPLIED result schema");
      }
      fields(result, v2 ? APPLIED_V2_FIELDS : APPLIED_V1_FIELDS);
      exactText(result, "status", "APPLIED");
      var account = request.accountBinding();
      var draft =
          DraftCommitBinding.fromStored(
              new String(account.gameDesignBinding(), StandardCharsets.UTF_8),
              account.inputDigest());
      if (!Arrays.equals(draft.canonicalBytes(), account.normalizedInput())
          || !draft.requestId().equals(account.requestId())
          || !draft.commitId().equals(account.commitId())
          || !draft.target().canonicalTenantId().equals(account.tenantId())
          || !draft.target().canonicalVersionId().equals(account.versionId())) {
        throw new IllegalArgumentException("World applied input differs from original binding");
      }
      OriginalFreshGraph originalGraph = readOriginalFreshGraph(draft, v2);
      if (v2 != (originalGraph != null)) {
        throw new IllegalArgumentException(
            "World APPLIED schema does not match the original fresh graph declaration");
      }
      FrameReader operation = new FrameReader(base64(result, "operationBytesBase64"));
      operation.expect("world-draft-terminal-operation/v1");
      for (UUID id :
          java.util.List.of(
              account.operationId(),
              account.requestId(),
              account.commitId(),
              account.fenceId(),
              account.tenantId(),
              account.versionId())) operation.expect(id.toString());
      operation.expectBytes(draft.canonicalBytes());
      operation.expect(request.targetNamespace());
      operation.expect(account.tenantId().toString());
      operation.expect(account.versionId().toString());
      UUID versionIdentityOperationId = operation.canonicalUuid();
      operation.expect(Long.toString(draft.target().gameDesignVersionRowId()));
      operation.canonicalUuid(); // Intake request
      operation.canonicalUuid(); // Intake operation
      operation.digest(); // Intake request digest
      operation.canonicalUuid(); // Source operation
      operation.digest(); // Source evidence digest
      operation.digest(); // Intake receipt digest
      operation.expect(sha256(request.originalAccountBinding()));
      operation.expectBytes(request.originalAccountBinding());
      operation.requireEnd();
      byte[] graph = base64(result, "graphBytesBase64");
      String graphDigest = sha256(graph);
      exactText(result, "graphDigest", graphDigest);
      JsonNode graphValue = JSON.readTree(graph);
      fields(
          graphValue, Set.of("schemaVersion", "canonicalTenantId", "canonicalVersionId", "rows"));
      exactText(graphValue, "schemaVersion", "2");
      exactText(graphValue, "canonicalTenantId", account.tenantId().toString());
      exactText(graphValue, "canonicalVersionId", account.versionId().toString());
      if (v2) {
        String graphJson =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(graph))
                .toString();
        if (!Arrays.equals(graph, Rfc8785CanonicalJson.canonicalizeUtf8(graphJson))) {
          throw new IllegalArgumentException("World v2 graph bytes are not canonical");
        }
      }
      if (!graphValue.get("rows").isArray() || graphValue.get("rows").isEmpty())
        throw new IllegalArgumentException("World applied graph is absent");
      if (v2) {
        validateDeclaredGraphRows(
            graphValue.get("rows"),
            originalGraph,
            request.targetNamespace(),
            account,
            versionIdentityOperationId);
        validateStartLocationReceipt(result, graphDigest, originalGraph, request, draft, account);
      }
      var expected = draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT);
      JsonNode epochs = result.get("appliedEpochs");
      if (!epochs.isArray() || expected.isEmpty() || epochs.size() != expected.size())
        throw new IllegalArgumentException("World applied epoch vector is incomplete");
      for (int i = 0; i < expected.size(); i++) {
        var unit = expected.get(i);
        JsonNode epoch = epochs.get(i);
        fields(
            epoch,
            Set.of(
                "aggregateType",
                "aggregateId",
                "scopeType",
                "scopeId",
                "expectedEpoch",
                "resultingEpoch"));
        exactText(epoch, "aggregateType", unit.aggregateType());
        exactText(epoch, "aggregateId", unit.aggregateId());
        exactText(epoch, "scopeType", unit.scopeType());
        exactText(epoch, "scopeId", unit.scopeId());
        exactText(epoch, "expectedEpoch", unit.expectedEpoch());
        exactText(
            epoch,
            "resultingEpoch",
            new BigInteger(unit.expectedEpoch()).add(BigInteger.ONE).toString());
      }
    } catch (java.io.IOException | NoSuchAlgorithmException | RuntimeException invalid) {
      throw new IllegalArgumentException(
          "World committed readback has an invalid or substituted canonical APPLIED result",
          invalid);
    }
  }

  private static OriginalFreshGraph readOriginalFreshGraph(
      DraftCommitBinding draft, boolean requireCompleteDeclaration) throws java.io.IOException {
    Map<String, OriginalGraphNode> nodes = new LinkedHashMap<>();
    JsonNode declaration = null;
    for (DraftCommitBinding.RevisionPayload revision : draft.revisions()) {
      if (revision.owner() != DraftCommitBinding.Owner.WORLD_MANAGEMENT) continue;
      JsonNode mutation;
      try {
        mutation = JSON.readTree(revision.payload());
      } catch (RuntimeException invalidPayload) {
        if (requireCompleteDeclaration) throw invalidPayload;
        // Retained v1 payloads predate a common JSON shape; they cannot declare a new typed
        // selector.
        continue;
      }
      if (mutation == null || !mutation.isObject()) {
        if (requireCompleteDeclaration) {
          throw new IllegalArgumentException("Original World mutation payload is not an object");
        }
        continue;
      }
      JsonNode graphDeclaration = mutation.get("freshGraphDeclaration");
      if (!requireCompleteDeclaration) {
        if (graphDeclaration != null) {
          throw new IllegalArgumentException(
              "Historical World APPLIED v1 cannot downgrade a declared fresh graph");
        }
        continue;
      }
      fieldsKnown(mutation, WORLD_MUTATION_FIELDS);
      exactText(mutation, "logicalRevisionId", revision.revisionId().toString());
      exactText(mutation, "commitId", draft.commitId().toString());
      String family = text(mutation, "aggregateType");
      if (!FRESH_GRAPH_FAMILIES.contains(family)) {
        throw new IllegalArgumentException("Unsupported family in declared World graph input");
      }
      UUID templateId = parseCanonicalNonNilUuid(text(mutation, "aggregateId"), "aggregateId");
      String key = family + ":" + templateId;
      if (nodes.putIfAbsent(
              key,
              new OriginalGraphNode(
                  family, templateId, revision.revisionId(), revision.revisionOrder()))
          != null) {
        throw new IllegalArgumentException("Original declared World graph repeats a template");
      }
      if (graphDeclaration != null) {
        if (declaration != null) {
          throw new IllegalArgumentException(
              "Original World input has multiple graph declarations");
        }
        declaration = graphDeclaration;
      }
    }
    if (!requireCompleteDeclaration) return null;
    if (declaration == null || nodes.isEmpty()) {
      throw new IllegalArgumentException(
          "World v2 requires an original complete graph declaration");
    }
    return parseOriginalFreshGraph(declaration, nodes, draft);
  }

  private static OriginalFreshGraph parseOriginalFreshGraph(
      JsonNode declaration, Map<String, OriginalGraphNode> nodes, DraftCommitBinding draft) {
    fields(declaration, FRESH_GRAPH_DECLARATION_FIELDS);
    UUID tenantId = parseCanonicalNonNilUuid(text(declaration, "tenantId"), "declaration tenantId");
    UUID versionId =
        parseCanonicalNonNilUuid(text(declaration, "versionId"), "declaration versionId");
    if (!tenantId.equals(draft.target().canonicalTenantId())
        || !versionId.equals(draft.target().canonicalVersionId())) {
      throw new IllegalArgumentException("Original World graph declaration has another scope");
    }
    JsonNode selector = declaration.get("startLocation");
    fields(selector, ROOM_TEMPLATE_REF_FIELDS);
    UUID selectorTenant =
        parseCanonicalNonNilUuid(text(selector, "tenantId"), "start selector tenantId");
    UUID selectorVersion =
        parseCanonicalNonNilUuid(text(selector, "versionId"), "start selector versionId");
    UUID roomTemplateId =
        parseCanonicalNonNilUuid(text(selector, "roomTemplateId"), "start selector roomTemplateId");
    if (!tenantId.equals(selectorTenant) || !versionId.equals(selectorVersion)) {
      throw new IllegalArgumentException("Original World start selector has another scope");
    }
    JsonNode counts = declaration.get("familyCounts");
    if (counts == null || !counts.isArray() || counts.size() != FRESH_GRAPH_FAMILIES.size()) {
      throw new IllegalArgumentException("Original World graph declaration is incomplete");
    }
    for (int i = 0; i < FRESH_GRAPH_FAMILIES.size(); i++) {
      JsonNode entry = counts.get(i);
      fields(entry, FAMILY_COUNT_FIELDS);
      String family = text(entry, "family");
      JsonNode countNode = entry.get("count");
      if (!FRESH_GRAPH_FAMILIES.get(i).equals(family)
          || countNode == null
          || !countNode.isIntegralNumber()
          || !countNode.canConvertToInt()
          || countNode.intValue() < 0) {
        throw new IllegalArgumentException(
            "Original World graph family counts must be present, ordered and nonnegative");
      }
      int actualCount =
          (int) nodes.values().stream().filter(node -> node.family().equals(family)).count();
      if (actualCount != countNode.intValue()) {
        throw new IllegalArgumentException(
            "Original World graph family count differs from its complete typed input");
      }
    }
    OriginalGraphNode selectedRoom =
        nodes.get("WORLD_DESIGN_AGGREGATE_TYPE_ROOM:" + roomTemplateId);
    if (selectedRoom == null) {
      throw new IllegalArgumentException(
          "Original World start selector is absent from its ROOM graph");
    }
    return new OriginalFreshGraph(
        tenantId,
        versionId,
        roomTemplateId,
        selectedRoom,
        Map.copyOf(nodes),
        List.copyOf(nodes.values()));
  }

  private static void validateDeclaredGraphRows(
      JsonNode rows,
      OriginalFreshGraph original,
      String targetNamespace,
      DraftAuthorizationFenceBinding binding,
      UUID versionIdentityOperationId) {
    if (rows.size() != original.nodes().size()) {
      throw new IllegalArgumentException(
          "World v2 graph rows differ from the complete original input");
    }
    Set<String> seenNodes = new HashSet<>();
    Set<Long> seenMappingIds = new HashSet<>();
    Set<String> seenPrivateRowKeys = new HashSet<>();
    Long privateTenantId = null;
    Long privateVersionId = null;
    boolean selectedRoomFound = false;
    int rowIndex = 0;
    for (JsonNode row : rows) {
      fields(row, GRAPH_ROW_FIELDS);
      JsonNode mapping = row.get("mapping");
      fields(mapping, GRAPH_MAPPING_FIELDS);
      if (row.get("content") == null || !row.get("content").isObject()) {
        throw new IllegalArgumentException("World v2 graph row content is absent");
      }
      exactText(mapping, "target_namespace", targetNamespace);
      exactText(mapping, "canonical_tenant_id", binding.tenantId().toString());
      exactText(mapping, "canonical_version_id", binding.versionId().toString());
      exactText(mapping, "request_id", binding.requestId().toString());
      exactText(mapping, "commit_id", binding.commitId().toString());
      String family = text(mapping, "family");
      if (!FRESH_GRAPH_FAMILIES.contains("WORLD_DESIGN_AGGREGATE_TYPE_" + family)) {
        throw new IllegalArgumentException("World v2 graph row has an unsupported family");
      }
      UUID templateId = parseCanonicalNonNilUuid(text(mapping, "template_id"), "graph template_id");
      String nodeKey = "WORLD_DESIGN_AGGREGATE_TYPE_" + family + ":" + templateId;
      OriginalGraphNode originalNode = original.nodes().get(nodeKey);
      if (originalNode == null
          || !original.orderedNodes().get(rowIndex).equals(originalNode)
          || !seenNodes.add(nodeKey)
          || !originalNode.revisionId().toString().equals(text(mapping, "revision_id"))
          || !originalNode.revisionOrder().equals(text(mapping, "revision_order"))) {
        throw new IllegalArgumentException(
            "World v2 graph row differs from its exact original revision");
      }
      UUID graphVersionIdentityOperationId =
          parseCanonicalNonNilUuid(
              text(mapping, "version_identity_operation_id"), "graph Version identity operation");
      if (!versionIdentityOperationId.equals(graphVersionIdentityOperationId)) {
        throw new IllegalArgumentException(
            "World v2 graph uses another Version identity operation");
      }
      long mappingId = positiveLong(mapping, "id");
      long tenantId = positiveLong(mapping, "tenant_id");
      long versionId = positiveLong(mapping, "version_id");
      long privateRowKey = positiveLong(mapping, "private_row_key");
      if (privateTenantId == null) {
        privateTenantId = tenantId;
        privateVersionId = versionId;
      } else if (privateTenantId != tenantId || privateVersionId != versionId) {
        throw new IllegalArgumentException(
            "World v2 graph rows cross private tenant or Version scope");
      }
      if (!seenMappingIds.add(mappingId) || !seenPrivateRowKeys.add(family + ":" + privateRowKey)) {
        throw new IllegalArgumentException("World v2 graph contains a repeated private mapping");
      }
      if (originalNode.equals(original.selectedRoom())
          && "ROOM".equals(family)
          && templateId.equals(original.roomTemplateId())) {
        selectedRoomFound = true;
      }
      rowIndex++;
    }
    if (!selectedRoomFound || seenNodes.size() != original.nodes().size()) {
      throw new IllegalArgumentException("World v2 graph omits the exact selected ROOM row");
    }
  }

  private static void validateStartLocationReceipt(
      JsonNode result,
      String graphDigest,
      OriginalFreshGraph original,
      WorldDraftTerminalReadEvidence.Request request,
      DraftCommitBinding draft,
      DraftAuthorizationFenceBinding binding)
      throws java.io.IOException, NoSuchAlgorithmException {
    byte[] receiptBytes = base64(result, "startLocationReceiptBase64");
    String receiptJson =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(receiptBytes))
            .toString();
    if (!Arrays.equals(receiptBytes, Rfc8785CanonicalJson.canonicalizeUtf8(receiptJson))) {
      throw new IllegalArgumentException("World start-location receipt is not canonical");
    }
    JsonNode receipt = JSON.readTree(receiptBytes);
    fields(
        receipt,
        Set.of(
            "schema",
            "targetNamespace",
            "operationId",
            "requestId",
            "commitId",
            "authorizationFenceId",
            "accountBindingDigest",
            "bindingDigest",
            "startLocation",
            "graphDigest",
            "receiptDigest"));
    exactText(receipt, "schema", START_LOCATION_RECEIPT_V1);
    exactText(receipt, "targetNamespace", request.targetNamespace());
    exactText(receipt, "operationId", binding.operationId().toString());
    exactText(receipt, "requestId", binding.requestId().toString());
    exactText(receipt, "commitId", binding.commitId().toString());
    exactText(receipt, "authorizationFenceId", binding.fenceId().toString());
    exactText(receipt, "accountBindingDigest", sha256(request.originalAccountBinding()));
    exactText(receipt, "bindingDigest", draft.digest());
    exactText(receipt, "graphDigest", graphDigest);
    JsonNode selector = receipt.get("startLocation");
    fields(selector, ROOM_TEMPLATE_REF_FIELDS);
    exactText(selector, "tenantId", original.tenantId().toString());
    exactText(selector, "versionId", original.versionId().toString());
    exactText(selector, "roomTemplateId", original.roomTemplateId().toString());
    String receiptDigest = text(receipt, "receiptDigest");
    exactText(result, "startLocationReceiptDigest", receiptDigest);
    String expectedReceiptDigest =
        startLocationReceiptDigest(
            request.targetNamespace(),
            binding.operationId(),
            binding.requestId(),
            binding.commitId(),
            binding.fenceId(),
            sha256(request.originalAccountBinding()),
            draft.digest(),
            original.tenantId(),
            original.versionId(),
            original.roomTemplateId(),
            graphDigest);
    if (!expectedReceiptDigest.equals(receiptDigest)) {
      throw new IllegalArgumentException("World start-location receipt digest is invalid");
    }
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
      throws NoSuchAlgorithmException {
    java.io.ByteArrayOutputStream framed = new java.io.ByteArrayOutputStream();
    for (String value :
        java.util.List.of(
            START_LOCATION_RECEIPT_V1,
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

  private static long positiveLong(JsonNode value, String field) {
    JsonNode actual = value.get(field);
    if (actual == null
        || !actual.isIntegralNumber()
        || !actual.canConvertToLong()
        || actual.longValue() <= 0) {
      throw new IllegalArgumentException("World graph requires positive private mapping " + field);
    }
    return actual.longValue();
  }

  private static String text(JsonNode value, String field) {
    JsonNode actual = value == null ? null : value.get(field);
    if (actual == null || !actual.isTextual()) {
      throw new IllegalArgumentException("World APPLIED carrier requires text " + field);
    }
    return actual.textValue();
  }

  private static void fieldsKnown(JsonNode value, Set<String> fields) {
    if (value == null
        || !value.isObject()
        || value.properties().stream().anyMatch(entry -> !fields.contains(entry.getKey()))) {
      throw new IllegalArgumentException("Original World mutation has unsupported fields");
    }
  }

  private record OriginalGraphNode(
      String family, UUID templateId, UUID revisionId, String revisionOrder) {}

  private record OriginalFreshGraph(
      UUID tenantId,
      UUID versionId,
      UUID roomTemplateId,
      OriginalGraphNode selectedRoom,
      Map<String, OriginalGraphNode> nodes,
      List<OriginalGraphNode> orderedNodes) {}

  private static void fields(JsonNode value, Set<String> fields) {
    if (value == null
        || !value.isObject()
        || value.size() != fields.size()
        || value.properties().stream().anyMatch(entry -> !fields.contains(entry.getKey())))
      throw new IllegalArgumentException("World APPLIED carrier fields differ");
  }

  private static void exactText(JsonNode value, String field, String expected) {
    if (value.get(field) == null
        || !value.get(field).isTextual()
        || !expected.equals(value.get(field).textValue()))
      throw new IllegalArgumentException("World APPLIED carrier differs at " + field);
  }

  private static String sha256(byte[] value) throws NoSuchAlgorithmException {
    return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static byte[] base64(JsonNode value, String field) {
    if (value.get(field) == null || !value.get(field).isTextual())
      throw new IllegalArgumentException("World APPLIED bytes missing");
    String encoded = value.get(field).textValue();
    byte[] decoded = Base64.getDecoder().decode(encoded);
    if (decoded.length == 0 || !Base64.getEncoder().encodeToString(decoded).equals(encoded))
      throw new IllegalArgumentException("Noncanonical World APPLIED Base64");
    return decoded;
  }

  private static final class FrameReader {
    private final ByteBuffer bytes;

    private FrameReader(byte[] value) {
      bytes = ByteBuffer.wrap(value);
    }

    private byte[] frame() {
      if (bytes.remaining() < Integer.BYTES)
        throw new IllegalArgumentException("Truncated World operation");
      int size = bytes.getInt();
      if (size <= 0 || size > bytes.remaining())
        throw new IllegalArgumentException("Invalid World operation frame");
      byte[] value = new byte[size];
      bytes.get(value);
      return value;
    }

    private String text() {
      try {
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(frame()))
            .toString();
      } catch (java.nio.charset.CharacterCodingException invalid) {
        throw new IllegalArgumentException("Invalid World operation UTF-8", invalid);
      }
    }

    private void expect(String expected) {
      if (!expected.equals(text()))
        throw new IllegalArgumentException("Substituted World operation");
    }

    private void expectBytes(byte[] expected) {
      if (!Arrays.equals(expected, frame()))
        throw new IllegalArgumentException("Substituted World operation bytes");
    }

    private UUID canonicalUuid() {
      return parseCanonicalNonNilUuid(text(), "World owner identity");
    }

    private void digest() {
      if (!text().matches("(?:sha256:)?[0-9a-f]{64}"))
        throw new IllegalArgumentException("Invalid World owner digest");
    }

    private void requireEnd() {
      if (bytes.hasRemaining())
        throw new IllegalArgumentException("Trailing World operation bytes");
    }
  }

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(label + " contains unsupported fields");
    }
  }

  private static UUID parseCanonicalNonNilUuid(String value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("Canonical non-nil " + label + " is required");
    }
    try {
      UUID parsed = UUID.fromString(value);
      if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
        throw new IllegalArgumentException("Canonical non-nil " + label + " is required");
      }
      return parsed;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Canonical non-nil " + label + " is required", exception);
    }
  }
}
