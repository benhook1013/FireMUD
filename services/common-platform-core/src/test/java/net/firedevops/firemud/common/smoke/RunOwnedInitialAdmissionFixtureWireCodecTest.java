package net.firedevops.firemud.common.smoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadGrpcCodec;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec.Capability;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec.LaunchReadEvidence;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec.SourceReceipt;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.v1.ArtifactState;
import net.firedevops.firemud.gamedesign.v1.LaunchDescriptor;
import net.firedevops.firemud.gamedesign.v1.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RunOwnedInitialAdmissionFixtureWireCodecTest {
  private static final String RUN_ID = "fixture-wire-codec-test";
  private static final String PROJECT_NAME = "firemud-fixture-wire-codec-test";
  private static final String CLAIM_DIGEST = "e".repeat(64);
  private static final String OPERATION_ID =
      RunOwnedInitialAdmissionFixtureWireCodec.fixtureOperationId(
              CLAIM_DIGEST, RUN_ID, PROJECT_NAME, "a".repeat(64), "b".repeat(64))
          .toString();
  private static final String SOURCE_OPERATION_ID = "3f773701-e812-44f5-8ae0-7268d543beed";
  private static final String LEAF_SHA = "a".repeat(64);
  private static final String WORLD_LEAF_SHA = "c".repeat(64);
  private static final String CA_SHA = "b".repeat(64);
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("ed5d1541-a33e-4f8b-9b54-38c27d76ba6d");
  private static final UnknownFieldSet UNKNOWN_FIELD_SET =
      UnknownFieldSet.newBuilder()
          .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
          .build();

  @Test
  void parsesTheClosedCapabilityAsImmutableUntrustedData() {
    Capability capability = parseCapability(validCapabilityJson());

    assertThat(capability.runId()).isEqualTo(RUN_ID);
    assertThat(capability.composeProjectName()).isEqualTo(PROJECT_NAME);
    assertThat(capability.operationId()).isEqualTo(OPERATION_ID);
    assertThat(capability.tenantId()).isEqualTo(7L);
    assertThat(capability.gameTemplateId()).isEqualTo(17L);
    assertThat(capability.gameSessionUriSan())
        .isEqualTo(RunOwnedInitialAdmissionFixtureWireCodec.GAME_SESSION_URI_SAN);
    assertThat(capability.worldManagementUriSan())
        .isEqualTo(RunOwnedInitialAdmissionFixtureWireCodec.WORLD_MANAGEMENT_URI_SAN);
    assertThat(capability.worldManagementLeafSha256()).isEqualTo(WORLD_LEAF_SHA);
  }

  @Test
  void rejectsCapabilityOutsideTheStrictBoundedGrammar() {
    String valid = validCapabilityJson();
    assertInvalidCapability(valid.replace("\"schema\":", "\"unknown\":true,\"schema\":"));
    assertInvalidCapability(valid.replace("\"schema\":", "\"schema\":\"duplicate\",\"schema\":"));
    assertInvalidCapability(valid + " {}\n");
    assertInvalidCapability(valid.replace("\"tenantId\":7", "\"tenantId\":\"7\""));
    assertInvalidCapability(valid.replace(OPERATION_ID, "not-a-canonical-uuid"));
    assertInvalidCapability(valid.replace(LEAF_SHA, "d".repeat(64)));
    assertInvalidCapability(valid.replace(CA_SHA, "d".repeat(64)));
    assertInvalidCapability(
        valid.replace(
            RunOwnedInitialAdmissionFixtureWireCodec.GAME_SESSION_URI_SAN,
            "spiffe://different/game-session"));
    assertInvalidCapability(valid.replace(WORLD_LEAF_SHA, "D".repeat(64)));
    assertInvalidCapability(valid.replace("\"visible\":true", "\"visible\":\"true\""));
    assertInvalidCapabilityBytes(new byte[] {(byte) 0xc3, (byte) 0x28});
    assertInvalidCapabilityBytes(new byte[16 * 1024 + 1]);
  }

  @Test
  void parsesTheCompleteReceiptAndActualOwnerDescriptor() {
    assertThat(encode(bundle().toByteArray())).hasSizeGreaterThan(256);
    assertThat(encode(launchDescriptor().toByteArray())).hasSizeGreaterThan(256);
    SourceReceipt receipt = parseSourceReceipt(validSourceReceiptJson(), capability());

    assertThat(receipt.schema())
        .isEqualTo(RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_RECEIPT_SCHEMA);
    assertThat(receipt.worldManagementUriSan())
        .isEqualTo(RunOwnedInitialAdmissionFixtureWireCodec.WORLD_MANAGEMENT_URI_SAN);
    assertThat(receipt.worldManagementLeafSha256()).isEqualTo(WORLD_LEAF_SHA);
    assertThat(receipt.sourceEvidence()).isEqualTo(sourceEvidence());
    assertThat(receipt.launchReadEvidence().publishedReleaseBundle()).isEqualTo(bundle());
    assertThat(receipt.launchReadEvidence().versionAssetArtifactState()).isEqualTo(artifactState());
    assertThat(receipt.launchReadEvidence().versionState()).isEqualTo(versionState());
    assertThat(receipt.launchReadEvidence().launchDescriptor()).isEqualTo(launchDescriptor());
  }

  @Test
  void rejectsReceiptEnvelopeAndProjectionViolations() {
    String valid = validSourceReceiptJson();
    assertInvalidReceipt(valid.replace("\"schema\":", "\"unknown\":true,\"schema\":"));
    assertInvalidReceipt(valid.replace("\"schema\":", "\"schema\":\"duplicate\",\"schema\":"));
    assertInvalidReceipt(valid.replace(RUN_ID, "other-run"));
    assertInvalidReceipt(valid.replace(WORLD_LEAF_SHA, "d".repeat(64)));
    assertInvalidReceipt(valid.replace(LEAF_SHA, "d".repeat(64)));
    assertInvalidReceipt(valid.replace(CA_SHA, "d".repeat(64)));
    assertInvalidReceipt(
        valid.replace(
            RunOwnedInitialAdmissionFixtureWireCodec.GAME_SESSION_URI_SAN,
            "spiffe://different/game-session"));
    assertInvalidReceipt(
        valid.replaceFirst(
            "\"canonicalTenantId\":\"" + CANONICAL_TENANT_ID + "\"",
            "\"canonicalTenantId\":\"" + UUID.randomUUID() + "\""));
    assertInvalidReceipt(valid.replace("\"sourceGameRowId\":13", "\"sourceGameRowId\":\"13\""));
    assertInvalidReceipt(
        valid.replace(
            "\"launchDescriptorBase64\":", "\"unknown\":true,\"launchDescriptorBase64\":"));
    assertInvalidReceipt(valid.substring(0, valid.length() - 8));
    assertInvalidReceiptBytes(new byte[] {(byte) 0xc3, (byte) 0x28});

    String encodedBundle = encode(bundle().toByteArray());
    String noncanonical =
        Base64.getEncoder().withoutPadding().encodeToString(bundle().toByteArray());
    if (noncanonical.equals(encodedBundle)) {
      noncanonical += "=";
    }
    assertInvalidReceipt(valid.replace(encodedBundle, noncanonical));
    assertInvalidReceipt(valid.replace(encodedBundle, encode(new byte[] {10, 5})));
    assertInvalidReceipt(valid.replace(encode(launchDescriptor().toByteArray()), "not base64"));
  }

  @Test
  void rejectsRecursiveUnknownProtoFieldsAndDescriptorProjectionMismatches() {
    LaunchDescriptor descriptorWithNestedUnknownFields =
        launchDescriptor().toBuilder()
            .setAuthoredWorldBinding(
                launchDescriptor().getAuthoredWorldBinding().toBuilder()
                    .setUnknownFields(UNKNOWN_FIELD_SET))
            .build();
    assertInvalidReceipt(
        sourceReceiptJson(
            sourceEvidence(),
            bundle(),
            artifactState(),
            versionState(),
            descriptorWithNestedUnknownFields));

    assertInvalidReceipt(
        sourceReceiptJson(
            sourceEvidence(),
            bundle(),
            artifactState(),
            versionState(),
            descriptorFor(12L, bundle().getId(), bundle().getPublishedReleaseBundleRef())));
    assertInvalidReceipt(
        sourceReceiptJson(
            sourceEvidence(),
            bundle(),
            artifactState(),
            versionState(),
            descriptorFor(
                bundle().getVersionId(), bundle().getId(), "different-owner-release-ref")));
  }

  @Test
  void retainsExactClaimCreationAndPolicySealAndRejectsReplaySubstitution() {
    SourceReceipt receipt = parseSourceReceipt(validSourceReceiptJson(), capability());
    assertThat(receipt.creationEvidence()).isEqualTo(creationEvidence());
    assertThat(receipt.creationEvidence().creationRequestId())
        .isNotEqualTo(sourceEvidence().registrationRequestId());
    byte[] request =
        RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestBytes(
            capability(),
            receipt.creationEvidence(),
            receipt.sourceEvidence(),
            receipt.launchReadEvidence());
    var decoded = RunOwnedInitialAdmissionFixtureWireCodec.parseFixturePolicyRequestBytes(request);
    assertThat(decoded.capability()).isEqualTo(capability());
    assertThat(decoded.creationEvidence()).isEqualTo(receipt.creationEvidence());
    assertThat(decoded.sourceEvidence()).isEqualTo(receipt.sourceEvidence());
    assertThat(decoded.launchReadEvidence()).isEqualTo(receipt.launchReadEvidence());
    assertThat(request.length).isLessThan(16 * 1024);
    assertInvalidCapability(validCapabilityJson().replace(CLAIM_DIGEST, "f".repeat(64)));
    assertInvalidCapability(
        validCapabilityJson().replace(OPERATION_ID, UUID.randomUUID().toString()));
    assertInvalidReceipt(
        validSourceReceiptJson()
            .replace(receipt.fixturePolicySeal().evidenceDigest(), "sha256:" + "0".repeat(64)));
    assertInvalidReceipt(
        validSourceReceiptJson()
            .replace(
                creationEvidence().creationRequestId().toString(), UUID.randomUUID().toString()));
    assertThatThrownBy(
            () ->
                RunOwnedInitialAdmissionFixtureWireCodec.parseFixturePolicyRequestBytes(
                    Arrays.copyOf(request, request.length + 1)))
        .isInstanceOf(RunOwnedInitialAdmissionFixtureWireCodec.InvalidWireDataException.class);
    byte[] badLength = request.clone();
    ByteBuffer.wrap(badLength).putInt(Integer.MAX_VALUE);
    assertThatThrownBy(
            () ->
                RunOwnedInitialAdmissionFixtureWireCodec.parseFixturePolicyRequestBytes(badLength))
        .isInstanceOf(RunOwnedInitialAdmissionFixtureWireCodec.InvalidWireDataException.class);
  }

  @Test
  void closedFixtureAllocationReadRejectsChangedIdentitySealAndVersion() {
    var result = fixtureAllocation();
    var request =
        CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toFixtureRequest(result.request());
    assertThat(CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromFixtureRequest(request))
        .isEqualTo(result.request());
    var response = CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toFixtureResponse(result);
    assertThat(response.getCurrentGameInstanceStatus()).isEqualTo("STARTING");
    assertThat(
            CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromFixtureResponse(
                result.request(), response))
        .isEqualTo(result);
    assertThat(result.gameSessionTenantId()).isNotEqualTo(capability().tenantId());
    byte[] bytes = result.fixturePolicyRequestBytes();
    bytes[0] ^= 1;
    assertThat(result.fixturePolicyRequestBytes()).isNotEqualTo(bytes);
    for (var wrong :
        new net.firedevops.firemud.gamesession.v1.GetRunOwnedFixtureLaunchAssociationResponse[] {
          response.toBuilder().setCanonicalVersionId(UUID.randomUUID().toString()).build(),
          response.toBuilder()
              .setFixturePolicySealEvidenceDigest("sha256:" + "0".repeat(64))
              .build(),
          response.toBuilder()
              .setFixturePolicySealRequestDigest("sha256:" + "0".repeat(64))
              .build(),
          response.toBuilder().setReadRequestId(UUID.randomUUID().toString()).build(),
          response.toBuilder().setTargetNamespace("other").build(),
          response.toBuilder().setFixtureOperationId(UUID.randomUUID().toString()).build(),
          response.toBuilder()
              .setExpectedFixturePolicyEvidenceDigest("sha256:" + "0".repeat(64))
              .build(),
          response.toBuilder().setGameSessionTenantId(0L).build()
        }) {
      assertThatThrownBy(
              () ->
                  CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromFixtureResponse(
                      result.request(), wrong))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromFixtureRequest(
                    request.toBuilder().setUnknownFields(UNKNOWN_FIELD_SET).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromFixtureResponse(
                    result.request(),
                    response.toBuilder().setUnknownFields(UNKNOWN_FIELD_SET).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromFixtureResponse(
                    result.request(),
                    response.toBuilder().setCurrentGameInstanceStatus("RUNNING").build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult
      fixtureAllocation() {
    var receipt = parseSourceReceipt(validSourceReceiptJson(), capability());
    var request =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureRequest(
            UUID.randomUUID(),
            "dev",
            UUID.fromString(OPERATION_ID),
            receipt.fixturePolicySeal().evidenceDigest());
    return new CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult(
        request,
        222L,
        333L,
        CANONICAL_TENANT_ID,
        UUID.randomUUID(),
        UUID.fromString(bundle().getCanonicalVersionId()),
        UUID.randomUUID(),
        UUID.randomUUID(),
        1L,
        CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus.STARTING,
        0L,
        RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestBytes(
            capability(),
            receipt.creationEvidence(),
            receipt.sourceEvidence(),
            receipt.launchReadEvidence()),
        receipt.fixturePolicySeal());
  }

  private static Capability capability() {
    return parseCapability(validCapabilityJson());
  }

  private static Capability parseCapability(String json) {
    return RunOwnedInitialAdmissionFixtureWireCodec.parseCapability(
        json.getBytes(StandardCharsets.UTF_8));
  }

  private static SourceReceipt parseSourceReceipt(String json, Capability capability) {
    return RunOwnedInitialAdmissionFixtureWireCodec.parseSourceReceipt(
        json.getBytes(StandardCharsets.UTF_8), capability);
  }

  private static void assertInvalidCapability(String json) {
    assertInvalidCapabilityBytes(json.getBytes(StandardCharsets.UTF_8));
  }

  private static void assertInvalidCapabilityBytes(byte[] bytes) {
    assertThatThrownBy(() -> RunOwnedInitialAdmissionFixtureWireCodec.parseCapability(bytes))
        .isInstanceOf(RunOwnedInitialAdmissionFixtureWireCodec.InvalidWireDataException.class);
  }

  private static void assertInvalidReceipt(String json) {
    assertInvalidReceiptBytes(json.getBytes(StandardCharsets.UTF_8));
  }

  private static void assertInvalidReceiptBytes(byte[] bytes) {
    assertThatThrownBy(
            () -> RunOwnedInitialAdmissionFixtureWireCodec.parseSourceReceipt(bytes, capability()))
        .isInstanceOf(RunOwnedInitialAdmissionFixtureWireCodec.InvalidWireDataException.class);
  }

  private static String validCapabilityJson() {
    return """
        {"schema":"%s","runId":"%s","composeProjectName":"%s","operationId":"%s","tenantId":7,"gameTemplateId":17,"ownerAccountId":27,"worldSlug":"demo","worldDisplayName":"Demo World","realmSlug":"production","realmDisplayName":"Live Realm","visible":true,"publicProductionRealm":true,"requiresCharacterSelection":false,"stateScope":"SHARED","characterCreationPolicy":"ALLOW_NEW","gameSessionLeafSha256":"%s","gameSessionUriSan":"%s","caCertificateSha256":"%s","worldManagementUriSan":"%s","worldManagementLeafSha256":"%s","claimDigest":"%s"}\
        """
        .formatted(
            RunOwnedInitialAdmissionFixtureWireCodec.CAPABILITY_SCHEMA,
            RUN_ID,
            PROJECT_NAME,
            OPERATION_ID,
            LEAF_SHA,
            RunOwnedInitialAdmissionFixtureWireCodec.GAME_SESSION_URI_SAN,
            CA_SHA,
            RunOwnedInitialAdmissionFixtureWireCodec.WORLD_MANAGEMENT_URI_SAN,
            WORLD_LEAF_SHA,
            CLAIM_DIGEST);
  }

  private static String validSourceReceiptJson() {
    return sourceReceiptJson(
        sourceEvidence(), bundle(), artifactState(), versionState(), launchDescriptor());
  }

  private static String sourceReceiptJson(
      AuthoredWorldSourceEvidence source,
      PublishedReleaseBundle bundle,
      VersionAssetArtifactState artifactState,
      VersionStateSnapshot versionState,
      LaunchDescriptor descriptor) {
    String receipt =
        """
        {"schema":"%s","runId":"%s","composeProjectName":"%s","fixtureOperationId":"%s","ownerService":"%s","gameSessionUriSan":"%s","gameSessionLeafSha256":"%s","caCertificateSha256":"%s","worldManagementUriSan":"%s","worldManagementLeafSha256":"%s","sourceEvidence":{"schemaVersion":%d,"targetNamespace":"%s","registrationRequestId":"%s","operationId":"%s","requestDigest":"%s","canonicalTenantId":"%s","tenantSlug":"%s","worldSlug":"%s","worldDisplayName":"%s","sourceGameRowId":%d,"sourceGameTenantKey":"%s","provenanceKind":"%s","evidenceDigest":"%s"},"launchReadEvidence":{"schema":"%s","gameTemplateId":17,"publishedReleaseBundleBase64":"%s","versionAssetArtifactStateBase64":"%s","versionStateBase64":"%s","launchDescriptorBase64":"%s"}}\
        """
            .formatted(
                RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_RECEIPT_SCHEMA,
                RUN_ID,
                PROJECT_NAME,
                OPERATION_ID,
                RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_RECEIPT_OWNER_SERVICE,
                RunOwnedInitialAdmissionFixtureWireCodec.GAME_SESSION_URI_SAN,
                LEAF_SHA,
                CA_SHA,
                RunOwnedInitialAdmissionFixtureWireCodec.WORLD_MANAGEMENT_URI_SAN,
                WORLD_LEAF_SHA,
                source.schemaVersion(),
                source.targetNamespace(),
                source.registrationRequestId(),
                source.operationId(),
                source.requestDigest(),
                source.canonicalTenantId(),
                source.tenantSlug(),
                source.worldSlug(),
                source.worldDisplayName(),
                source.sourceGameRowId(),
                source.sourceGameTenantKey(),
                source.provenanceKind(),
                source.evidenceDigest(),
                RunOwnedInitialAdmissionFixtureWireCodec.LAUNCH_READ_EVIDENCE_SCHEMA,
                encode(bundle.toByteArray()),
                encode(artifactState.toByteArray()),
                encode(versionState.toByteArray()),
                encode(descriptor.toByteArray()));
    var launch =
        new LaunchReadEvidence(
            RunOwnedInitialAdmissionFixtureWireCodec.LAUNCH_READ_EVIDENCE_SCHEMA,
            17L,
            bundle(),
            artifactState(),
            versionState(),
            launchDescriptor());
    String requestDigest =
        RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestDigest(
            capability(), creationEvidence(), sourceEvidence(), launch);
    var seal =
        new RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicySeal(
            RunOwnedInitialAdmissionFixtureWireCodec.FIXTURE_POLICY_SEAL_SCHEMA,
            RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_RECEIPT_OWNER_SERVICE,
            UUID.fromString(OPERATION_ID),
            requestDigest,
            RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyEvidenceDigest(
                UUID.fromString(OPERATION_ID), requestDigest));
    ObjectMapper mapper = new ObjectMapper();
    return receipt.substring(0, receipt.length() - 1)
        + ",\"creationEvidence\":"
        + mapper.writeValueAsString(creationEvidence())
        + ",\"fixturePolicySeal\":"
        + mapper.writeValueAsString(seal)
        + "}";
  }

  private static FreshTenantCreationEvidence creationEvidence() {
    UUID request = UUID.fromString("945cc1d0-1e69-4532-b1c3-7dcde16c9256");
    UUID operation = UUID.fromString("e05352ce-7f61-4436-ac61-508edfe45e8a");
    String digest = "sha256:" + "f".repeat(64);
    return new FreshTenantCreationEvidence(
        1,
        "dev",
        request,
        operation,
        digest,
        CANONICAL_TENANT_ID,
        13L,
        "7",
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            "dev", request, operation, digest, CANONICAL_TENANT_ID, 13L, "7", "NEW_GAME_ROW"));
  }

  private static AuthoredWorldSourceEvidence sourceEvidence() {
    UUID registrationRequestId = UUID.fromString(OPERATION_ID);
    UUID operationId = UUID.fromString(SOURCE_OPERATION_ID);
    String namespace = RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_TARGET_NAMESPACE;
    String tenantSlug = "fixture-tenant";
    String worldSlug = RunOwnedInitialAdmissionFixtureWireCodec.WORLD_SLUG;
    String worldDisplayName = RunOwnedInitialAdmissionFixtureWireCodec.WORLD_DISPLAY_NAME;
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            namespace,
            registrationRequestId,
            CANONICAL_TENANT_ID,
            tenantSlug,
            worldSlug,
            worldDisplayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            namespace,
            registrationRequestId,
            operationId,
            requestDigest,
            CANONICAL_TENANT_ID,
            tenantSlug,
            worldSlug,
            worldDisplayName,
            13L,
            "7",
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        namespace,
        registrationRequestId,
        operationId,
        requestDigest,
        CANONICAL_TENANT_ID,
        tenantSlug,
        worldSlug,
        worldDisplayName,
        13L,
        "7",
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static PublishedReleaseBundle bundle() {
    String artifactDigest = "sha256:" + "d".repeat(64);
    return PublishedReleaseBundle.newBuilder()
        .setId(77L)
        .setVersionId(11L)
        .setVersionNumber(1)
        .setAttestationSchemaVersion("v1")
        .setPublishWorkflowId("publish-11")
        .setManifestHash("sha256:" + "c".repeat(64))
        .addRequiredManifestAssetKeys("manifest.json")
        .setGenerationConfigRevision("genrev-11")
        .setPublishedAt("2026-04-15T10:00:00")
        .setPublishedReleaseBundleRef("owner-release-ref-11")
        .setCanonicalTenantId(CANONICAL_TENANT_ID.toString())
        .setCanonicalVersionId("10000000-0000-4000-8000-000000000011")
        .setManifestSchemaVersion(1)
        .addArtifactDigests(
            PublishedArtifactDigest.newBuilder()
                .setUsageKey("manifest.json")
                .setArtifactKind("manifest")
                .setImmutableObjectKey("artifacts/sha256/" + artifactDigest.substring(7))
                .setContentDigest(artifactDigest)
                .setContentType("application/json")
                .setArtifactSchemaVersion(1)
                .build())
        .build();
  }

  private static VersionAssetArtifactState artifactState() {
    return VersionAssetArtifactState.newBuilder()
        .setTenantId("7")
        .setVersionId(11L)
        .setArtifactState(ArtifactState.ARTIFACT_STATE_PUBLISHED)
        .setStateEpoch(2L)
        .setManifestHash("sha256:" + "c".repeat(64))
        .setUpdatedAt("2026-04-15T10:00:00")
        .addExportedManifestAssetKeys("manifest.json")
        .setExportedVersionNumber(1)
        .build();
  }

  private static VersionStateSnapshot versionState() {
    return VersionStateSnapshot.newBuilder()
        .setTenantId("7")
        .setVersionId(11L)
        .setVersionState(VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED)
        .setVersionStateEpoch(77L)
        .setUpdatedAt("2026-04-15T10:00:00")
        .build();
  }

  private static LaunchDescriptor launchDescriptor() {
    return descriptorFor(
        bundle().getVersionId(), bundle().getId(), bundle().getPublishedReleaseBundleRef());
  }

  private static LaunchDescriptor descriptorFor(long versionId, long bundleId, String bundleRef) {
    AuthoredWorldSourceEvidence source = sourceEvidence();
    var request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_TARGET_NAMESPACE,
            OPERATION_ID,
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            17L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    var evidence =
        AuthoredWorldLaunchDescriptorEvidence.create(
            request,
            "launch-descriptor-fixture-11",
            versionId,
            false,
            null,
            "{}",
            bundle().getGenerationConfigRevision(),
            versionState().getVersionStateEpoch(),
            bundleId,
            bundleRef,
            false,
            null);
    return AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(evidence);
  }

  private static String encode(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }
}
