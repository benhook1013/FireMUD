package support.net.firedevops.firemud.gamesession;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
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
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Participant;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.ReleaseContent;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence.OwnedAffectedTuple;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence.Request;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterClient;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalPublishedPlayerRoute;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Complete canonical policy-set evidence for Game Session receiving-boundary tests. */
public final class PublishedRealmPolicyEvidenceFixture {
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final UUID ACTOR_ID = uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final UUID OPERATION_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID VERSION_IDENTITY_OPERATION_ID =
      uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaac");
  private static final UUID DRAFT_REQUEST_ID = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID COMMIT_ID = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID DRAFT_FENCE_ID = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID INTAKE_REQUEST_ID = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID INTAKE_OPERATION_ID = uuid("ffffffff-ffff-4fff-8fff-ffffffffffff");
  private static final UUID REGION_TEMPLATE_ID = uuid("88888888-8888-4888-8888-888888888888");
  private static final UUID ZONE_TEMPLATE_ID = uuid("99999999-9999-4999-8999-999999999999");
  private static final UUID ROOM_TEMPLATE_ID = uuid("77777777-7777-4777-8777-777777777777");
  private static final UUID REGION_REVISION_ID = uuid("12345678-1234-4234-8234-123456789001");
  private static final UUID ZONE_REVISION_ID = uuid("12345678-1234-4234-8234-123456789002");
  private static final UUID ROOM_REVISION_ID = uuid("12345678-1234-4234-8234-123456789003");
  private static final ObjectMapper JSON = new ObjectMapper();

  private PublishedRealmPolicyEvidenceFixture() {}

  public static Fixture fixture(PolicySpec... requestedPolicies) {
    return fixture(
        uuid("11111111-1111-4111-8111-111111111111"),
        uuid("22222222-2222-4222-8222-222222222222"),
        19L,
        42L,
        "tenant-key",
        "NEW_GAME_ROW",
        3,
        requestedPolicies);
  }

  public static Fixture fixture(
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      long versionRowId,
      long sourceGameRowId,
      String tenantKey,
      String provenanceKind,
      int versionNumber,
      PolicySpec... requestedPolicies) {
    TargetProof target =
        new TargetProof(
            canonicalTenantId,
            canonicalVersionId,
            versionRowId,
            tenantKey,
            sourceGameRowId,
            tenantKey,
            provenanceKind);
    DraftCommitBinding draft = freshGraphBinding(target);
    byte[] accountBytes = accountBinding(draft);
    var selection = selection(target, draft);
    var workflow =
        PublicationDigestRequestBinding.full(
                target.canonicalTenantId().toString(),
                Long.toString(target.gameDesignVersionRowId()),
                "publication-request")
            .derivedWorkflowIdentity();
    var terminalReadRequest =
        new WorldDraftTerminalReadEvidence.Request(1, "test", INTAKE_REQUEST_ID, accountBytes);
    var committed = committedFreshGraphReadback(terminalReadRequest);
    List<OwnedAffectedTuple> tuples =
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
    var worldRequest =
        new Request(
            "test",
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            INTAKE_REQUEST_ID,
            uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaab"),
            "publication-request",
            selection.digest().substring(7),
            5L,
            workflow,
            draft.commitId().toString(),
            "b".repeat(64),
            3,
            tuples);
    JsonNode applied = JSON.readTree(committed.result());
    byte[] receipt =
        Base64.getDecoder().decode(applied.get("startLocationReceiptBase64").textValue());
    var world =
        new WorldPublishedStartLocationEvidence(
            worldRequest, receipt, committed.fullBinding(), committed.result());
    var operation = new GameDesignPublicationOperationBinding(selectionAccount(selection), world);
    var release = release(operation, versionNumber);
    var terminal =
        new GameDesignPublicationTerminalEvidence(
            operation.canonicalBytes(),
            GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED,
            release,
            6L);

    List<PolicySpec> specs =
        java.util.Arrays.stream(requestedPolicies)
            .sorted(
                java.util.Comparator.comparing(PolicySpec::worldSlug)
                    .thenComparing(PolicySpec::realmSlug))
            .toList();
    if (specs.isEmpty()) {
      throw new IllegalArgumentException("At least one complete published policy is required");
    }
    List<PublishedRealmEntryPolicyEvidence> policies =
        specs.stream()
            .map(
                spec -> {
                  RealmEntryPolicy policy = policy(spec);
                  UUID policyId =
                      stableUuid("policy/" + canonicalVersionId + "/" + spec.realmSlug());
                  UUID revisionId =
                      stableUuid("revision/" + canonicalVersionId + "/" + spec.realmSlug());
                  String logicalRevisionId =
                      "realm-policy/" + spec.worldSlug() + "/" + spec.realmSlug();
                  String policyDigest =
                      PublishedRealmEntryPolicySetEvidence.policyDigest(
                          policyId,
                          target,
                          versionNumber,
                          release.publishedReleaseBundleRef(),
                          release.contentDigest(),
                          workflow,
                          DIGEST,
                          draft.commitId(),
                          revisionId,
                          logicalRevisionId,
                          policy);
                  return new PublishedRealmEntryPolicyEvidence(
                      policyId,
                      draft.commitId(),
                      revisionId,
                      logicalRevisionId,
                      policy,
                      policyDigest);
                })
            .toList();
    byte[] capture = capture(operation, draft, "43", specs, policies);
    String setDigest =
        PublishedRealmEntryPolicySetEvidence.calculatePolicySetDigest(
            target,
            versionNumber,
            draft.commitId(),
            "43",
            release.publishedReleaseBundleRef(),
            release.contentDigest(),
            workflow,
            DIGEST,
            terminal.publicationVersionStateEpoch(),
            operation.canonicalBytes(),
            capture,
            terminal.canonicalBytes(),
            policies);
    PublishedRealmEntryPolicySetEvidence evidence =
        new PublishedRealmEntryPolicySetEvidence(
            target,
            versionNumber,
            draft.commitId(),
            "43",
            release.publishedReleaseBundleRef(),
            release.contentDigest(),
            workflow,
            DIGEST,
            terminal.publicationVersionStateEpoch(),
            operation.canonicalBytes(),
            capture,
            terminal.canonicalBytes(),
            policies.size(),
            setDigest,
            policies);
    if (!evidence.hasValidDigest()) {
      throw new IllegalStateException("Canonical Game Design policy evidence fixture is invalid");
    }
    return new Fixture(target, evidence, operation, terminal, capture);
  }

  public static CanonicalPublishedPlayerRoute canonicalPublishedRoute() {
    var fixture = fixture(policy("main", true, true, "SHARED"));
    var route =
        new CanonicalPlayableTarget(
            "test",
            "tenant-key",
            uuid("11111111-1111-4111-8111-111111111111"),
            "earth",
            "Earth",
            uuid("22222222-2222-4222-8222-222222222222"),
            "main",
            "main",
            101L,
            202L,
            uuid("33333333-3333-4333-8333-333333333333"),
            "SHARED",
            uuid("44444444-4444-4444-8444-444444444444"),
            fixture.target().canonicalVersionId(),
            303L,
            1L,
            7L,
            "d".repeat(64),
            82L,
            "first-open-operation-17",
            "a".repeat(64),
            OriginKind.NO_PRIOR_POINTER,
            null,
            uuid("99999999-9999-4999-8999-999999999999"),
            uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            "sha256:" + "c".repeat(64),
            27L,
            "sha256:" + "f".repeat(64),
            CanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
            false,
            Instant.parse("2026-10-07T00:00:00Z"),
            "PRESEEDED_ONLY");
    return new CanonicalPublishedPlayerRoute(
        route, fixture.set(), fixture.set().policies().getFirst());
  }

  public static CanonicalGameplayRosterClient.PreseededRosterSnapshot rosterSnapshot(
      CanonicalPublishedPlayerRoute route,
      UUID accountUuid,
      List<CanonicalGameplayRosterClient.RosterActor> actors) {
    var target = route.route();
    var publishedTarget =
        CanonicalGameplayRosterTarget.newBuilder()
            .setTenantUuid(target.canonicalTenantId().toString())
            .setRealmUuid(target.realmId().toString())
            .setWorldSlug(target.worldSlug())
            .setRealmSlug(target.realmSlug())
            .setGameInstanceUuid(target.canonicalGameInstanceId().toString())
            .setCatalogRevision(target.catalogRevision())
            .setPointerVersion(target.pointerVersion())
            .setActiveWorldEpoch(target.activeWorldEpoch())
            .setCanonicalVersionUuid(target.canonicalVersionId().toString())
            .setPublishedPolicyDigest(
                route.selectedPolicyEvidence().policyDigest().substring("sha256:".length()))
            .setPublishedReleaseBundleRef(route.policySetEvidence().publishedReleaseBundleRef())
            .setAdmissionPointerSnapshotDigest(target.admissionPointerSnapshotDigest())
            .setPublishedOwnerProofDigest(target.ownerProofDigest().substring("sha256:".length()))
            .setPlayableStateNamespaceUuid(target.playableStateNamespaceId().toString())
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setEntryPolicy(
                CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY)
            .build();
    return new CanonicalGameplayRosterClient.PreseededRosterSnapshot(
        accountUuid,
        uuid("88888888-8888-4888-8888-888888888888"),
        "b".repeat(64),
        publishedTarget,
        actors);
  }

  public static PolicySpec policy(
      String realmSlug, boolean visible, boolean publicProduction, String scope) {
    return new PolicySpec("earth", "Earth", realmSlug, realmSlug, visible, publicProduction, scope);
  }

  private static AccountPublicationAuthorizationBinding selectionAccount(
      AuthoredDraftPublishSelectionBinding selection) {
    return new AccountPublicationAuthorizationBinding(
        uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
        uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
        new AccountPublicationAuthorizationBinding.PreallocationInput(ACTOR_ID, selection),
        List.of(
            new SourceEvidence(
                SourceKind.GLOBAL_ROLES,
                ACTOR_ID.toString(),
                "1",
                "1",
                null,
                null,
                new byte[] {1, 2, 3})));
  }

  private static AuthoredDraftPublishSelectionBinding selection(
      TargetProof target, DraftCommitBinding draft) {
    String request = "publication-request";
    return AuthoredDraftPublishSelectionBinding.capture(
        new AuthoredDraftPublishSelectionBinding.PublishIntent(
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            request,
            "5",
            "test publication",
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
            java.time.OffsetDateTime.parse("2026-10-07T00:00:00Z")));
  }

  private static DraftCommitBinding freshGraphBinding(TargetProof target) {
    String declaration =
        JSON.writeValueAsString(
            Map.of(
                "tenantId", target.canonicalTenantId().toString(),
                "versionId", target.canonicalVersionId().toString(),
                "startLocation",
                    Map.of(
                        "tenantId", target.canonicalTenantId().toString(),
                        "versionId", target.canonicalVersionId().toString(),
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
        target,
        DRAFT_REQUEST_ID,
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
      UUID revisionId, String family, UUID templateId, String declaration) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("logicalRevisionId", revisionId.toString());
    payload.put("commitId", COMMIT_ID.toString());
    payload.put("aggregateType", family);
    payload.put("aggregateId", templateId.toString());
    if (declaration != null) {
      try {
        payload.put("freshGraphDeclaration", JSON.readTree(declaration));
      } catch (Exception impossible) {
        throw new IllegalStateException(impossible);
      }
    }
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
            DRAFT_REQUEST_ID,
            COMMIT_ID,
            DRAFT_FENCE_ID,
            ACTOR_ID,
            draft.target().canonicalTenantId(),
            draft.target().canonicalVersionId(),
            draft.baseCommitId(),
            "0",
            draftBytes,
            draftBytes,
            draft.digest(),
            List.of(
                new SourceEvidence(
                    SourceKind.GLOBAL_ROLES,
                    ACTOR_ID.toString(),
                    null,
                    "1",
                    null,
                    null,
                    new byte[] {4, 5})))
        .canonicalBytes();
  }

  private static DraftAuthorizationFenceBinding.OwnerReadback committedFreshGraphReadback(
      WorldDraftTerminalReadEvidence.Request request) {
    try {
      var account = request.accountBinding();
      DraftCommitBinding draft =
          DraftCommitBinding.fromStored(
              new String(account.gameDesignBinding(), StandardCharsets.UTF_8),
              account.inputDigest());
      var operation = new ByteArrayOutputStream();
      var frames = new DataOutputStream(operation);
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
              VERSION_IDENTITY_OPERATION_ID.toString(),
              Long.toString(draft.target().gameDesignVersionRowId()),
              INTAKE_REQUEST_ID.toString(),
              INTAKE_OPERATION_ID.toString(),
              "a".repeat(64),
              "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
              "b".repeat(64),
              "c".repeat(64))) text.accept(value);
      text.accept(sha256(account.canonicalBytes()));
      frame.accept(account.canonicalBytes());

      byte[] graph = graphBytes(request, draft);
      String graphDigest = sha256(graph);
      var receipt =
          WorldDraftStartLocationEvidence.create(
              request.targetNamespace(),
              account.operationId(),
              account.requestId(),
              account.commitId(),
              account.fenceId(),
              sha256(account.canonicalBytes()),
              draft.digest(),
              new RoomTemplateRef(
                  draft.target().canonicalTenantId(),
                  draft.target().canonicalVersionId(),
                  ROOM_TEMPLATE_ID),
              graphDigest);
      byte[] receiptBytes = receipt.canonicalBytes();
      var result = new LinkedHashMap<String, Object>();
      result.put("schema", "world-draft-graph-applied/v2");
      result.put("status", "APPLIED");
      result.put(
          "operationBytesBase64", Base64.getEncoder().encodeToString(operation.toByteArray()));
      result.put("graphBytesBase64", Base64.getEncoder().encodeToString(graph));
      result.put("graphDigest", graphDigest);
      result.put("startLocationReceiptBase64", Base64.getEncoder().encodeToString(receiptBytes));
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
                              Long.toString(Long.parseLong(unit.expectedEpoch()) + 1)))
              .toList());
      byte[] resultBytes = canonical(JSON.valueToTree(result));
      return new DraftAuthorizationFenceBinding.OwnerReadback(
          Owner.WORLD,
          Outcome.COMMITTED,
          account.operationId(),
          account.commitId(),
          account.fenceId(),
          account.inputDigest(),
          account.canonicalBytes(),
          resultBytes);
    } catch (Exception invalid) {
      throw new IllegalStateException("Fresh World evidence fixture could not be created", invalid);
    }
  }

  private static byte[] graphBytes(
      WorldDraftTerminalReadEvidence.Request request, DraftCommitBinding draft) throws Exception {
    var root = new LinkedHashMap<String, Object>();
    root.put("schemaVersion", "2");
    root.put("canonicalTenantId", draft.target().canonicalTenantId().toString());
    root.put("canonicalVersionId", draft.target().canonicalVersionId().toString());
    var rows = new ArrayList<Map<String, Object>>();
    int mappingId = 1;
    long privateRowKey = 101;
    for (RevisionPayload revision : draft.revisions()) {
      if (revision.owner() != DraftCommitBinding.Owner.WORLD_MANAGEMENT) continue;
      JsonNode mutation = JSON.readTree(revision.payload());
      String family =
          mutation.get("aggregateType").textValue().replace("WORLD_DESIGN_AGGREGATE_TYPE_", "");
      var mapping = new LinkedHashMap<String, Object>();
      mapping.put("id", mappingId++);
      mapping.put("target_namespace", request.targetNamespace());
      mapping.put("canonical_tenant_id", draft.target().canonicalTenantId().toString());
      mapping.put("canonical_version_id", draft.target().canonicalVersionId().toString());
      mapping.put("family", family);
      mapping.put("template_id", mutation.get("aggregateId").textValue());
      mapping.put("private_row_key", privateRowKey++);
      mapping.put("tenant_id", 11);
      mapping.put("version_id", draft.target().gameDesignVersionRowId());
      mapping.put("version_identity_operation_id", VERSION_IDENTITY_OPERATION_ID.toString());
      mapping.put("request_id", draft.requestId().toString());
      mapping.put("commit_id", draft.commitId().toString());
      mapping.put("revision_id", revision.revisionId().toString());
      mapping.put("revision_order", revision.revisionOrder());
      rows.add(Map.of("mapping", mapping, "content", Map.of()));
    }
    root.put("rows", rows);
    return JSON.writeValueAsBytes(root);
  }

  private static byte[] capture(
      GameDesignPublicationOperationBinding operation,
      DraftCommitBinding binding,
      String sourceEpoch,
      List<PolicySpec> specs,
      List<PublishedRealmEntryPolicyEvidence> policies) {
    try {
      List<Map<String, Object>> entries =
          java.util.stream.IntStream.range(0, specs.size())
              .mapToObj(
                  index -> {
                    Map<String, Object> source = new LinkedHashMap<>();
                    source.put("commitId", policies.get(index).sourceCommitId().toString());
                    source.put("revisionId", policies.get(index).sourceRevisionId().toString());
                    source.put("logicalRevisionId", policies.get(index).logicalRevisionId());
                    try {
                      source.put(
                          "policy", JSON.readTree(policies.get(index).policy().canonicalJson()));
                    } catch (Exception impossible) {
                      throw new IllegalStateException(impossible);
                    }
                    return source;
                  })
              .toList();
      var snapshot = new LinkedHashMap<String, Object>();
      snapshot.put("schema", PublishedRealmEntryPolicySetEvidence.SNAPSHOT_SCHEMA);
      snapshot.put("bindingJson", binding.canonicalJson());
      snapshot.put("bindingDigest", binding.digest());
      snapshot.put("sourceEpoch", sourceEpoch);
      snapshot.put("policies", entries);
      byte[] snapshotBytes =
          Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(snapshot));
      ByteArrayOutputStream capture = new ByteArrayOutputStream();
      DraftAuthorizationFenceBinding.frame(
          capture, PublishedRealmEntryPolicySetEvidence.CAPTURE_SCHEMA);
      DraftAuthorizationFenceBinding.frame(capture, operation.canonicalBytes());
      DraftAuthorizationFenceBinding.frame(capture, snapshotBytes);
      return capture.toByteArray();
    } catch (Exception invalid) {
      throw new IllegalStateException(
          "Policy source capture fixture could not be created", invalid);
    }
  }

  private static ReleaseContent release(
      GameDesignPublicationOperationBinding operation, int versionNumber) {
    var request = operation.world().request();
    List<Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new Participant(
                        owner,
                        Long.toString(
                            operation
                                .account()
                                .input()
                                .selection()
                                .target()
                                .gameDesignVersionRowId()),
                        null,
                        request.appliedCommitId(),
                        owner.equals("WORLD_MANAGEMENT") ? request.contentDigest() : "c".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner),
                        owner.equals("GAME_LOGIC") ? DIGEST : null,
                        null,
                        null))
            .toList();
    return new ReleaseContent(
        request.canonicalTenantId(),
        request.canonicalVersionId(),
        "bundle:exact",
        versionNumber,
        "v2",
        request.publishWorkflowId(),
        DIGEST,
        1,
        List.of(),
        List.of(),
        participants,
        List.of("LOOK"),
        "generation-1",
        operation.world());
  }

  private static RealmEntryPolicy policy(PolicySpec spec) {
    String json =
        "{\"schemaVersion\":1,\"worldSlug\":\""
            + spec.worldSlug()
            + "\",\"worldDisplayName\":\""
            + spec.worldDisplayName()
            + "\",\"realmSlug\":\""
            + spec.realmSlug()
            + "\",\"realmDisplayName\":\""
            + spec.realmDisplayName()
            + "\",\"visible\":"
            + spec.visible()
            + ",\"publicProduction\":"
            + spec.publicProduction()
            + ",\"stateScope\":\""
            + spec.stateScope()
            + "\",\"entryPolicy\":\"PRESEEDED_ONLY\"}";
    return RealmEntryPolicy.parse(json, JSON);
  }

  private static byte[] canonical(JsonNode node) throws Exception {
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(node));
  }

  private static String sha256(byte[] bytes) throws Exception {
    return "sha256:"
        + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static UUID stableUuid(String text) {
    return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
  }

  public static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  public record PolicySpec(
      String worldSlug,
      String worldDisplayName,
      String realmSlug,
      String realmDisplayName,
      boolean visible,
      boolean publicProduction,
      String stateScope) {}

  public record Fixture(
      TargetProof target,
      PublishedRealmEntryPolicySetEvidence set,
      GameDesignPublicationOperationBinding operation,
      GameDesignPublicationTerminalEvidence terminal,
      byte[] captureBytes) {
    public Fixture {
      captureBytes = captureBytes.clone();
    }

    @Override
    public byte[] captureBytes() {
      return captureBytes.clone();
    }
  }
}
