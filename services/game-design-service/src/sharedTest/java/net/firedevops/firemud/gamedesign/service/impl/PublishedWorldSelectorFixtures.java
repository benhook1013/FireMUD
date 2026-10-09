package net.firedevops.firemud.gamedesign.service.impl;

import java.io.ByteArrayOutputStream;
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
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;

/** Stipulated original Account/APPLIED component bytes; no live producer/currentness proof. */
public final class PublishedWorldSelectorFixtures {
  private final TargetProof target;

  private PublishedWorldSelectorFixtures(TargetProof target) {
    this.target = target;
    this.TENANT_ID = target.canonicalTenantId();
    this.VERSION_ID = target.canonicalVersionId();
  }

  public static WorldPublishedStartLocationEvidence evidence(TargetProof target) throws Exception {
    return new PublishedWorldSelectorFixtures(target).build();
  }

  public static List<net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto>
      participants(long versionId, WorldPublishedStartLocationEvidence evidence) {
    return net
        .firedevops
        .firemud
        .common
        .gamedesign
        .AuthoredWorldReleaseAttestationEvidence
        .requiredParticipantOrder()
        .stream()
        .map(
            owner ->
                new net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto(
                    owner,
                    Long.toString(versionId),
                    null,
                    evidence.request().appliedCommitId(),
                    "WORLD_MANAGEMENT".equals(owner)
                        ? evidence.request().contentDigest()
                        : "c".repeat(64),
                    net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence
                        .supportedParticipantDigestSchema(
                            owner,
                            net.firedevops.firemud.common.gamedesign
                                .AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION),
                    "GAME_LOGIC".equals(owner) ? "sha256:" + "d".repeat(64) : null,
                    null,
                    null))
        .toList();
  }

  private WorldPublishedStartLocationEvidence build() throws Exception {
    var draft = freshGraphBinding();
    var terminal = WorldDraftTerminalReadEvidence.Request.create("test", accountBinding(draft));
    var result = committedReadback(terminal, true);
    var applied = JSON.readTree(result.result());
    var tuples =
        draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
            .map(
                u ->
                    new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
                        u.owner().name(),
                        u.aggregateType(),
                        u.aggregateId(),
                        u.scopeType(),
                        u.scopeId(),
                        u.expectedEpoch()))
            .toList();
    var request =
        new WorldPublishedStartLocationEvidence.Request(
            "test",
            TENANT_ID,
            VERSION_ID,
            uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            "publication-request",
            "a".repeat(64),
            5L,
            "publish-workflow",
            draft.commitId().toString(),
            "b".repeat(64),
            3,
            tuples);
    return new WorldPublishedStartLocationEvidence(
        request,
        Base64.getDecoder().decode(applied.get("startLocationReceiptBase64").textValue()),
        result.fullBinding(),
        result.result());
  }

  private final UUID OPERATION_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private final UUID REQUEST_ID = uuid("44444444-4444-4444-8444-444444444444");
  private final UUID COMMIT_ID = uuid("55555555-5555-4555-8555-555555555555");
  private final UUID FENCE_ID = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private final UUID ACTOR_ID = uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private final UUID TENANT_ID;
  private final UUID VERSION_ID;
  private final UUID REVISION_ID = uuid("66666666-6666-4666-8666-666666666666");
  private final UUID REGION_TEMPLATE_ID = uuid("88888888-8888-4888-8888-888888888888");
  private final UUID ZONE_TEMPLATE_ID = uuid("99999999-9999-4999-8999-999999999999");
  private final UUID ROOM_TEMPLATE_ID = uuid("77777777-7777-4777-8777-777777777777");
  private final UUID REGION_REVISION_ID = uuid("12345678-1234-4234-8234-123456789001");
  private final UUID ZONE_REVISION_ID = uuid("12345678-1234-4234-8234-123456789002");
  private final UUID ROOM_REVISION_ID = uuid("12345678-1234-4234-8234-123456789003");
  private final tools.jackson.databind.ObjectMapper JSON =
      new tools.jackson.databind.ObjectMapper();

  private DraftAuthorizationFenceBinding.OwnerReadback committedReadback(
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

  private DraftCommitBinding freshGraphBinding() throws Exception {
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
    var revisions = policyRevisions();
    int worldRevisionOrder = revisions.size();
    revisions.add(
        new RevisionPayload(
            Integer.toString(worldRevisionOrder++),
            REGION_REVISION_ID,
            DraftCommitBinding.Owner.WORLD_MANAGEMENT,
            worldRevisionPayload(
                REGION_REVISION_ID,
                "WORLD_DESIGN_AGGREGATE_TYPE_REGION",
                REGION_TEMPLATE_ID,
                declaration)));
    revisions.add(
        new RevisionPayload(
            Integer.toString(worldRevisionOrder++),
            ZONE_REVISION_ID,
            DraftCommitBinding.Owner.WORLD_MANAGEMENT,
            worldRevisionPayload(
                ZONE_REVISION_ID, "WORLD_DESIGN_AGGREGATE_TYPE_ZONE", ZONE_TEMPLATE_ID, null)));
    revisions.add(
        new RevisionPayload(
            Integer.toString(worldRevisionOrder),
            ROOM_REVISION_ID,
            DraftCommitBinding.Owner.WORLD_MANAGEMENT,
            worldRevisionPayload(
                ROOM_REVISION_ID, "WORLD_DESIGN_AGGREGATE_TYPE_ROOM", ROOM_TEMPLATE_ID, null)));
    var affectedUnits = new java.util.ArrayList<AffectedUnit>();
    affectedUnits.add(
        new AffectedUnit(
            DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
            "REALM_ENTRY_POLICY_SET",
            VERSION_ID.toString(),
            "REALM_ENTRY_POLICY_SET",
            "effective",
            "0"));
    affectedUnits.addAll(
        List.of(
                affected("REGION", REGION_TEMPLATE_ID, "REGION_SUBTREE", REGION_TEMPLATE_ID),
                affected("ZONE", ZONE_TEMPLATE_ID, "REGION_SUBTREE", REGION_TEMPLATE_ID),
                affected("ROOM", ROOM_TEMPLATE_ID, "ZONE_SUBTREE", ZONE_TEMPLATE_ID))
            .stream()
            .flatMap(List::stream)
            .toList());
    return DraftCommitBinding.create(
        new TargetProof(
            TENANT_ID,
            VERSION_ID,
            target.gameDesignVersionRowId(),
            target.gameDesignVersionTenantKey(),
            target.sourceGameRowId(),
            target.sourceGameTenantKey(),
            target.sourceProvenanceKind()),
        REQUEST_ID,
        COMMIT_ID,
        "base-1",
        revisions,
        affectedUnits);
  }

  private List<RevisionPayload> policyRevisions() throws Exception {
    var revisions = new java.util.ArrayList<RevisionPayload>();
    UUID revisionId = UUID.randomUUID();
    var policy =
        Map.of(
            "schemaVersion",
            1,
            "worldSlug",
            "starter-world",
            "worldDisplayName",
            "Starter World",
            "realmSlug",
            "realm-001",
            "realmDisplayName",
            "Realm 1",
            "visible",
            true,
            "publicProduction",
            true,
            "stateScope",
            "SHARED",
            "entryPolicy",
            "PRESEEDED_ONLY");
    String payload =
        JSON.writeValueAsString(
            Map.of(
                "revisionKind",
                "REALM_ENTRY_POLICY",
                "logicalRevisionId",
                revisionId.toString(),
                "policy",
                policy));
    revisions.add(
        new RevisionPayload(
            "0", revisionId, DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE, payload));
    return revisions;
  }

  private String worldRevisionPayload(
      UUID revisionId, String family, UUID templateId, String declaration) throws Exception {
    var payload = new java.util.LinkedHashMap<String, Object>();
    payload.put("logicalRevisionId", revisionId.toString());
    payload.put("commitId", COMMIT_ID.toString());
    payload.put("aggregateType", family);
    payload.put("aggregateId", templateId.toString());
    if (declaration != null) payload.put("freshGraphDeclaration", JSON.readTree(declaration));
    return JSON.writeValueAsString(payload);
  }

  private List<AffectedUnit> affected(
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

  private byte[] accountBinding(DraftCommitBinding draft) {
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

  private byte[] graphBytes(
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

  private byte[] historicalGraphBytes(DraftAuthorizationFenceBinding account) {
    throw new IllegalArgumentException("This fixture supplies only selector-bearing APPLIED v2");
  }

  private tools.jackson.databind.node.ObjectNode receipt(
      WorldDraftTerminalReadEvidence.Request request, DraftCommitBinding draft, String graphDigest)
      throws Exception {
    var account = request.accountBinding();
    var receipt =
        WorldDraftStartLocationEvidence.create(
            request.targetNamespace(),
            account.operationId(),
            account.requestId(),
            account.commitId(),
            account.fenceId(),
            sha256(account.canonicalBytes()),
            draft.digest(),
            new RoomTemplateRef(TENANT_ID, VERSION_ID, ROOM_TEMPLATE_ID),
            graphDigest);
    return (tools.jackson.databind.node.ObjectNode) JSON.readTree(receipt.canonicalBytes());
  }

  private byte[] canonical(tools.jackson.databind.JsonNode value) throws Exception {
    return net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
        JSON.writeValueAsString(value));
  }

  private String sha256(byte[] bytes) throws Exception {
    return "sha256:"
        + java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
