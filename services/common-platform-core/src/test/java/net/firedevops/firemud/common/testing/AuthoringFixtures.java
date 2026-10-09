package net.firedevops.firemud.common.testing;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Reusable exact authoring evidence fixtures for isolated Common carrier tests. */
public final class AuthoringFixtures {
  private static final UUID OPERATION_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID REQUEST_ID = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID COMMIT_ID = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID FENCE_ID = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID ACTOR_ID = uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final UUID TENANT_ID = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION_ID = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID REGION_TEMPLATE_ID = uuid("88888888-8888-4888-8888-888888888888");
  private static final UUID ZONE_TEMPLATE_ID = uuid("99999999-9999-4999-8999-999999999999");
  private static final UUID ROOM_TEMPLATE_ID = uuid("77777777-7777-4777-8777-777777777777");
  private static final UUID REGION_REVISION_ID = uuid("12345678-1234-4234-8234-123456789001");
  private static final UUID ZONE_REVISION_ID = uuid("12345678-1234-4234-8234-123456789002");
  private static final UUID ROOM_REVISION_ID = uuid("12345678-1234-4234-8234-123456789003");
  private static final ObjectMapper JSON = new ObjectMapper();

  private AuthoringFixtures() {}

  public static WorldDraftTerminalReadEvidence.Request freshGraphRequest() throws Exception {
    DraftCommitBinding draft = freshGraphBinding();
    return new WorldDraftTerminalReadEvidence.Request(
        1, "test", uuid("33333333-3333-4333-8333-333333333333"), accountBinding(draft));
  }

  public static DraftAuthorizationFenceBinding.OwnerReadback committedReadback(
      WorldDraftTerminalReadEvidence.Request request) {
    return committedReadback(request, false);
  }

  public static DraftAuthorizationFenceBinding.OwnerReadback committedFreshGraphReadback(
      WorldDraftTerminalReadEvidence.Request request) {
    return committedReadback(request, true);
  }

  public static WorldPublishedStartLocationEvidence startLocationEvidence() throws Exception {
    WorldDraftTerminalReadEvidence.Request terminalRequest = freshGraphRequest();
    var committed = committedFreshGraphReadback(terminalRequest);
    var account = terminalRequest.accountBinding();
    var draft =
        DraftCommitBinding.fromStored(
            new String(account.gameDesignBinding(), StandardCharsets.UTF_8), account.inputDigest());
    List<WorldPublishedStartLocationEvidence.OwnedAffectedTuple> tuples =
        draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
                        unit.owner().name(),
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch()))
            .toList();
    var request =
        new WorldPublishedStartLocationEvidence.Request(
            "test",
            account.tenantId(),
            account.versionId(),
            uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            "publication-request",
            "a".repeat(64),
            5L,
            "publish-workflow",
            account.commitId().toString(),
            "b".repeat(64),
            3,
            tuples);
    var applied = JSON.readTree(committed.result());
    byte[] receipt =
        Base64.getDecoder().decode(applied.get("startLocationReceiptBase64").textValue());
    return new WorldPublishedStartLocationEvidence(
        request, receipt, committed.fullBinding(), committed.result());
  }

  public static byte[] accountBinding(DraftCommitBinding draft) {
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

  public static ObjectNode receipt(
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
    return (ObjectNode) JSON.valueToTree(value);
  }

  private static DraftAuthorizationFenceBinding.OwnerReadback committedReadback(
      WorldDraftTerminalReadEvidence.Request request, boolean freshGraph) {
    try {
      var account = request.accountBinding();
      var draft =
          DraftCommitBinding.fromStored(
              new String(account.gameDesignBinding(), StandardCharsets.UTF_8),
              account.inputDigest());
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
      result.put(
          "operationBytesBase64", Base64.getEncoder().encodeToString(operation.toByteArray()));
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
    } catch (Exception impossible) {
      throw new AssertionError(impossible);
    }
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

  private static byte[] canonical(tools.jackson.databind.JsonNode value) throws Exception {
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
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
