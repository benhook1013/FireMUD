package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.stub.AbstractStub;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationTerminalReadClient;
import net.firedevops.firemud.common.publication.GameDesignPublicationTerminalReadGrpcCodec;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.v1.GameDesignPublicationTerminalReadServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ReadPublicationTerminalResponse;
import net.firedevops.firemud.test.IsolatedWorldPublicationInventoryFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WorldGameDesignPublicationTerminalVerifierTest {
  private static final String NAMESPACE = "test";

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void authenticatesSameNamespaceGameDesignCallerBeforeDecodingOrCallingClient() {
    var client = mock(GameDesignPublicationTerminalReadClient.class);
    var verifier = new WorldGameDesignPublicationTerminalVerifier(NAMESPACE, client);

    assertThatThrownBy(() -> verifier.authenticateAndVerifyAndHold(new byte[] {1}, new byte[] {2}))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("same-namespace Game Design");
    assertThatThrownBy(
            () ->
                withPeer(
                    peer("other", "game-design-service"),
                    () -> verifier.authenticateAndVerifyAndHold(new byte[] {1}, new byte[] {2})))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                withPeer(
                    peer(NAMESPACE, "game-session-service"),
                    () -> verifier.authenticateAndVerifyAndHold(new byte[] {1}, new byte[] {2})))
        .isInstanceOf(SecurityException.class);

    verifyNoInteractions(client);
  }

  @Test
  void requiresNoAmbientTransactionAndRejectsMalformedOperationBeforeRemoteRead() throws Exception {
    var client = mock(GameDesignPublicationTerminalReadClient.class);
    var verifier = new WorldGameDesignPublicationTerminalVerifier(NAMESPACE, client);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(
              () ->
                  withPeer(
                      peer(NAMESPACE, "game-design-service"),
                      () -> verifier.authenticateAndVerifyAndHold(new byte[] {1}, new byte[] {2})))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("outside owner transactions");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    verifyNoInteractions(client);

    assertThatThrownBy(
            () ->
                withPeer(
                    peer(NAMESPACE, "game-design-service"),
                    () -> verifier.authenticateAndVerifyAndHold(new byte[] {1}, new byte[] {2})))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(client);
  }

  @Test
  // The real client and codec are exercised over a stubbed channel; this is not physical mTLS
  // proof.
  void authenticatedExactCanonicalReadReturnsClosableAuthorityThroughTheRealClientCodec(
      @TempDir Path directory) throws Exception {
    var operation = operation();
    var terminal = terminal(operation, Outcome.NO_PUBLICATION);
    try (var upstream = new StubbedGameDesignClient(directory)) {
      upstream.respondWith(
          wireRequest -> {
            var readRequest = GameDesignPublicationTerminalReadGrpcCodec.fromRequest(wireRequest);
            return GameDesignPublicationTerminalReadGrpcCodec.toResponse(readRequest, terminal);
          });
      var verifier = new WorldGameDesignPublicationTerminalVerifier(NAMESPACE, upstream.client);
      var verified =
          withPeer(
              peer(NAMESPACE, "game-design-service"),
              () ->
                  verifier.authenticateAndVerifyAndHold(
                      operation.canonicalBytes(), terminal.canonicalBytes()));

      assertThat(verified.request().operationBytes()).containsExactly(operation.canonicalBytes());
      assertThat(verified.request().terminalBytes()).containsExactly(terminal.canonicalBytes());
      verified.authority().requireHeld();
      verified.authority().close();
      assertThatThrownBy(verified.authority()::requireHeld)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no longer valid");
      assertThat(upstream.expectedServerIdentity())
          .isEqualTo("spiffe://firemud/ns/test/sa/game-design-service");
      verify(upstream.stub).readPublicationTerminal(any());
    }
  }

  @Test
  void rejectsChangedReadEchoAndSubstitutedCanonicalTerminal(@TempDir Path directory)
      throws Exception {
    var operation = operation();
    var expectedTerminal = terminal(operation, Outcome.NO_PUBLICATION);
    var substitutedTerminal = terminal(operation, Outcome.PUBLISHED);
    try (var upstream = new StubbedGameDesignClient(directory)) {
      var verifier = new WorldGameDesignPublicationTerminalVerifier(NAMESPACE, upstream.client);
      upstream.respondWith(
          wireRequest -> {
            var readRequest = GameDesignPublicationTerminalReadGrpcCodec.fromRequest(wireRequest);
            return GameDesignPublicationTerminalReadGrpcCodec.toResponse(
                    readRequest, expectedTerminal)
                .toBuilder()
                .setReadRequestId(UUID.randomUUID().toString())
                .build();
          });
      assertThatThrownBy(
              () ->
                  withPeer(
                      peer(NAMESPACE, "game-design-service"),
                      () ->
                          verifier.authenticateAndVerifyAndHold(
                              operation.canonicalBytes(), expectedTerminal.canonicalBytes())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid publication terminal evidence");

      upstream.respondWith(
          wireRequest -> {
            var readRequest = GameDesignPublicationTerminalReadGrpcCodec.fromRequest(wireRequest);
            return GameDesignPublicationTerminalReadGrpcCodec.toResponse(
                readRequest, substitutedTerminal);
          });
      assertThatThrownBy(
              () ->
                  withPeer(
                      peer(NAMESPACE, "game-design-service"),
                      () ->
                          verifier.authenticateAndVerifyAndHold(
                              operation.canonicalBytes(), expectedTerminal.canonicalBytes())))
          .isInstanceOf(SecurityException.class)
          .hasMessageContaining("differs from the submitted exact operation or result");
    }
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static <T> T withPeer(GrpcPeerIdentity identity, Callable<T> task) throws Exception {
    return Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, identity).call(task);
  }

  private static GameDesignPublicationOperationBinding operation() throws Exception {
    // Canonical structural inputs only; this fixture does not stipulate real owner commits or
    // source holds.
    var upstream = freshGraphRequest();
    var applied = committedFreshGraphReadback(upstream);
    var original = upstream.accountBinding();
    var draft =
        DraftCommitBinding.fromStored(
            new String(original.gameDesignBinding(), StandardCharsets.UTF_8),
            original.inputDigest());
    var target = draft.target();
    var intent =
        new AuthoredDraftPublishSelectionBinding.PublishIntent(
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            "publication-request",
            "5",
            "fixture",
            draft.requestId(),
            draft.commitId(),
            draft.digest());
    var selection =
        AuthoredDraftPublishSelectionBinding.capture(
            intent,
            target,
            draft,
            new AuthoredDraftPublishSelectionBinding.VisibilityFence(
                target,
                draft.requestId(),
                draft.commitId(),
                draft.digest(),
                "[]",
                OffsetDateTime.parse("2026-10-01T00:00:00Z")));
    var publicationSources =
        List.of(
            new SourceEvidence(
                SourceKind.ACCOUNT,
                original.actorAccountId().toString(),
                "1",
                "1",
                null,
                null,
                "stipulated-publication-account-source".getBytes(StandardCharsets.UTF_8)));
    var account =
        new AccountPublicationAuthorizationBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new AccountPublicationAuthorizationBinding.PreallocationInput(
                original.actorAccountId(), selection),
            publicationSources);
    var tuples =
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
    String workflow =
        PublicationDigestRequestBinding.full(
                target.canonicalTenantId().toString(),
                Long.toString(target.gameDesignVersionRowId()),
                intent.publishRequestId())
            .derivedWorkflowIdentity();
    var result = JsonMapper.builder().build().readTree(applied.result());
    var appliedOperation =
        new DraftAuthorizationFenceBinding.FrameReader(
            Base64.getDecoder().decode(result.get("operationBytesBase64").textValue()));
    appliedOperation.expect("world-draft-terminal-operation/v1");
    for (int frame = 1; frame < 13; frame++) appliedOperation.bytes();
    UUID intakeRequestId = UUID.fromString(appliedOperation.text());
    var worldRequest =
        new WorldPublishedStartLocationEvidence.Request(
            NAMESPACE,
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            intakeRequestId,
            UUID.randomUUID(),
            intent.publishRequestId(),
            selection.digest().substring(7),
            5L,
            workflow,
            draft.commitId().toString(),
            "b".repeat(64),
            3,
            tuples);
    var world =
        new WorldPublishedStartLocationEvidence(
            worldRequest,
            Base64.getDecoder().decode(result.get("startLocationReceiptBase64").textValue()),
            applied.fullBinding(),
            applied.result());
    return new GameDesignPublicationOperationBinding(
        account, world, IsolatedWorldPublicationInventoryFixtures.stipulated(account, world));
  }

  private static GameDesignPublicationTerminalEvidence terminal(
      GameDesignPublicationOperationBinding operation, Outcome outcome) {
    if (outcome == Outcome.NO_PUBLICATION) {
      return new GameDesignPublicationTerminalEvidence(
          operation.canonicalBytes(), outcome, null, null);
    }
    var selection = operation.account().input().selection();
    var world = operation.world().request();
    var participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new GameDesignPublicationTerminalEvidence.Participant(
                        owner,
                        Long.toString(selection.target().gameDesignVersionRowId()),
                        null,
                        world.appliedCommitId(),
                        "WORLD_MANAGEMENT".equals(owner) ? world.contentDigest() : "c".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner, AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "d".repeat(64) : null,
                        null,
                        null))
            .toList();
    var release =
        new GameDesignPublicationTerminalEvidence.ReleaseContent(
            selection.intent().canonicalTenantId(),
            selection.intent().canonicalVersionId(),
            "fixture-bundle",
            1,
            "v2",
            world.publishWorkflowId(),
            "sha256:" + "a".repeat(64),
            1,
            List.of(),
            List.of(),
            participants,
            List.of(),
            "generation-1",
            operation.world());
    return new GameDesignPublicationTerminalEvidence(
        operation.canonicalBytes(), outcome, release, 6L);
  }

  private static WorldDraftTerminalReadEvidence.Request freshGraphRequest() throws Exception {
    var draft = freshGraphBinding();
    return new WorldDraftTerminalReadEvidence.Request(
        1, NAMESPACE, uuid("33333333-3333-4333-8333-333333333333"), accountBinding(draft));
  }

  private static DraftCommitBinding freshGraphBinding() throws Exception {
    UUID tenantId = uuid("11111111-1111-4111-8111-111111111111");
    UUID versionId = uuid("22222222-2222-4222-8222-222222222222");
    UUID roomTemplateId = uuid("77777777-7777-4777-8777-777777777777");
    UUID regionTemplateId = uuid("88888888-8888-4888-8888-888888888888");
    UUID zoneTemplateId = uuid("99999999-9999-4999-8999-999999999999");
    UUID requestId = uuid("44444444-4444-4444-8444-444444444444");
    UUID commitId = uuid("55555555-5555-4555-8555-555555555555");
    var json = JsonMapper.builder().build();
    String declaration =
        json.writeValueAsString(
            Map.of(
                "tenantId", tenantId.toString(),
                "versionId", versionId.toString(),
                "startLocation",
                    Map.of(
                        "tenantId", tenantId.toString(),
                        "versionId", versionId.toString(),
                        "roomTemplateId", roomTemplateId.toString()),
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
                            0)),
                "inboundSourceClosure", inboundSourceClosure()));
    UUID regionRevision = uuid("12345678-1234-4234-8234-123456789001");
    UUID zoneRevision = uuid("12345678-1234-4234-8234-123456789002");
    UUID roomRevision = uuid("12345678-1234-4234-8234-123456789003");
    return DraftCommitBinding.create(
        new TargetProof(tenantId, versionId, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW"),
        requestId,
        commitId,
        "base-1",
        List.of(
            new RevisionPayload(
                "0",
                regionRevision,
                DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                worldRevisionPayload(
                    json,
                    commitId,
                    regionRevision,
                    "WORLD_DESIGN_AGGREGATE_TYPE_REGION",
                    regionTemplateId,
                    declaration)),
            new RevisionPayload(
                "1",
                zoneRevision,
                DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                worldRevisionPayload(
                    json,
                    commitId,
                    zoneRevision,
                    "WORLD_DESIGN_AGGREGATE_TYPE_ZONE",
                    zoneTemplateId,
                    null)),
            new RevisionPayload(
                "2",
                roomRevision,
                DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                worldRevisionPayload(
                    json,
                    commitId,
                    roomRevision,
                    "WORLD_DESIGN_AGGREGATE_TYPE_ROOM",
                    roomTemplateId,
                    null))),
        List.of(
                affected("REGION", regionTemplateId, "REGION_SUBTREE", regionTemplateId),
                affected("ZONE", zoneTemplateId, "REGION_SUBTREE", regionTemplateId),
                affected("ROOM", roomTemplateId, "ZONE_SUBTREE", zoneTemplateId))
            .stream()
            .flatMap(List::stream)
            .toList());
  }

  private static String worldRevisionPayload(
      JsonMapper json,
      UUID commitId,
      UUID revisionId,
      String family,
      UUID templateId,
      String declaration)
      throws Exception {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("logicalRevisionId", revisionId.toString());
    payload.put("commitId", commitId.toString());
    payload.put("aggregateType", family);
    payload.put("aggregateId", templateId.toString());
    if (declaration != null) payload.put("freshGraphDeclaration", json.readTree(declaration));
    return json.writeValueAsString(payload);
  }

  private static Map<String, Object> inboundSourceClosure() {
    return Map.of(
        "schemaVersion",
        1,
        "familyCounts",
        List.of(
            inboundFamily("WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ROOT"),
            inboundFamily("WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ATTACHMENT"),
            inboundFamily("WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_SELECTION"),
            inboundFamily("WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_BINDING"),
            inboundFamily("WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_HOOK"),
            inboundFamily("WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_SCRIPT_REFERENCE"),
            inboundFamily("WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_TARGET_BINDING")));
  }

  private static Map<String, Object> inboundFamily(String family) {
    return Map.of("family", family, "count", 0);
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
            uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            draft.requestId(),
            draft.commitId(),
            uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            draft.target().canonicalTenantId(),
            draft.target().canonicalVersionId(),
            "base-1",
            "0",
            draftBytes,
            draftBytes,
            draft.digest(),
            List.of(
                new SourceEvidence(
                    SourceKind.GLOBAL_ROLES,
                    "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                    null,
                    "1",
                    null,
                    null,
                    new byte[] {4, 5})))
        .canonicalBytes();
  }

  private static DraftAuthorizationFenceBinding.OwnerReadback committedFreshGraphReadback(
      WorldDraftTerminalReadEvidence.Request request) throws Exception {
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
          } catch (IOException impossible) {
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
    byte[] graph = graphBytes(request, draft);
    String graphDigest = sha256(graph);
    var result = new LinkedHashMap<String, Object>();
    result.put("schema", "world-draft-graph-applied/v2");
    result.put("status", "APPLIED");
    result.put("operationBytesBase64", Base64.getEncoder().encodeToString(operation.toByteArray()));
    result.put("graphBytesBase64", Base64.getEncoder().encodeToString(graph));
    result.put("graphDigest", graphDigest);
    var receipt = receipt(request, draft, graphDigest);
    byte[] receiptBytes = canonical(receipt);
    result.put("startLocationReceiptBase64", Base64.getEncoder().encodeToString(receiptBytes));
    result.put("startLocationReceiptDigest", receipt.get("receiptDigest").textValue());
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
    return new DraftAuthorizationFenceBinding.OwnerReadback(
        DraftAuthorizationFenceBinding.Owner.WORLD,
        DraftAuthorizationFenceBinding.Outcome.COMMITTED,
        account.operationId(),
        account.commitId(),
        account.fenceId(),
        account.inputDigest(),
        account.canonicalBytes(),
        canonical(JsonMapper.builder().build().valueToTree(result)));
  }

  private static byte[] graphBytes(
      WorldDraftTerminalReadEvidence.Request request, DraftCommitBinding draft) throws Exception {
    var root = new LinkedHashMap<String, Object>();
    root.put("schemaVersion", "3");
    root.put("canonicalTenantId", draft.target().canonicalTenantId().toString());
    root.put("canonicalVersionId", draft.target().canonicalVersionId().toString());
    var rows = new ArrayList<Map<String, Object>>();
    int mappingId = 1;
    long privateRowKey = 101;
    var json = JsonMapper.builder().build();
    root.put(
        "inboundSourceClosure",
        json.readTree(draft.revisions().getFirst().payload())
            .get("freshGraphDeclaration")
            .get("inboundSourceClosure"));
    for (RevisionPayload revision : draft.revisions()) {
      if (revision.owner() != DraftCommitBinding.Owner.WORLD_MANAGEMENT) continue;
      JsonNode mutation = json.readTree(revision.payload());
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
      mapping.put("version_id", 19);
      mapping.put("version_identity_operation_id", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
      mapping.put("request_id", draft.requestId().toString());
      mapping.put("commit_id", draft.commitId().toString());
      mapping.put("revision_id", revision.revisionId().toString());
      mapping.put("revision_order", revision.revisionOrder());
      rows.add(Map.of("mapping", mapping, "content", Map.of()));
    }
    root.put("rows", rows);
    return json.writeValueAsBytes(root);
  }

  private static tools.jackson.databind.node.ObjectNode receipt(
      WorldDraftTerminalReadEvidence.Request request, DraftCommitBinding draft, String graphDigest)
      throws Exception {
    var account = request.accountBinding();
    var selector =
        Map.of(
            "tenantId", account.tenantId().toString(),
            "versionId", account.versionId().toString(),
            "roomTemplateId", "77777777-7777-4777-8777-777777777777");
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
            account.tenantId(),
            account.versionId(),
            uuid("77777777-7777-4777-8777-777777777777"),
            graphDigest);
    var value = new LinkedHashMap<String, Object>();
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
    return (tools.jackson.databind.node.ObjectNode) JsonMapper.builder().build().valueToTree(value);
  }

  private static String startLocationReceiptDigest(
      String namespace,
      UUID operationId,
      UUID requestId,
      UUID commitId,
      UUID fenceId,
      String accountDigest,
      String bindingDigest,
      UUID tenantId,
      UUID versionId,
      UUID roomId,
      String graphDigest)
      throws Exception {
    var framed = new ByteArrayOutputStream();
    for (String value :
        List.of(
            "world-draft-start-location-receipt/v1",
            namespace,
            operationId.toString(),
            requestId.toString(),
            commitId.toString(),
            fenceId.toString(),
            accountDigest,
            bindingDigest,
            tenantId.toString(),
            versionId.toString(),
            roomId.toString(),
            graphDigest)) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      framed.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
      framed.writeBytes(bytes);
    }
    return sha256(framed.toByteArray());
  }

  private static byte[] canonical(JsonNode value) throws Exception {
    return net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
        JsonMapper.builder().build().writeValueAsString(value));
  }

  private static String sha256(byte[] bytes) throws Exception {
    return "sha256:"
        + java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static final class StubbedGameDesignClient implements AutoCloseable {
    private final GameDesignPublicationTerminalReadClient client;
    private final String serverIdentity;
    private final GameDesignPublicationTerminalReadServiceGrpc
            .GameDesignPublicationTerminalReadServiceBlockingStub
        stub;

    private StubbedGameDesignClient(Path directory) throws Exception {
      var endpoints = new ServiceEndpointsProperties();
      endpoints.setGameDesignService("design.internal:6565");
      var tls = new CommonGrpcClientProperties();
      tls.setCertChain(
          Files.writeString(directory.resolve("client.crt"), "certificate").toString());
      tls.setPrivateKey(
          Files.writeString(directory.resolve("client.key"), "private key").toString());
      tls.setCaCert(Files.writeString(directory.resolve("ca.crt"), "CA certificate").toString());
      var channelFactory = mock(GrpcChannelFactory.class);
      var channel = mock(ManagedChannel.class);
      when(channelFactory.buildChannel(
              org.mockito.ArgumentMatchers.eq("design.internal:6565"),
              org.mockito.ArgumentMatchers.eq(6565),
              any(CommonGrpcClientProperties.class),
              org.mockito.ArgumentMatchers.eq(true)))
          .thenReturn(channel);
      client =
          new GameDesignPublicationTerminalReadClient(endpoints, tls, channelFactory, NAMESPACE);
      client.init();
      Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
      field.setAccessible(true);
      AbstractStub<?> initialized = (AbstractStub<?>) field.get(client);
      var credentials = initialized.getCallOptions().getCredentials();
      if (!(credentials instanceof GrpcServerPeerIdentityCallCredentials)) {
        throw new AssertionError("Strict Game Design peer identity credentials are required");
      }
      Field peerField = credentials.getClass().getDeclaredField("expectedPeerUri");
      peerField.setAccessible(true);
      serverIdentity = (String) peerField.get(credentials);
      stub =
          mock(
              GameDesignPublicationTerminalReadServiceGrpc
                  .GameDesignPublicationTerminalReadServiceBlockingStub.class);
      when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
      field.set(client, stub);
    }

    private void respondWith(
        java.util.function.Function<
                net.firedevops.firemud.gamedesign.v1.ReadPublicationTerminalRequest,
                ReadPublicationTerminalResponse>
            response) {
      doAnswer(call -> response.apply(call.getArgument(0)))
          .when(stub)
          .readPublicationTerminal(any());
    }

    private String expectedServerIdentity() throws Exception {
      return serverIdentity;
    }

    @Override
    public void close() throws Exception {
      client.close();
    }
  }
}
