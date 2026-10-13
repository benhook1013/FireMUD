package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.OwnerReadback;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence.Request;
import org.junit.jupiter.api.Test;

/** Real nested codecs over explicitly synthetic source/application/release and lease fixtures. */
class WorldCanonicalPlayerAdmissionHoldEvidenceTest {
  private static final String ID = "11111111-1111-4111-8111-111111111111";
  private static final String ACCOUNT = "22222222-2222-4222-8222-222222222222";

  @Test
  void closedCanonicalRoundtripPreservesEveryOriginalNestedByteAndDigest() throws Exception {
    var original = hold();
    byte[] bytes = original.canonicalBytes();
    var decoded = WorldCanonicalPlayerAdmissionHoldEvidence.fromCanonical(bytes, original.sha256());
    assertThat(decoded).isEqualTo(original);
    assertThat(decoded.canonicalBytes()).containsExactly(bytes);
    assertThat(decoded.request().lease().canonicalJson())
        .isEqualTo(original.request().lease().canonicalJson());
    assertThat(decoded.request().lease().sha256()).isEqualTo(original.request().lease().sha256());
    assertThat(decoded.worldEvidence().canonicalBytes())
        .containsExactly(original.worldEvidence().canonicalBytes());
    assertThat(decoded.diagnosticExpiresAtMillis()).isEqualTo(original.diagnosticExpiresAtMillis());
    bytes[0] ^= 1;
    assertThat(original.canonicalBytes()).isEqualTo(decoded.canonicalBytes());
  }

  @Test
  void rejectsUnknownMissingDuplicateAndNoncanonicalOuterBytes() throws Exception {
    var original = hold();
    var root = JSON.readTree(original.canonicalBytes());
    var unknown = root.deepCopy();
    ((tools.jackson.databind.node.ObjectNode) unknown).put("unknown", "value");
    assertRejected(canonical(unknown));
    var missing = root.deepCopy();
    ((tools.jackson.databind.node.ObjectNode) missing).remove("schemaVersion");
    assertRejected(canonical(missing));
    var nestedUnknown = root.deepCopy();
    ((tools.jackson.databind.node.ObjectNode) nestedUnknown.get("request")).put("unknown", "value");
    assertRejected(canonical(nestedUnknown));
    String text = new String(original.canonicalBytes(), StandardCharsets.UTF_8);
    assertRejected(
        text.replace("\"schemaVersion\":\"1\"", "\"schemaVersion\":\"1\",\"schemaVersion\":\"1\"")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected((" " + text).getBytes(StandardCharsets.UTF_8));
    assertRejected((text + "{}").getBytes(StandardCharsets.UTF_8));
    assertRejected(
        text.replace("\"schemaVersion\":\"1\"", "\"schemaVersion\":1")
            .getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void substitutedLeaseDigestOriginalScopeWorldEpochAndOpaqueIdentityFailClosed() throws Exception {
    var original = hold();
    var root = JSON.readTree(original.canonicalBytes());
    var leaseDigest = root.deepCopy();
    ((tools.jackson.databind.node.ObjectNode) leaseDigest.get("request"))
        .put("accountLeaseSha256", "b".repeat(64));
    assertRejected(canonical(leaseDigest));
    var changedCarrier = new LinkedHashMap<>(original.request().lease().carrier());
    var changedScope = new LinkedHashMap<>(scope(changedCarrier));
    changedScope.put("worldSlug", "another-world");
    changedCarrier.put("bindingScope", changedScope);
    var changedLease = AccountGameplayAdmissionLeaseEvidence.fromCarrier(changedCarrier);
    var changed = root.deepCopy();
    ((tools.jackson.databind.node.ObjectNode) changed.get("request"))
        .put("accountLeaseJson", changedLease.canonicalJson());
    ((tools.jackson.databind.node.ObjectNode) changed.get("request"))
        .put("accountLeaseSha256", changedLease.sha256());
    assertRejected(canonical(changed));
    var epoch = root.deepCopy();
    ((tools.jackson.databind.node.ObjectNode) epoch.get("request"))
        .put("expectedLifecycleEpoch", "4");
    assertRejected(canonical(epoch));
    var uuid = root.deepCopy();
    ((tools.jackson.databind.node.ObjectNode) uuid)
        .put("holdId", "00000000-0000-1000-8000-000000000001");
    assertRejected(canonical(uuid));
    assertThatThrownBy(
            () ->
                WorldCanonicalPlayerAdmissionHoldEvidence.fromCanonical(
                    original.canonicalBytes(), "c".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exactDecimalStringsPreserveHighAndMaximumLongsAndRejectEveryOtherShape() throws Exception {
    var original = hold();
    for (long value : List.of(9_007_199_254_740_993L, Long.MAX_VALUE)) {
      var world = original.worldEvidence();
      var preciseWorld =
          new WorldCanonicalInstanceLifecycleEvidence(
              world.request(),
              world.launchBinding(),
              world.startLocation(),
              world.runtimeRoomInstanceId(),
              "ACTIVE",
              value,
              value,
              world.captureId(),
              world.graphSha256(),
              world.preparationInputDigest(),
              world.operationalRegionAssignments());
      var precise =
          new WorldCanonicalPlayerAdmissionHoldEvidence(
              original.holdId(),
              original.holdFence(),
              new WorldCanonicalPlayerAdmissionHoldEvidence.Request(
                  original.request().lease(), value, value),
              preciseWorld);
      assertThat(WorldCanonicalPlayerAdmissionHoldEvidence.parseCanonical(precise.canonicalBytes()))
          .isEqualTo(precise);
    }
    for (String invalid : List.of("01", "-1", "+1", "1.0", "1e0", " 1", "9223372036854775808")) {
      var changed = JSON.readTree(original.canonicalBytes());
      ((tools.jackson.databind.node.ObjectNode) changed.get("request"))
          .put("expectedRowVersion", invalid);
      assertRejected(canonical(changed));
    }
    var numeric = JSON.readTree(original.canonicalBytes());
    ((tools.jackson.databind.node.ObjectNode) numeric.get("request"))
        .put("expectedLifecycleEpoch", 3);
    assertRejected(canonical(numeric));
    var zeroEpoch = JSON.readTree(original.canonicalBytes());
    ((tools.jackson.databind.node.ObjectNode) zeroEpoch.get("request"))
        .put("expectedLifecycleEpoch", "0");
    assertRejected(canonical(zeroEpoch));
  }

  @Test
  void rejectsUtf8Base64ByteAndNestingBoundsWithoutMockedNestedDecoders() throws Exception {
    assertRejected(new byte[] {(byte) 0xc3, 0x28});
    assertRejected(new byte[WorldCanonicalPlayerAdmissionHoldEvidence.MAX_EVIDENCE_BYTES + 1]);
    assertRejected("{\"request\":{\"a\":{\"b\":{\"c\":{}}}}}".getBytes(StandardCharsets.UTF_8));
    var original = hold();
    var invalidBase64 = JSON.readTree(original.canonicalBytes());
    ((tools.jackson.databind.node.ObjectNode) invalidBase64)
        .put("worldLifecycleEvidenceBase64", "%%%");
    assertRejected(canonical(invalidBase64));
    var oversizedLifecycle = JSON.readTree(original.canonicalBytes());
    ((tools.jackson.databind.node.ObjectNode) oversizedLifecycle)
        .put(
            "worldLifecycleEvidenceBase64",
            Base64.getEncoder()
                .encodeToString(
                    new byte[WorldCanonicalPlayerAdmissionHoldEvidence.MAX_LIFECYCLE_BYTES + 1]));
    assertRejected(canonical(oversizedLifecycle));
    var corruptedWorld = JSON.readTree(original.canonicalBytes());
    ((tools.jackson.databind.node.ObjectNode) corruptedWorld)
        .put(
            "worldLifecycleEvidenceBase64",
            Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8)));
    assertRejected(canonical(corruptedWorld));
  }

  private static void assertRejected(byte[] bytes) throws Exception {
    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    assertThatThrownBy(() -> WorldCanonicalPlayerAdmissionHoldEvidence.fromCanonical(bytes, digest))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static WorldCanonicalPlayerAdmissionHoldEvidence hold() throws Exception {
    var world = lifecycleEvidence();
    var carrier = carrier();
    var boundScope = new LinkedHashMap<>(scope(carrier));
    boundScope.put("worldSlug", world.request().worldSlug());
    boundScope.put(
        "playableStateNamespaceId", world.request().playableStateNamespaceId().toString());
    carrier.put("bindingScope", boundScope);
    var lease = AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier);
    return new WorldCanonicalPlayerAdmissionHoldEvidence(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new WorldCanonicalPlayerAdmissionHoldEvidence.Request(
            lease, world.lifecycleEpoch(), world.rowVersion()),
        world);
  }

  // Only synthetic fixture construction is adapted from existing Common Core codec tests below.

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

  private static WorldCanonicalInstanceLifecycleEvidence lifecycleEvidence() throws Exception {
    WorldPublishedStartLocationEvidence selector = publishedSelector();
    var descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            selector.request().targetNamespace(),
            "world-lifecycle-control-request",
            selector.request().canonicalTenantId(),
            "synthetic-world",
            uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            "sha256:" + "a".repeat(64),
            19L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            descriptorRequest,
            "canonical-instance-launch-descriptor",
            42L,
            false,
            null,
            "{}",
            "generation-revision",
            9L,
            7L,
            "release-bundle",
            false,
            null);
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        selector.request().appliedCommitId(),
                        selector.request().contentDigest(),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner, AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "c".repeat(64) : null))
            .toList();
    AuthoredWorldReleaseAttestationEvidence release =
        AuthoredWorldReleaseAttestationEvidence.create(
            descriptor.targetNamespace(),
            descriptor.resultDigest(),
            descriptor.canonicalTenantId(),
            selector.request().canonicalVersionId(),
            descriptor.worldSlug(),
            descriptor.authoredWorldSourceOperationId(),
            descriptor.authoredWorldSourceEvidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            selector.request().publishWorkflowId(),
            selector.request().appliedCommitId(),
            participants,
            "sha256:" + "d".repeat(64),
            1,
            List.of(),
            List.of(),
            List.of(),
            descriptor.generationConfigRevision(),
            selector);
    CompleteLaunchBindingEvidence binding = new CompleteLaunchBindingEvidence(descriptor, release);
    WorldDraftStartLocationEvidence selectorReceipt =
        WorldDraftStartLocationEvidence.fromStored(selector.selectorReceiptBytes());
    RoomTemplateRef startLocation = selectorReceipt.startLocation();
    Request request =
        new Request(
            Request.SCHEMA_VERSION,
            uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            descriptor.targetNamespace(),
            descriptor.canonicalTenantId(),
            descriptor.worldSlug(),
            uuid("11111111-1111-4111-8111-111111111111"),
            uuid("22222222-2222-4222-8222-222222222222"),
            "SHARED",
            true,
            descriptor.controlPlaneRequestId(),
            release.canonicalVersionId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            release.evidenceDigest());
    return new WorldCanonicalInstanceLifecycleEvidence(
        request,
        binding,
        startLocation,
        1042L,
        "ACTIVE",
        3L,
        0L,
        uuid("33333333-3333-4333-8333-333333333333"),
        selectorReceipt.graphDigest().substring("sha256:".length()),
        "sha256:" + "e".repeat(64),
        java.util.Map.of(
            uuid("11111111-1111-4111-8111-111111111111"),
            uuid("22222222-2222-4222-8222-222222222222")));
  }

  static WorldPublishedStartLocationEvidence publishedSelector() throws Exception {
    WorldDraftTerminalReadEvidence.Request terminalRequest = freshGraphRequest();
    OwnerReadback committed = committedReadback(terminalRequest, true);
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

  static Map<String, Object> carrier() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", AccountGameplayAdmissionLeaseEvidence.SCHEMA);
    value.put("schemaVersion", "1");
    value.put("mode", "PUBLIC_PRODUCTION");
    value.put("targetNamespace", "test");
    value.put("callerWorkload", "spiffe://firemud/ns/test/sa/game-session-service");
    value.put("requestId", ID);
    value.put("leaseId", ACCOUNT);
    value.put("leaseFence", "1");
    value.put("leaseKind", "NEW_BINDING");
    Map<String, Object> scope = new LinkedHashMap<>();
    for (String key :
        List.of(
            "tenantId",
            "realmId",
            "playableStateNamespaceId",
            "gameInstanceId",
            "characterId",
            "sessionId",
            "regionId")) scope.put(key, ID);
    scope.put("accountId", ACCOUNT);
    scope.put("worldSlug", "world");
    scope.put("realmSlug", "realm");
    scope.put("playableStateScope", "SHARED");
    for (String key :
        List.of("bindingGeneration", "catalogRevision", "pointerVersion", "regionEpoch"))
      scope.put(key, "9007199254740993");
    value.put("bindingScope", scope);
    value.put(
        "authorityTuple",
        Map.of(
            "issuerAuthGeneration",
            "1",
            "accountAuthorityGeneration",
            "1",
            "tenantAuthorityGeneration",
            Map.of(ID, "1"),
            "membershipAuthorityGeneration",
            Map.of(ID, "1"),
            "privateRealmGrantVersions",
            List.of()));
    value.put("issuanceFence", "1");
    value.put(
        "membershipBaseline",
        Map.of(
            "membershipLifecycleState",
            "ACTIVE",
            "membershipVersion",
            Map.of(ID, "1"),
            "membershipAuthorityGeneration",
            "1"));
    value.put(
        "outboxCheckpoints",
        List.of(
            checkpoint("account/" + ACCOUNT, "0"),
                checkpoint("issuer/firemud-account-service", "0"),
            checkpoint("membership/" + ACCOUNT + "/" + ID, "1"), checkpoint("tenant/" + ID, "1")));
    long now = System.currentTimeMillis();
    value.put(
        "tokenIdentityEvidence",
        Map.ofEntries(
            Map.entry("accountId", ACCOUNT),
            Map.entry("operationId", ID),
            Map.entry("issuanceRequestId", ID),
            Map.entry("tokenJti", ID),
            Map.entry("tokenSHA256", "a".repeat(64)),
            Map.entry("tokenGeneration", "1"),
            Map.entry("issuanceFence", "1"),
            Map.entry("tokenIdentityFence", "1"),
            Map.entry("tokenProfile", "game-session-account-delegation"),
            Map.entry("issuedAt", Long.toString(now / 1000)),
            Map.entry("notBefore", Long.toString(now / 1000)),
            Map.entry("expiresAt", Long.toString(now / 1000 + 300))));
    value.put("evaluatedAt", Long.toString(now));
    value.put("expiresAt", Long.toString(now + 15000));
    return value;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> scope(Map<String, Object> carrier) {
    return (Map<String, Object>) carrier.get("bindingScope");
  }

  private static Map<String, Object> checkpoint(String suffix, String sequence) {
    return Map.of(
        "outboxStreamKey", "account:auth-authority:v1:" + suffix, "outboxSequence", sequence);
  }
}
