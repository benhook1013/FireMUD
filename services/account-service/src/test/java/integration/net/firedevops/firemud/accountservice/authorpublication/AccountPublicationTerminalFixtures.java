package integration.net.firedevops.firemud.accountservice.authorpublication;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence.OwnedAffectedTuple;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence.Request;
import tools.jackson.databind.ObjectMapper;

/**
 * Fixed isolated complete DTO fixtures copied from the common publication carrier tests.
 * Account/World inputs are stipulated; these fixtures exercise Account's decoders and persistence,
 * not either upstream service's durable state or authenticated transport.
 */
final class AccountPublicationTerminalFixtures {
  private static final UUID TENANT_ID = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION_ID = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID REQUEST_ID = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID COMMIT_ID = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID ROOM_ID = uuid("77777777-7777-4777-8777-777777777777");
  private static final UUID REGION_ID = uuid("88888888-8888-4888-8888-888888888888");
  private static final UUID ZONE_ID = uuid("99999999-9999-4999-8999-999999999999");
  private static final UUID REGION_REVISION_ID = uuid("12345678-1234-4234-8234-123456789001");
  private static final UUID ZONE_REVISION_ID = uuid("12345678-1234-4234-8234-123456789002");
  private static final UUID ROOM_REVISION_ID = uuid("12345678-1234-4234-8234-123456789003");
  private static final ObjectMapper JSON = new ObjectMapper();

  private AccountPublicationTerminalFixtures() {}

  static Scenario scenario() throws Exception {
    DraftCommitBinding draft = freshGraphBinding();
    byte[] draftFenceBytes = draftFence(draft).canonicalBytes();
    byte[] appliedResult = appliedResult(draft, draftFenceBytes);
    var appliedJson = JSON.readTree(appliedResult);
    byte[] receiptBytes =
        Base64.getDecoder().decode(appliedJson.get("startLocationReceiptBase64").textValue());

    String publishRequestId = "publication-request";
    String namespace = "test";
    var target = draft.target();
    var selected =
        AuthoredDraftPublishSelectionBinding.capture(
            new AuthoredDraftPublishSelectionBinding.PublishIntent(
                target.canonicalTenantId(),
                target.canonicalVersionId(),
                publishRequestId,
                "5",
                "ISOLATED fixed notes",
                draft.requestId(),
                draft.commitId(),
                draft.digest()),
            target,
            draft,
            new AuthoredDraftPublishSelectionBinding.VisibilityFence(
                target,
                draft.requestId(),
                draft.commitId(),
                draft.digest(),
                "[]",
                OffsetDateTime.parse("2026-10-07T00:00:00Z")));
    var input =
        new AccountPublicationAuthorizationBinding.PreallocationInput(
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"), selected);
    var account =
        new AccountPublicationAuthorizationBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            input,
            List.of(
                new DraftAuthorizationFenceBinding.SourceEvidence(
                    DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                    input.actorAccountId().toString(),
                    "3",
                    "4",
                    null,
                    null,
                    new byte[] {7})));

    var seedRequest = publishedSelectorRequest(draft, publishRequestId, selected.digest());
    var world =
        new WorldPublishedStartLocationEvidence(
            seedRequest, receiptBytes, draftFenceBytes, appliedResult);
    var operation = new GameDesignPublicationOperationBinding(account, world);
    var noPublicationTerminal =
        new GameDesignPublicationTerminalEvidence(
            operation.canonicalBytes(),
            GameDesignPublicationTerminalEvidence.Outcome.NO_PUBLICATION,
            null,
            null);
    var publishedTerminal =
        new GameDesignPublicationTerminalEvidence(
            operation.canonicalBytes(),
            GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED,
            releaseContent(operation),
            6L);
    return new Scenario(account, operation, noPublicationTerminal, publishedTerminal);
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
                        "roomTemplateId", ROOM_ID.toString()),
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
        new DraftCommitBinding.TargetProof(
            TENANT_ID, VERSION_ID, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW"),
        REQUEST_ID,
        COMMIT_ID,
        "base-1",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0",
                REGION_REVISION_ID,
                DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                worldRevisionPayload(
                    REGION_REVISION_ID,
                    "WORLD_DESIGN_AGGREGATE_TYPE_REGION",
                    REGION_ID,
                    declaration)),
            new DraftCommitBinding.RevisionPayload(
                "1",
                ZONE_REVISION_ID,
                DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                worldRevisionPayload(
                    ZONE_REVISION_ID, "WORLD_DESIGN_AGGREGATE_TYPE_ZONE", ZONE_ID, null)),
            new DraftCommitBinding.RevisionPayload(
                "2",
                ROOM_REVISION_ID,
                DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                worldRevisionPayload(
                    ROOM_REVISION_ID, "WORLD_DESIGN_AGGREGATE_TYPE_ROOM", ROOM_ID, null))),
        List.of(
                affected("REGION", REGION_ID, "REGION_SUBTREE", REGION_ID),
                affected("ZONE", ZONE_ID, "REGION_SUBTREE", REGION_ID),
                affected("ROOM", ROOM_ID, "ZONE_SUBTREE", ZONE_ID))
            .stream()
            .flatMap(List::stream)
            .toList());
  }

  private static DraftAuthorizationFenceBinding draftFence(DraftCommitBinding draft) {
    return new DraftAuthorizationFenceBinding(
        uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
        draft.requestId(),
        draft.commitId(),
        uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
        uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
        TENANT_ID,
        VERSION_ID,
        draft.baseCommitId(),
        "0",
        draft.canonicalBytes(),
        draft.canonicalBytes(),
        draft.digest(),
        List.of(
            new SourceEvidence(
                SourceKind.GLOBAL_ROLES,
                uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd").toString(),
                null,
                "1",
                null,
                null,
                new byte[] {4, 5})));
  }

  private static byte[] appliedResult(DraftCommitBinding draft, byte[] draftFenceBytes)
      throws Exception {
    var accountRequest =
        new net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence.Request(
            1, "test", UUID.randomUUID(), draftFenceBytes);
    var account = accountRequest.accountBinding();
    byte[] graph = graphBytes(accountRequest, draft);
    String graphDigest = sha256(graph);
    var receipt =
        WorldDraftStartLocationEvidence.create(
            "test",
            account.operationId(),
            account.requestId(),
            account.commitId(),
            account.fenceId(),
            sha256(draftFenceBytes),
            draft.digest(),
            new RoomTemplateRef(TENANT_ID, VERSION_ID, ROOM_ID),
            graphDigest);
    byte[] operation = appliedOperation(accountRequest, draft);
    var result = new java.util.LinkedHashMap<String, Object>();
    result.put("schema", "world-draft-graph-applied/v2");
    result.put("status", "APPLIED");
    result.put("operationBytesBase64", Base64.getEncoder().encodeToString(operation));
    result.put("graphBytesBase64", Base64.getEncoder().encodeToString(graph));
    result.put("graphDigest", graphDigest);
    result.put(
        "startLocationReceiptBase64", Base64.getEncoder().encodeToString(receipt.canonicalBytes()));
    result.put("startLocationReceiptDigest", receipt.receiptDigest());
    result.put(
        "appliedEpochs",
        draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    Map.of(
                        "aggregateType", unit.aggregateType(),
                        "aggregateId", unit.aggregateId(),
                        "scopeType", unit.scopeType(),
                        "scopeId", unit.scopeId(),
                        "expectedEpoch", unit.expectedEpoch(),
                        "resultingEpoch",
                            new java.math.BigInteger(unit.expectedEpoch())
                                .add(java.math.BigInteger.ONE)
                                .toString()))
            .toList());
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(result));
  }

  private static byte[] appliedOperation(
      net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence.Request request,
      DraftCommitBinding draft)
      throws Exception {
    var output = new ByteArrayOutputStream();
    var frames = new DataOutputStream(output);
    frame(frames, "world-draft-terminal-operation/v1");
    var account = request.accountBinding();
    for (UUID id :
        List.of(
            account.operationId(),
            account.requestId(),
            account.commitId(),
            account.fenceId(),
            account.tenantId(),
            account.versionId())) frame(frames, id.toString());
    frame(frames, draft.canonicalBytes());
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
            "c".repeat(64))) frame(frames, value);
    frame(frames, sha256(account.canonicalBytes()));
    frame(frames, account.canonicalBytes());
    return output.toByteArray();
  }

  private static byte[] graphBytes(
      net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence.Request request,
      DraftCommitBinding draft)
      throws Exception {
    var root = new java.util.LinkedHashMap<String, Object>();
    root.put("schemaVersion", "2");
    root.put("canonicalTenantId", draft.target().canonicalTenantId().toString());
    root.put("canonicalVersionId", draft.target().canonicalVersionId().toString());
    var rows = new java.util.ArrayList<Map<String, Object>>();
    int mappingId = 1;
    long privateRowKey = 101;
    for (var revision : draft.revisions()) {
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
      mapping.put("version_identity_operation_id", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
      mapping.put("request_id", draft.requestId().toString());
      mapping.put("commit_id", draft.commitId().toString());
      mapping.put("revision_id", revision.revisionId().toString());
      mapping.put("revision_order", revision.revisionOrder());
      rows.add(Map.of("mapping", mapping, "content", Map.of()));
    }
    root.put("rows", rows);
    return JSON.writeValueAsBytes(root);
  }

  private static GameDesignPublicationTerminalEvidence.ReleaseContent releaseContent(
      GameDesignPublicationOperationBinding operation) {
    var world = operation.world();
    String digest = "sha256:" + "a".repeat(64);
    long versionRowId = operation.account().input().selection().target().gameDesignVersionRowId();
    var participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new GameDesignPublicationTerminalEvidence.Participant(
                        owner,
                        Long.toString(versionRowId),
                        null,
                        world.request().appliedCommitId(),
                        owner.equals("WORLD_MANAGEMENT")
                            ? world.request().contentDigest()
                            : "c".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner),
                        owner.equals("GAME_LOGIC") ? digest : null,
                        null,
                        null))
            .toList();
    return new GameDesignPublicationTerminalEvidence.ReleaseContent(
        world.request().canonicalTenantId(),
        world.request().canonicalVersionId(),
        "ISOLATED-bundle",
        1,
        "v2",
        world.request().publishWorkflowId(),
        digest,
        1,
        List.of(),
        List.of(),
        participants,
        List.of("LOOK"),
        "generation-1",
        world);
  }

  private static Request publishedSelectorRequest(
      DraftCommitBinding draft, String publishRequestId, String selectionDigest) {
    var tuples =
        draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    new OwnedAffectedTuple(
                        unit.owner().name(),
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch()))
            .toList();
    String workflow =
        PublicationDigestRequestBinding.full(TENANT_ID.toString(), "42", publishRequestId)
            .derivedWorkflowIdentity();
    return new Request(
        "test",
        TENANT_ID,
        VERSION_ID,
        uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
        uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
        publishRequestId,
        selectionDigest.substring(7),
        5L,
        workflow,
        draft.commitId().toString(),
        "b".repeat(64),
        3,
        tuples);
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

  private static List<DraftCommitBinding.AffectedUnit> affected(
      String family, UUID templateId, String scopeType, UUID scopeId) {
    return List.of(
        new DraftCommitBinding.AffectedUnit(
            DraftCommitBinding.Owner.WORLD_MANAGEMENT,
            "WORLD_DESIGN_AGGREGATE_TYPE_" + family,
            templateId.toString(),
            "AGGREGATE",
            templateId.toString(),
            "0"),
        new DraftCommitBinding.AffectedUnit(
            DraftCommitBinding.Owner.WORLD_MANAGEMENT,
            "WORLD_DESIGN_AGGREGATE_TYPE_" + family,
            templateId.toString(),
            scopeType,
            scopeId.toString(),
            "0"));
  }

  private static void frame(DataOutputStream output, String value) throws Exception {
    frame(output, value.getBytes(StandardCharsets.UTF_8));
  }

  private static void frame(DataOutputStream output, byte[] value) throws Exception {
    output.writeInt(value.length);
    output.write(value);
  }

  private static String sha256(byte[] value) throws Exception {
    return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  record Scenario(
      AccountPublicationAuthorizationBinding account,
      GameDesignPublicationOperationBinding operation,
      GameDesignPublicationTerminalEvidence noPublicationTerminal,
      GameDesignPublicationTerminalEvidence publishedTerminal) {}
}
