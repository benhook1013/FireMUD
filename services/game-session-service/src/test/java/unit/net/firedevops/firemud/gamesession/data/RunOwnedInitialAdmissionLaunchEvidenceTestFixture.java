package net.firedevops.firemud.gamesession.data;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.ArtifactState;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;

/** Test-only builder that obtains the run-owned carrier through the closed receipt parser. */
public final class RunOwnedInitialAdmissionLaunchEvidenceTestFixture {
  private static final String RUN_ID = "fixture-evidence-test";
  private static final String PROJECT_NAME = "firemud-smoke-fixture-evidence-test";
  private static final String LEAF_SHA = "a".repeat(64);
  private static final String CA_SHA = "b".repeat(64);
  private static final String CANONICAL_VERSION_ID = "10000000-0000-4000-8000-000000000011";

  private RunOwnedInitialAdmissionLaunchEvidenceTestFixture() {}

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
        versionState);
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
    return """
        {"schema":"%s","runId":"%s","composeProjectName":"%s","operationId":"%s","tenantId":%d,"gameTemplateId":%d,"ownerAccountId":%d,"worldSlug":"demo","worldDisplayName":"Demo World","realmSlug":"production","realmDisplayName":"Live Realm","visible":true,"publicProductionRealm":true,"requiresCharacterSelection":false,"stateScope":"SHARED","characterCreationPolicy":"ALLOW_NEW","gameSessionLeafSha256":"%s","gameSessionUriSan":"%s","caCertificateSha256":"%s"}%n\
        """
        .formatted(
            RunOwnedInitialAdmissionFixtureCapability.CAPABILITY_SCHEMA,
            RUN_ID,
            PROJECT_NAME,
            request.controlPlaneRequestId(),
            request.tenantId(),
            request.gameTemplateId(),
            request.ownerAccountId(),
            LEAF_SHA,
            RunOwnedInitialAdmissionFixtureCapability.GAME_SESSION_URI_SAN,
            CA_SHA);
  }

  private static String sourceReceiptJson(
      StartSessionRequest request,
      AuthoredWorldSourceEvidence source,
      PublishedReleaseBundle bundle,
      VersionAssetArtifactState artifactState,
      VersionStateSnapshot versionState) {
    return """
        {"schema":"%s","runId":"%s","composeProjectName":"%s","fixtureOperationId":"%s","ownerService":"%s","gameSessionUriSan":"%s","gameSessionLeafSha256":"%s","caCertificateSha256":"%s","sourceEvidence":{"schemaVersion":%d,"targetNamespace":"%s","registrationRequestId":"%s","operationId":"%s","requestDigest":"%s","canonicalTenantId":"%s","tenantSlug":"%s","worldSlug":"%s","worldDisplayName":"%s","sourceGameRowId":%d,"sourceGameTenantKey":"%s","provenanceKind":"%s","evidenceDigest":"%s"},"launchReadEvidence":{"schema":"%s","gameTemplateId":%d,"publishedReleaseBundleBase64":"%s","versionAssetArtifactStateBase64":"%s","versionStateBase64":"%s"}}%n\
        """
        .formatted(
            RunOwnedInitialAdmissionFixtureCapability.SOURCE_RECEIPT_SCHEMA,
            RUN_ID,
            PROJECT_NAME,
            request.controlPlaneRequestId(),
            RunOwnedInitialAdmissionFixtureCapability.SOURCE_RECEIPT_OWNER_SERVICE,
            RunOwnedInitialAdmissionFixtureCapability.GAME_SESSION_URI_SAN,
            LEAF_SHA,
            CA_SHA,
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
            RunOwnedInitialAdmissionLaunchEvidence.SCHEMA,
            request.gameTemplateId(),
            encode(bundle.toByteArray()),
            encode(artifactState.toByteArray()),
            encode(versionState.toByteArray()));
  }

  private static String encode(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }
}
