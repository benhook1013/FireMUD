package net.firedevops.firemud.common.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
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
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.Request;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.v1.LaunchDescriptor;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameInstanceLaunchAssociationResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CanonicalGameInstanceLaunchAssociationReadGrpcCodecTest {
  private static final String NAMESPACE = "test";
  private static final UUID TENANT_ID = UUID.fromString("12345678-1234-4234-8234-123456789abc");
  private static final UUID INSTANCE_ID = UUID.fromString("22345678-1234-4234-8234-123456789abc");
  private static final UUID READ_ID = UUID.fromString("32345678-1234-4234-8234-123456789abc");
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("42345678-1234-4234-8234-123456789abc");
  private static final UUID VERSION_UUID = UUID.fromString("52345678-1234-4234-8234-123456789abc");
  private static final UUID PLAYABLE_NAMESPACE_ID =
      UUID.fromString("62345678-1234-4234-8234-123456789abc");
  private static final long LARGE_ROW_VERSION = 4_294_967_297L;
  private static final UUID V2_OPERATION_ID =
      UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID V2_DRAFT_REQUEST_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID V2_COMMIT_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID V2_FENCE_ID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID V2_ACTOR_ID = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final UUID V2_ROOM_TEMPLATE_ID =
      UUID.fromString("77777777-7777-4777-8777-777777777777");
  private static final UUID V2_REVISION_ID =
      UUID.fromString("12345678-1234-4234-8234-123456789003");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void mapsExactRequestAndRetainsCompleteBindingAndLongRowVersion() {
    Request request = request();
    var wireRequest = CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toRequest(request);
    assertThat(wireRequest.getReadRequestId()).isEqualTo(READ_ID.toString());
    assertThat(wireRequest.getTargetNamespace()).isEqualTo(NAMESPACE);
    assertThat(wireRequest.getCanonicalTenantId()).isEqualTo(TENANT_ID.toString());
    assertThat(wireRequest.getWorldSlug()).isEqualTo("copper-coast");
    assertThat(wireRequest.getGameInstanceUuid()).isEqualTo(INSTANCE_ID.toString());
    assertThat(wireRequest.getControlPlaneRequestId()).isEqualTo("launch-control-request");
    assertThat(wireRequest.getLaunchDescriptorId()).isEqualTo("descriptor-7");
    assertThat(wireRequest.getExpectedDescriptorRequestDigest())
        .isEqualTo(request.expectedDescriptorRequestDigest());
    assertThat(wireRequest.getExpectedDescriptorResultDigest())
        .isEqualTo(request.expectedDescriptorResultDigest());
    assertThat(wireRequest.getExpectedReleaseAttestationEvidenceDigest())
        .isEqualTo(request.expectedReleaseAttestationEvidenceDigest());

    var result =
        CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(request, response());
    assertThat(result.playableStateNamespaceId()).isEqualTo(PLAYABLE_NAMESPACE_ID);
    assertThat(result.playableStateScope()).isEqualTo(RealmEntryPolicy.StateScope.SHARED);
    assertThat(result.publicProduction()).isTrue();
    assertThat(result.currentGameInstanceStatus()).isEqualTo(CurrentGameInstanceStatus.STARTING);
    assertThat(result.currentRowVersion()).isEqualTo(LARGE_ROW_VERSION);
    assertThat(result.launchBindingEvidence()).isEqualTo(binding());
  }

  @Test
  void preservesGenuineV2WorldSelectorEvidenceThroughTheOwnerReadCodec() {
    var expectedBinding = v2Binding();
    Request request = request(expectedBinding);
    var response = response(request, expectedBinding);

    var result =
        CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(request, response);
    var expectedWorldSelector = expectedBinding.releaseAttestation().worldStartLocationEvidence();
    var returnedRelease = result.launchBindingEvidence().releaseAttestation();
    assertThat(returnedRelease.schemaVersion())
        .isEqualTo(AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION);
    assertThat(returnedRelease.worldStartLocationEvidence()).isEqualTo(expectedWorldSelector);
    assertThat(returnedRelease.worldStartLocationEvidence().canonicalBytes())
        .containsExactly(expectedWorldSelector.canonicalBytes());
    assertThat(result.launchBindingEvidence()).isEqualTo(expectedBinding);

    byte[] changedSelector = expectedWorldSelector.canonicalBytes();
    changedSelector[changedSelector.length - 1] ^= 1;
    var changedV2Response =
        response.toBuilder()
            .setReleaseAttestation(
                response.getReleaseAttestation().toBuilder()
                    .setWorldStartLocationEvidence(ByteString.copyFrom(changedSelector)))
            .build();
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    request, changedV2Response))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsEveryChangedSelectorEcho() {
    var valid = response();
    List<UnaryOperator<GetCanonicalGameInstanceLaunchAssociationResponse.Builder>> changes =
        List.of(
            builder -> builder.setReadRequestId("ffffffff-ffff-4fff-8fff-ffffffffffff"),
            builder -> builder.setTargetNamespace("other"),
            builder -> builder.setCanonicalTenantId("ffffffff-ffff-4fff-8fff-ffffffffffff"),
            builder -> builder.setWorldSlug("other-world"),
            builder -> builder.setGameInstanceUuid("ffffffff-ffff-4fff-8fff-ffffffffffff"),
            builder -> builder.setControlPlaneRequestId("other-control-request"),
            builder -> builder.setLaunchDescriptorId("other-descriptor"),
            builder -> builder.setDescriptorRequestDigest("sha256:" + "f".repeat(64)),
            builder -> builder.setDescriptorResultDigest("sha256:" + "f".repeat(64)),
            builder -> builder.setReleaseAttestationEvidenceDigest("sha256:" + "f".repeat(64)));
    for (UnaryOperator<GetCanonicalGameInstanceLaunchAssociationResponse.Builder> change :
        changes) {
      var changed = change.apply(valid.toBuilder()).build();
      assertThatThrownBy(
              () ->
                  CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                      request(), changed))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("echo changed");
    }
  }

  @Test
  void rejectsChangedCompletePayloadAndOmittedPairMember() {
    var valid = response();
    var changedDescriptor =
        valid.toBuilder()
            .setLaunchDescriptor(valid.getLaunchDescriptor().toBuilder().setVersionId(90L))
            .build();
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    request(), changedDescriptor))
        .isInstanceOf(IllegalArgumentException.class);

    var changedRelease =
        valid.toBuilder()
            .setReleaseAttestation(
                valid.getReleaseAttestation().toBuilder()
                    .setManifestHash("sha256:" + "e".repeat(64)))
            .build();
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    request(), changedRelease))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    request(), valid.toBuilder().clearLaunchDescriptor().build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("complete descriptor and release attestation pair");
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    request(), valid.toBuilder().clearReleaseAttestation().build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("complete descriptor and release attestation pair");
  }

  @Test
  void rejectsUnknownNestedFieldsAndOwnerErrors() {
    var valid = response();
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    request(), valid.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    request(),
                    valid.toBuilder()
                        .setLaunchDescriptor(
                            valid.getLaunchDescriptor().toBuilder()
                                .setAuthoredWorldBinding(
                                    valid
                                        .getLaunchDescriptor()
                                        .getAuthoredWorldBinding()
                                        .toBuilder()
                                        .setUnknownFields(unknown)))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    request(),
                    valid.toBuilder()
                        .setReleaseAttestation(
                            valid.getReleaseAttestation().toBuilder().setUnknownFields(unknown))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    request(),
                    valid.toBuilder()
                        .setError(ErrorDetail.newBuilder().setMessage("denied"))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rejected");
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    request(),
                    valid.toBuilder()
                        .setError(ErrorDetail.newBuilder().setUnknownFields(unknown))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
  }

  @Test
  void rejectsNonSharedNonProductionUnknownStatusesAndMalformedLongValues() {
    var valid = response();
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    request(), valid.toBuilder().setPlayableStateScope("ISOLATED").build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SHARED");
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    request(), valid.toBuilder().setPublicProduction(false).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("public production");
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    request(), valid.toBuilder().setCurrentGameInstanceStatus("UNKNOWN").build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown current");
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                    request(), valid.toBuilder().setCurrentRowVersion(-1L).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("row version");
  }

  static Request request() {
    var pair = binding();
    return new Request(
        READ_ID,
        NAMESPACE,
        TENANT_ID,
        "copper-coast",
        INSTANCE_ID,
        "launch-control-request",
        pair.descriptor().launchDescriptorId(),
        pair.descriptor().requestDigest(),
        pair.descriptor().resultDigest(),
        pair.releaseAttestation().evidenceDigest());
  }

  static Request v2Request() {
    return request(v2Binding());
  }

  static GetCanonicalGameInstanceLaunchAssociationResponse v2Response() {
    var binding = v2Binding();
    return response(request(binding), binding);
  }

  static CompleteLaunchBindingEvidence v2Binding() {
    try {
      WorldPublishedStartLocationEvidence selector = v2SelectorEvidence();
      var descriptor = v2Descriptor(selector);
      var release = v2Release(descriptor, selector);
      return new CompleteLaunchBindingEvidence(descriptor, release);
    } catch (IOException | NoSuchAlgorithmException failure) {
      throw new IllegalStateException("Local complete v2 selector fixture is invalid", failure);
    }
  }

  private static WorldPublishedStartLocationEvidence v2SelectorEvidence()
      throws IOException, NoSuchAlgorithmException {
    DraftCommitBinding draft = v2DraftBinding();
    byte[] accountBytes = v2AccountBinding(draft);
    var terminalRequest =
        new WorldDraftTerminalReadEvidence.Request(
            1, NAMESPACE, UUID.fromString("33333333-3333-4333-8333-333333333333"), accountBytes);
    byte[] resultBytes = v2AppliedResult(terminalRequest, draft, accountBytes);
    WorldDraftStartLocationEvidence receipt = v2Receipt(terminalRequest, accountBytes, resultBytes);
    var affectedTuples =
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
            NAMESPACE,
            TENANT_ID,
            VERSION_UUID,
            V2_FENCE_ID,
            V2_OPERATION_ID,
            "publication-request",
            "a".repeat(64),
            5L,
            "publish-workflow",
            V2_COMMIT_ID.toString(),
            "b".repeat(64),
            3,
            affectedTuples);
    return new WorldPublishedStartLocationEvidence(
        request, receipt.canonicalBytes(), accountBytes, resultBytes);
  }

  private static DraftCommitBinding v2DraftBinding() throws IOException {
    String declaration =
        JSON.writeValueAsString(
            Map.of(
                "tenantId", TENANT_ID.toString(),
                "versionId", VERSION_UUID.toString(),
                "startLocation",
                    Map.of(
                        "tenantId", TENANT_ID.toString(),
                        "versionId", VERSION_UUID.toString(),
                        "roomTemplateId", V2_ROOM_TEMPLATE_ID.toString()),
                "familyCounts",
                    List.of(
                        Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_REGION", "count", 0),
                        Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_ZONE", "count", 0),
                        Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_ROOM", "count", 1),
                        Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT", "count", 0),
                        Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE", "count", 0),
                        Map.of(
                            "family",
                            "WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING",
                            "count",
                            0))));
    var payload = new LinkedHashMap<String, Object>();
    payload.put("logicalRevisionId", V2_REVISION_ID.toString());
    payload.put("commitId", V2_COMMIT_ID.toString());
    payload.put("aggregateType", "WORLD_DESIGN_AGGREGATE_TYPE_ROOM");
    payload.put("aggregateId", V2_ROOM_TEMPLATE_ID.toString());
    payload.put("freshGraphDeclaration", JSON.readTree(declaration));
    String revision = JSON.writeValueAsString(payload);
    return DraftCommitBinding.create(
        new TargetProof(
            TENANT_ID, VERSION_UUID, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW"),
        V2_DRAFT_REQUEST_ID,
        V2_COMMIT_ID,
        "base-1",
        List.of(
            new RevisionPayload(
                "0", V2_REVISION_ID, DraftCommitBinding.Owner.WORLD_MANAGEMENT, revision)),
        List.of(
            new AffectedUnit(
                DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                "WORLD_DESIGN_AGGREGATE_TYPE_ROOM",
                V2_ROOM_TEMPLATE_ID.toString(),
                "AGGREGATE",
                V2_ROOM_TEMPLATE_ID.toString(),
                "0")));
  }

  private static byte[] v2AccountBinding(DraftCommitBinding draft) {
    byte[] draftBytes = draft.canonicalBytes();
    return new DraftAuthorizationFenceBinding(
            V2_OPERATION_ID,
            V2_DRAFT_REQUEST_ID,
            V2_COMMIT_ID,
            V2_FENCE_ID,
            V2_ACTOR_ID,
            TENANT_ID,
            VERSION_UUID,
            "base-1",
            "0",
            draftBytes,
            draftBytes,
            draft.digest(),
            List.of(
                new SourceEvidence(
                    SourceKind.GLOBAL_ROLES,
                    UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd").toString(),
                    null,
                    "1",
                    null,
                    null,
                    new byte[] {4, 5})))
        .canonicalBytes();
  }

  private static byte[] v2AppliedResult(
      WorldDraftTerminalReadEvidence.Request request, DraftCommitBinding draft, byte[] accountBytes)
      throws IOException, NoSuchAlgorithmException {
    var account = request.accountBinding();
    var operation = new ByteArrayOutputStream();
    for (String frame :
        List.of(
            "world-draft-terminal-operation/v1",
            account.operationId().toString(),
            account.requestId().toString(),
            account.commitId().toString(),
            account.fenceId().toString(),
            account.tenantId().toString(),
            account.versionId().toString())) {
      writeFrame(operation, frame.getBytes(StandardCharsets.UTF_8));
    }
    writeFrame(operation, draft.canonicalBytes());
    for (String frame :
        List.of(
            request.targetNamespace(),
            account.tenantId().toString(),
            account.versionId().toString(),
            V2_OPERATION_ID.toString(),
            Long.toString(draft.target().gameDesignVersionRowId()),
            V2_FENCE_ID.toString(),
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            "a".repeat(64),
            "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
            "b".repeat(64),
            "c".repeat(64))) {
      writeFrame(operation, frame.getBytes(StandardCharsets.UTF_8));
    }
    writeFrame(operation, sha256(accountBytes).getBytes(StandardCharsets.UTF_8));
    writeFrame(operation, accountBytes);

    var mapping = new LinkedHashMap<String, Object>();
    mapping.put("id", 1);
    mapping.put("target_namespace", request.targetNamespace());
    mapping.put("canonical_tenant_id", TENANT_ID.toString());
    mapping.put("canonical_version_id", VERSION_UUID.toString());
    mapping.put("family", "ROOM");
    mapping.put("template_id", V2_ROOM_TEMPLATE_ID.toString());
    mapping.put("private_row_key", 101L);
    mapping.put("tenant_id", 11L);
    mapping.put("version_id", 19L);
    mapping.put("version_identity_operation_id", V2_OPERATION_ID.toString());
    mapping.put("request_id", V2_DRAFT_REQUEST_ID.toString());
    mapping.put("commit_id", V2_COMMIT_ID.toString());
    mapping.put("revision_id", V2_REVISION_ID.toString());
    mapping.put("revision_order", "0");
    byte[] graph =
        JSON.writeValueAsBytes(
            Map.of(
                "schemaVersion", "2",
                "canonicalTenantId", TENANT_ID.toString(),
                "canonicalVersionId", VERSION_UUID.toString(),
                "rows", List.of(Map.of("mapping", mapping, "content", Map.of()))));
    String graphDigest = sha256(graph);
    WorldDraftStartLocationEvidence receipt =
        WorldDraftStartLocationEvidence.create(
            request.targetNamespace(),
            account.operationId(),
            account.requestId(),
            account.commitId(),
            account.fenceId(),
            sha256(accountBytes),
            draft.digest(),
            new RoomTemplateRef(TENANT_ID, VERSION_UUID, V2_ROOM_TEMPLATE_ID),
            graphDigest);
    var result = new LinkedHashMap<String, Object>();
    result.put("schema", "world-draft-graph-applied/v2");
    result.put("status", "APPLIED");
    result.put("operationBytesBase64", Base64.getEncoder().encodeToString(operation.toByteArray()));
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
                            new BigInteger(unit.expectedEpoch()).add(BigInteger.ONE).toString()))
            .toList());
    return canonical(result);
  }

  private static WorldDraftStartLocationEvidence v2Receipt(
      WorldDraftTerminalReadEvidence.Request request, byte[] accountBytes, byte[] resultBytes)
      throws IOException, NoSuchAlgorithmException {
    var account = request.accountBinding();
    var readback =
        new DraftAuthorizationFenceBinding.OwnerReadback(
            Owner.WORLD,
            Outcome.COMMITTED,
            account.operationId(),
            account.commitId(),
            account.fenceId(),
            account.inputDigest(),
            accountBytes,
            resultBytes);
    var response =
        net.firedevops.firemud.common.authoring.WorldDraftTerminalReadGrpcCodec.toResponse(
            request, java.util.Optional.of(readback));
    net.firedevops.firemud.common.authoring.WorldDraftTerminalReadGrpcCodec.fromResponse(
        request, response);
    var applied = JSON.readTree(resultBytes);
    byte[] receiptBytes =
        Base64.getDecoder().decode(applied.get("startLocationReceiptBase64").textValue());
    return WorldDraftStartLocationEvidence.fromStored(receiptBytes);
  }

  private static AuthoredWorldLaunchDescriptorEvidence v2Descriptor(
      WorldPublishedStartLocationEvidence selector) {
    var descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            "launch-control-request",
            selector.request().canonicalTenantId(),
            "synthetic-world",
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            "sha256:" + "b".repeat(64),
            19L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    return AuthoredWorldLaunchDescriptorEvidence.create(
        descriptorRequest,
        "descriptor",
        42L,
        false,
        null,
        "{}",
        "generation",
        9L,
        7L,
        "release",
        false,
        null);
  }

  private static AuthoredWorldReleaseAttestationEvidence v2Release(
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      WorldPublishedStartLocationEvidence selector) {
    var participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        selector.request().appliedCommitId(),
                        "b".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "c".repeat(64) : null))
            .toList();
    return AuthoredWorldReleaseAttestationEvidence.create(
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
        List.of("LOOK"),
        descriptor.generationConfigRevision(),
        selector);
  }

  private static void writeFrame(ByteArrayOutputStream output, byte[] value) {
    output.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
    output.writeBytes(value);
  }

  private static byte[] canonical(Object value) throws IOException {
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
  }

  private static String sha256(byte[] value) throws NoSuchAlgorithmException {
    return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  static CompleteLaunchBindingEvidence binding() {
    var request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            "launch-control-request",
            TENANT_ID,
            "copper-coast",
            SOURCE_OPERATION_ID,
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
    var descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            request,
            "descriptor-7",
            42L,
            false,
            null,
            "{}",
            "generation-4",
            9L,
            7L,
            "release-7",
            false,
            null);
    var commitId = "commit-7";
    var participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        commitId,
                        "b".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "c".repeat(64) : null))
            .toList();
    var release =
        AuthoredWorldReleaseAttestationEvidence.create(
            descriptor.targetNamespace(),
            descriptor.resultDigest(),
            descriptor.canonicalTenantId(),
            VERSION_UUID,
            descriptor.worldSlug(),
            descriptor.authoredWorldSourceOperationId(),
            descriptor.authoredWorldSourceEvidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            "publish-workflow-7",
            commitId,
            participants,
            "sha256:" + "d".repeat(64),
            1,
            List.of(),
            List.of(),
            List.of("LOOK"),
            descriptor.generationConfigRevision());
    return new CompleteLaunchBindingEvidence(descriptor, release);
  }

  static GetCanonicalGameInstanceLaunchAssociationResponse response() {
    return response(request(), binding());
  }

  private static Request request(CompleteLaunchBindingEvidence binding) {
    var descriptor = binding.descriptor();
    return new Request(
        READ_ID,
        descriptor.targetNamespace(),
        descriptor.canonicalTenantId(),
        descriptor.worldSlug(),
        INSTANCE_ID,
        descriptor.controlPlaneRequestId(),
        descriptor.launchDescriptorId(),
        descriptor.requestDigest(),
        descriptor.resultDigest(),
        binding.releaseAttestation().evidenceDigest());
  }

  private static GetCanonicalGameInstanceLaunchAssociationResponse response(
      Request request, CompleteLaunchBindingEvidence evidence) {
    return GetCanonicalGameInstanceLaunchAssociationResponse.newBuilder()
        .setReadRequestId(request.readRequestId().toString())
        .setTargetNamespace(request.targetNamespace())
        .setCanonicalTenantId(request.canonicalTenantId().toString())
        .setWorldSlug(request.worldSlug())
        .setGameInstanceUuid(request.gameInstanceUuid().toString())
        .setControlPlaneRequestId(request.controlPlaneRequestId())
        .setLaunchDescriptorId(request.launchDescriptorId())
        .setDescriptorRequestDigest(request.expectedDescriptorRequestDigest())
        .setDescriptorResultDigest(request.expectedDescriptorResultDigest())
        .setReleaseAttestationEvidenceDigest(request.expectedReleaseAttestationEvidenceDigest())
        .setPlayableStateNamespaceId(PLAYABLE_NAMESPACE_ID.toString())
        .setPlayableStateScope("SHARED")
        .setCurrentGameInstanceStatus("STARTING")
        .setCurrentRowVersion(LARGE_ROW_VERSION)
        .setLaunchDescriptor(wireDescriptor(evidence.descriptor()))
        .setReleaseAttestation(
            AuthoredWorldLaunchDescriptorGrpcCodec.toReleaseAttestation(
                evidence.releaseAttestation()))
        .setPublicProduction(true)
        .build();
  }

  private static LaunchDescriptor wireDescriptor(AuthoredWorldLaunchDescriptorEvidence evidence) {
    var authored =
        net.firedevops.firemud.gamedesign.v1.AuthoredWorldLaunchDescriptorEvidence.newBuilder()
            .setSchemaVersion(evidence.schemaVersion())
            .setTargetNamespace(evidence.targetNamespace())
            .setControlPlaneRequestId(evidence.controlPlaneRequestId())
            .setCanonicalTenantId(evidence.canonicalTenantId().toString())
            .setWorldSlug(evidence.worldSlug())
            .setAuthoredWorldSourceOperationId(evidence.authoredWorldSourceOperationId().toString())
            .setAuthoredWorldSourceEvidenceDigest(evidence.authoredWorldSourceEvidenceDigest())
            .setGameTemplateId(evidence.gameTemplateId())
            .setRequestDigest(evidence.requestDigest())
            .setLaunchDescriptorId(evidence.launchDescriptorId())
            .setVersionId(evidence.versionId())
            .setRuntimeFlagsJson(evidence.runtimeFlagsJson())
            .setGenerationConfigRevision(evidence.generationConfigRevision())
            .setVersionStateEpoch(evidence.versionStateEpoch())
            .setReleaseBundleId(evidence.releaseBundleId())
            .setPublishedReleaseBundleRef(evidence.publishedReleaseBundleRef())
            .setResultDigest(evidence.resultDigest());
    return LaunchDescriptor.newBuilder()
        .setLaunchDescriptorId(evidence.launchDescriptorId())
        .setCanonicalTenantId(evidence.canonicalTenantId().toString())
        .setGameTemplateId(evidence.gameTemplateId())
        .setControlPlaneRequestId(evidence.controlPlaneRequestId())
        .setVersionId(evidence.versionId())
        .setRuntimeFlagsJson(evidence.runtimeFlagsJson())
        .setGenerationConfigRevision(evidence.generationConfigRevision())
        .setVersionStateEpoch(evidence.versionStateEpoch())
        .setReleaseBundleId(evidence.releaseBundleId())
        .setPublishedReleaseBundleRef(evidence.publishedReleaseBundleRef())
        .setAuthoredWorldBinding(authored)
        .build();
  }
}
