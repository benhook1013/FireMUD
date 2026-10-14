package net.firedevops.firemud.gamesession.data;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.v1.ArtifactState;
import net.firedevops.firemud.gamedesign.v1.LaunchDescriptor;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import tools.jackson.databind.ObjectMapper;

/** Controlled test-only owner inputs parsed into the verified run-owned fixture carrier. */
public final class RunOwnedInitialAdmissionLaunchEvidenceTestFixture {
  private static final String RUN_ID = "fixture-evidence-test";
  private static final String PROJECT_NAME = "firemud-smoke-fixture-evidence-test";
  private static final String LEAF_SHA = "a".repeat(64);
  private static final String WORLD_LEAF_SHA = "c".repeat(64);
  private static final String CA_SHA = "b".repeat(64);
  private static final String CANONICAL_VERSION_ID = "10000000-0000-4000-8000-000000000011";
  private static final String CLAIM_DIGEST = "d".repeat(64);
  private static final String OPERATION_ID =
      RunOwnedInitialAdmissionFixtureWireCodec.fixtureOperationId(
              CLAIM_DIGEST, RUN_ID, PROJECT_NAME, LEAF_SHA, CA_SHA)
          .toString();
  private static final ObjectMapper JSON = new ObjectMapper();

  private RunOwnedInitialAdmissionLaunchEvidenceTestFixture() {}

  /** Derived control-plane operation used by service tests that consume this fixed test fixture. */
  public static String operationId() {
    return OPERATION_ID;
  }

  public static RunOwnedInitialAdmissionLaunchEvidence create(
      StartSessionRequest request, AuthoredWorldSourceEvidence sourceEvidence) {
    return create(
        request,
        sourceEvidence,
        defaultBundle(sourceEvidence, "prb:" + request.tenantId() + ":11:77"),
        defaultArtifactState(request.tenantId()),
        defaultVersionState(request.tenantId()));
  }

  public static RunOwnedInitialAdmissionLaunchEvidence create(
      StartSessionRequest request,
      AuthoredWorldSourceEvidence sourceEvidence,
      String publishedReleaseBundleRef) {
    return create(
        request,
        sourceEvidence,
        defaultBundle(sourceEvidence, publishedReleaseBundleRef),
        defaultArtifactState(request.tenantId()),
        defaultVersionState(request.tenantId()));
  }

  public static RunOwnedInitialAdmissionLaunchEvidence create(
      StartSessionRequest request,
      AuthoredWorldSourceEvidence sourceEvidence,
      PublishedReleaseBundle bundle,
      VersionAssetArtifactState artifactState,
      VersionStateSnapshot versionState) {
    if (!OPERATION_ID.equals(request.controlPlaneRequestId())) {
      throw new IllegalArgumentException("Test fixture request must use its derived operation ID");
    }
    var capability =
        RunOwnedInitialAdmissionFixtureCapability.parseAndValidate(
            capabilityJson(request).getBytes(StandardCharsets.UTF_8), RUN_ID, PROJECT_NAME, pins());
    var receipt =
        RunOwnedInitialAdmissionFixtureCapability.parseSourceReceipt(
            sourceReceiptJson(request, sourceEvidence, bundle, artifactState, versionState)
                .getBytes(StandardCharsets.UTF_8),
            RUN_ID,
            PROJECT_NAME,
            capability,
            pins());
    return RunOwnedInitialAdmissionLaunchEvidence.fromVerifiedSourceReceipt(capability, receipt);
  }

  /** Builds deliberately unchecked evidence for isolated service preflight rejection tests. */
  public static RunOwnedInitialAdmissionLaunchEvidence createUncheckedForServicePreflightTests(
      StartSessionRequest request,
      AuthoredWorldSourceEvidence sourceEvidence,
      PublishedReleaseBundle bundle,
      VersionAssetArtifactState artifactState,
      VersionStateSnapshot versionState) {
    return new RunOwnedInitialAdmissionLaunchEvidence(
        sourceEvidence,
        RunOwnedInitialAdmissionLaunchEvidence.SCHEMA,
        request.gameTemplateId(),
        bundle,
        artifactState,
        versionState,
        launchDescriptor(request, sourceEvidence, bundle, versionState));
  }

  private static RunOwnedInitialAdmissionFixtureCapability.CertificatePins pins() {
    return new RunOwnedInitialAdmissionFixtureCapability.CertificatePins(
        LEAF_SHA, RunOwnedInitialAdmissionFixtureCapability.GAME_SESSION_URI_SAN, CA_SHA);
  }

  private static PublishedReleaseBundle defaultBundle(
      AuthoredWorldSourceEvidence sourceEvidence, String bundleRef) {
    String manifestHash = "sha256:" + "c".repeat(64);
    String artifactDigest = "sha256:" + "d".repeat(64);
    return PublishedReleaseBundle.newBuilder()
        .setId(77L)
        .setVersionId(11L)
        .setVersionNumber(1)
        .setAttestationSchemaVersion("v1")
        .setPublishWorkflowId("publish-11")
        .setManifestHash(manifestHash)
        .addRequiredManifestAssetKeys("manifest.json")
        .setGenerationConfigRevision("genrev-11")
        .setPublishedAt("2026-04-15T10:00:00")
        .setPublishedReleaseBundleRef(bundleRef)
        .setCanonicalTenantId(sourceEvidence.canonicalTenantId().toString())
        .setCanonicalVersionId(CANONICAL_VERSION_ID)
        .setManifestSchemaVersion(1)
        .addArtifactDigests(
            net.firedevops.firemud.gamedesign.v1.PublishedArtifactDigest.newBuilder()
                .setUsageKey("manifest.json")
                .setArtifactKind("manifest")
                .setImmutableObjectKey(
                    "artifacts/sha256/" + artifactDigest.substring("sha256:".length()))
                .setContentDigest(artifactDigest)
                .setContentType("application/json")
                .setArtifactSchemaVersion(1)
                .build())
        .build();
  }

  private static VersionAssetArtifactState defaultArtifactState(long tenantId) {
    return VersionAssetArtifactState.newBuilder()
        .setTenantId(Long.toString(tenantId))
        .setVersionId(11L)
        .setArtifactState(ArtifactState.ARTIFACT_STATE_PUBLISHED)
        .setStateEpoch(2L)
        .setManifestHash("sha256:" + "c".repeat(64))
        .setUpdatedAt("2026-04-15T10:00:00")
        .addExportedManifestAssetKeys("manifest.json")
        .setExportedVersionNumber(1)
        .build();
  }

  private static VersionStateSnapshot defaultVersionState(long tenantId) {
    return VersionStateSnapshot.newBuilder()
        .setTenantId(Long.toString(tenantId))
        .setVersionId(11L)
        .setVersionState(VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED)
        .setVersionStateEpoch(77L)
        .setUpdatedAt("2026-04-15T10:00:00")
        .build();
  }

  private static String capabilityJson(StartSessionRequest request) {
    return json(wireCapability(request));
  }

  private static RunOwnedInitialAdmissionFixtureWireCodec.Capability wireCapability(
      StartSessionRequest request) {
    return new RunOwnedInitialAdmissionFixtureWireCodec.Capability(
        RunOwnedInitialAdmissionFixtureWireCodec.CAPABILITY_SCHEMA,
        RUN_ID,
        PROJECT_NAME,
        request.controlPlaneRequestId(),
        request.tenantId(),
        request.gameTemplateId(),
        request.ownerAccountId(),
        RunOwnedInitialAdmissionFixtureWireCodec.WORLD_SLUG,
        RunOwnedInitialAdmissionFixtureWireCodec.WORLD_DISPLAY_NAME,
        RunOwnedInitialAdmissionFixtureWireCodec.REALM_SLUG,
        RunOwnedInitialAdmissionFixtureWireCodec.REALM_DISPLAY_NAME,
        true,
        true,
        false,
        RunOwnedInitialAdmissionFixtureWireCodec.STATE_SCOPE,
        RunOwnedInitialAdmissionFixtureWireCodec.CHARACTER_CREATION_POLICY,
        LEAF_SHA,
        RunOwnedInitialAdmissionFixtureWireCodec.GAME_SESSION_URI_SAN,
        CA_SHA,
        RunOwnedInitialAdmissionFixtureWireCodec.WORLD_MANAGEMENT_URI_SAN,
        WORLD_LEAF_SHA,
        CLAIM_DIGEST);
  }

  private static String sourceReceiptJson(
      StartSessionRequest request,
      AuthoredWorldSourceEvidence source,
      PublishedReleaseBundle bundle,
      VersionAssetArtifactState artifactState,
      VersionStateSnapshot versionState) {
    var capability = wireCapability(request);
    FreshTenantCreationEvidence creation = creationEvidence(source);
    LaunchDescriptor descriptor = launchDescriptor(request, source, bundle, versionState);
    var launchReadEvidence =
        new RunOwnedInitialAdmissionFixtureWireCodec.LaunchReadEvidence(
            RunOwnedInitialAdmissionFixtureWireCodec.LAUNCH_READ_EVIDENCE_SCHEMA,
            request.gameTemplateId(),
            bundle,
            artifactState,
            versionState,
            descriptor);
    String policyRequestDigest =
        RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestDigest(
            capability, creation, source, launchReadEvidence);
    UUID operationId = UUID.fromString(capability.operationId());
    var policySeal =
        new RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicySeal(
            RunOwnedInitialAdmissionFixtureWireCodec.FIXTURE_POLICY_SEAL_SCHEMA,
            RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_RECEIPT_OWNER_SERVICE,
            operationId,
            policyRequestDigest,
            RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyEvidenceDigest(
                operationId, policyRequestDigest));

    Map<String, Object> launchReadJson = new LinkedHashMap<>();
    launchReadJson.put("schema", launchReadEvidence.schema());
    launchReadJson.put("gameTemplateId", launchReadEvidence.gameTemplateId());
    launchReadJson.put(
        "publishedReleaseBundleBase64",
        encode(launchReadEvidence.publishedReleaseBundle().toByteArray()));
    launchReadJson.put(
        "versionAssetArtifactStateBase64",
        encode(launchReadEvidence.versionAssetArtifactState().toByteArray()));
    launchReadJson.put(
        "versionStateBase64", encode(launchReadEvidence.versionState().toByteArray()));
    launchReadJson.put(
        "launchDescriptorBase64", encode(launchReadEvidence.launchDescriptor().toByteArray()));

    Map<String, Object> receipt = new LinkedHashMap<>();
    receipt.put("schema", RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_RECEIPT_SCHEMA);
    receipt.put("runId", capability.runId());
    receipt.put("composeProjectName", capability.composeProjectName());
    receipt.put("fixtureOperationId", capability.operationId());
    receipt.put(
        "ownerService", RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_RECEIPT_OWNER_SERVICE);
    receipt.put("gameSessionUriSan", capability.gameSessionUriSan());
    receipt.put("gameSessionLeafSha256", capability.gameSessionLeafSha256());
    receipt.put("caCertificateSha256", capability.caCertificateSha256());
    receipt.put("worldManagementUriSan", capability.worldManagementUriSan());
    receipt.put("worldManagementLeafSha256", capability.worldManagementLeafSha256());
    receipt.put("sourceEvidence", source);
    receipt.put("launchReadEvidence", launchReadJson);
    receipt.put("creationEvidence", creation);
    receipt.put("fixturePolicySeal", policySeal);
    return json(receipt);
  }

  private static FreshTenantCreationEvidence creationEvidence(AuthoredWorldSourceEvidence source) {
    UUID creationRequestId = UUID.fromString("945cc1d0-1e69-4532-b1c3-7dcde16c9256");
    UUID creationOperationId = UUID.fromString("e05352ce-7f61-4436-ac61-508edfe45e8a");
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            source.targetNamespace(),
            creationRequestId,
            source.sourceGameTenantKey(),
            source.worldDisplayName(),
            null);
    return new FreshTenantCreationEvidence(
        1,
        source.targetNamespace(),
        creationRequestId,
        creationOperationId,
        requestDigest,
        source.canonicalTenantId(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        GameTenantCreationDigest.evidenceDigest(
            source.targetNamespace(),
            creationRequestId,
            creationOperationId,
            requestDigest,
            source.canonicalTenantId(),
            source.sourceGameRowId(),
            source.sourceGameTenantKey(),
            source.provenanceKind()));
  }

  private static String json(Object value) {
    return JSON.writeValueAsString(value);
  }

  private static LaunchDescriptor launchDescriptor(
      StartSessionRequest request,
      AuthoredWorldSourceEvidence source,
      PublishedReleaseBundle bundle,
      VersionStateSnapshot versionState) {
    var descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_TARGET_NAMESPACE,
            request.controlPlaneRequestId(),
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            request.gameTemplateId(),
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    var descriptorEvidence =
        AuthoredWorldLaunchDescriptorEvidence.create(
            descriptorRequest,
            "launch-descriptor-fixture-11",
            bundle.getVersionId(),
            false,
            null,
            "{}",
            bundle.getGenerationConfigRevision(),
            versionState.getVersionStateEpoch(),
            bundle.getId(),
            bundle.getPublishedReleaseBundleRef(),
            false,
            null);
    return AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(descriptorEvidence);
  }

  private static String encode(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }
}
