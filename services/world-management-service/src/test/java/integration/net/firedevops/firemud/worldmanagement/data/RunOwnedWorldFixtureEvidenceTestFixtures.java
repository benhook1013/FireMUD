package net.firedevops.firemud.worldmanagement.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.v1.ArtifactState;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Complete controlled upstream data only; construction supplies no genuine producer or TLS proof.
 */
public final class RunOwnedWorldFixtureEvidenceTestFixtures {
  private RunOwnedWorldFixtureEvidenceTestFixtures() {}

  public static RunOwnedCanonicalWorldLaunchEvidence create(ObjectMapper objectMapper) {
    long gameDesignTenant = 8_810_000L + (positiveLong() % 1_000_000L);
    long gameSessionTenant = 18_810_000L + (positiveLong() % 1_000_000L);
    long sourceGameRow = positiveLong();
    String sourceTenantKey = Long.toString(gameDesignTenant);
    String tenantSlug = "fixture-tenant-" + UUID.randomUUID().toString().replace("-", "");
    String claimDigest =
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    String operationId =
        RunOwnedInitialAdmissionFixtureWireCodec.fixtureOperationId(
                claimDigest, "fixture-run", "firemud-fixture-run", "a".repeat(64), "b".repeat(64))
            .toString();
    String releaseRef = UUID.randomUUID().toString();
    UUID registrationId = UUID.fromString(operationId);
    UUID sourceOperationId = UUID.randomUUID();
    UUID canonicalTenantId = UUID.randomUUID();
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            "dev", registrationId, canonicalTenantId, tenantSlug, "demo", "Demo World");
    String sourceEvidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            "dev",
            registrationId,
            sourceOperationId,
            requestDigest,
            canonicalTenantId,
            tenantSlug,
            "demo",
            "Demo World",
            sourceGameRow,
            sourceTenantKey,
            "NEW_GAME_ROW");
    AuthoredWorldSourceEvidence source =
        new AuthoredWorldSourceEvidence(
            1,
            "dev",
            registrationId,
            sourceOperationId,
            requestDigest,
            canonicalTenantId,
            tenantSlug,
            "demo",
            "Demo World",
            sourceGameRow,
            sourceTenantKey,
            "NEW_GAME_ROW",
            sourceEvidenceDigest);
    var descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            "dev",
            operationId,
            canonicalTenantId,
            "demo",
            sourceOperationId,
            sourceEvidenceDigest,
            17L,
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
            descriptorRequest,
            "launch-descriptor-fixture-11",
            11L,
            false,
            null,
            "{}",
            "genrev-11",
            77L,
            77L,
            releaseRef,
            false,
            null);
    String manifestHash = "sha256:" + "c".repeat(64);
    var bundle =
        PublishedReleaseBundle.newBuilder()
            .setId(77L)
            .setVersionId(11L)
            .setVersionNumber(1)
            .setAttestationSchemaVersion("v1")
            .setCanonicalTenantId(canonicalTenantId.toString())
            .setCanonicalVersionId(UUID.randomUUID().toString())
            .setPublishedReleaseBundleRef(releaseRef)
            .setGenerationConfigRevision("genrev-11")
            .setManifestHash(manifestHash)
            .build();
    var artifact =
        VersionAssetArtifactState.newBuilder()
            .setTenantId(sourceTenantKey)
            .setVersionId(11L)
            .setStateEpoch(2L)
            .setExportedVersionNumber(1)
            .setArtifactState(ArtifactState.ARTIFACT_STATE_PUBLISHED)
            .setManifestHash(manifestHash)
            .build();
    var version =
        VersionStateSnapshot.newBuilder()
            .setTenantId(sourceTenantKey)
            .setVersionId(11L)
            .setVersionState(VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED)
            .setVersionStateEpoch(77L)
            .setUpdatedAt("2026-10-11T10:00:00Z")
            .build();
    RunOwnedInitialAdmissionFixtureWireCodec.Capability capability =
        new RunOwnedInitialAdmissionFixtureWireCodec.Capability(
            RunOwnedInitialAdmissionFixtureWireCodec.CAPABILITY_SCHEMA,
            "fixture-run",
            "firemud-fixture-run",
            operationId,
            gameDesignTenant,
            17L,
            9L,
            "demo",
            "Demo World",
            "production",
            "Live Realm",
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            "a".repeat(64),
            RunOwnedInitialAdmissionFixtureWireCodec.GAME_SESSION_URI_SAN,
            "b".repeat(64),
            RunOwnedInitialAdmissionFixtureWireCodec.WORLD_MANAGEMENT_URI_SAN,
            "c".repeat(64),
            claimDigest);
    // Controlled constructor data remains untrusted; these commitments do not prove owner writes.
    UUID creationRequestId = UUID.randomUUID();
    UUID creationOperationId = UUID.randomUUID();
    String creationRequestDigest =
        GameTenantCreationDigest.requestDigest(
            "dev", creationRequestId, sourceTenantKey, "Fixture Game", null);
    var creation =
        new FreshTenantCreationEvidence(
            1,
            "dev",
            creationRequestId,
            creationOperationId,
            creationRequestDigest,
            canonicalTenantId,
            sourceGameRow,
            sourceTenantKey,
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                "dev",
                creationRequestId,
                creationOperationId,
                creationRequestDigest,
                canonicalTenantId,
                sourceGameRow,
                sourceTenantKey,
                "NEW_GAME_ROW"));
    var launchRead =
        new RunOwnedInitialAdmissionFixtureWireCodec.LaunchReadEvidence(
            RunOwnedInitialAdmissionFixtureWireCodec.LAUNCH_READ_EVIDENCE_SCHEMA,
            17L,
            bundle,
            artifact,
            version,
            net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec
                .toLaunchDescriptorMessage(descriptor));
    String policyRequestDigest =
        RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestDigest(
            capability, creation, source, launchRead);
    var policySeal =
        new RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicySeal(
            RunOwnedInitialAdmissionFixtureWireCodec.FIXTURE_POLICY_SEAL_SCHEMA,
            RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_RECEIPT_OWNER_SERVICE,
            UUID.fromString(operationId),
            policyRequestDigest,
            RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyEvidenceDigest(
                UUID.fromString(operationId), policyRequestDigest));
    var receipt =
        new RunOwnedInitialAdmissionFixtureWireCodec.SourceReceipt(
            RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_RECEIPT_SCHEMA,
            "fixture-run",
            "firemud-fixture-run",
            operationId,
            RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_RECEIPT_OWNER_SERVICE,
            RunOwnedInitialAdmissionFixtureWireCodec.GAME_SESSION_URI_SAN,
            "a".repeat(64),
            "b".repeat(64),
            RunOwnedInitialAdmissionFixtureWireCodec.WORLD_MANAGEMENT_URI_SAN,
            "c".repeat(64),
            source,
            launchRead,
            creation,
            policySeal);
    var gsRequest =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureRequest(
            UUID.randomUUID(), "dev", UUID.fromString(operationId), policySeal.evidenceDigest());
    var gs =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult(
            gsRequest,
            gameSessionTenant,
            positiveLong(),
            canonicalTenantId,
            UUID.randomUUID(),
            UUID.fromString(bundle.getCanonicalVersionId()),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1L,
            CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus.STARTING,
            0L,
            RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestBytes(
                capability, creation, source, launchRead),
            policySeal);
    byte[] capabilityBytes = objectMapper.writeValueAsBytes(capability);
    Map<String, Object> receiptWire = new LinkedHashMap<>();
    receiptWire.put("schema", receipt.schema());
    receiptWire.put("runId", receipt.runId());
    receiptWire.put("composeProjectName", receipt.composeProjectName());
    receiptWire.put("fixtureOperationId", receipt.fixtureOperationId());
    receiptWire.put("ownerService", receipt.ownerService());
    receiptWire.put("gameSessionUriSan", receipt.gameSessionUriSan());
    receiptWire.put("gameSessionLeafSha256", receipt.gameSessionLeafSha256());
    receiptWire.put("caCertificateSha256", receipt.caCertificateSha256());
    receiptWire.put("worldManagementUriSan", receipt.worldManagementUriSan());
    receiptWire.put("worldManagementLeafSha256", receipt.worldManagementLeafSha256());
    receiptWire.put("sourceEvidence", source);
    receiptWire.put("creationEvidence", creation);
    receiptWire.put("fixturePolicySeal", policySeal);
    receiptWire.put(
        "launchReadEvidence",
        Map.of(
            "schema",
            launchRead.schema(),
            "gameTemplateId",
            launchRead.gameTemplateId(),
            "publishedReleaseBundleBase64",
            Base64.getEncoder().encodeToString(bundle.toByteArray()),
            "versionAssetArtifactStateBase64",
            Base64.getEncoder().encodeToString(artifact.toByteArray()),
            "versionStateBase64",
            Base64.getEncoder().encodeToString(version.toByteArray()),
            "launchDescriptorBase64",
            Base64.getEncoder().encodeToString(launchRead.launchDescriptor().toByteArray())));
    byte[] receiptBytes = objectMapper.writeValueAsBytes(receiptWire);
    assertThat(RunOwnedInitialAdmissionFixtureWireCodec.parseCapability(capabilityBytes))
        .isEqualTo(capability);
    assertThat(
            RunOwnedInitialAdmissionFixtureWireCodec.parseSourceReceipt(receiptBytes, capability))
        .isEqualTo(receipt);
    return new RunOwnedCanonicalWorldLaunchEvidence(
        capability,
        receipt,
        descriptor,
        capabilityBytes,
        receiptBytes,
        "a".repeat(64),
        RunOwnedInitialAdmissionFixtureWireCodec.WORLD_MANAGEMENT_URI_SAN,
        "c".repeat(64),
        "b".repeat(64),
        gs);
  }

  public static RunOwnedCanonicalWorldLaunchEvidence create() {
    return create(new ObjectMapper());
  }

  /** A fresh response correlation with identical allocation and original immutable policy bytes. */
  public static RunOwnedCanonicalWorldLaunchEvidence freshObservation(
      RunOwnedCanonicalWorldLaunchEvidence original) {
    var gs = original.gameSessionAssociation();
    var freshRequest =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureRequest(
            UUID.randomUUID(),
            gs.request().targetNamespace(),
            gs.request().fixtureOperationId(),
            gs.request().expectedFixturePolicyEvidenceDigest());
    var fresh =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult(
            freshRequest,
            gs.gameSessionTenantId(),
            gs.gameInstanceId(),
            gs.canonicalTenantId(),
            gs.gameInstanceUuid(),
            gs.canonicalVersionId(),
            gs.canonicalRealmId(),
            gs.playableStateNamespaceId(),
            gs.catalogRevision(),
            gs.currentGameInstanceStatus(),
            gs.currentRowVersion(),
            gs.fixturePolicyRequestBytes(),
            gs.fixturePolicySeal());
    return copy(original, original.sourceReceiptBytes(), fresh);
  }

  /** Valid JSON with changed retained bytes must not replace the original preparation binding. */
  public static RunOwnedCanonicalWorldLaunchEvidence changedReceiptBytes(
      RunOwnedCanonicalWorldLaunchEvidence original) {
    byte[] bytes = original.sourceReceiptBytes();
    byte[] changed = java.util.Arrays.copyOf(bytes, bytes.length + 1);
    changed[bytes.length] = '\n';
    assertThat(
            RunOwnedInitialAdmissionFixtureWireCodec.parseSourceReceipt(
                changed, original.capability()))
        .isEqualTo(original.sourceReceipt());
    return copy(original, changed, original.gameSessionAssociation());
  }

  /** Controlled contradictory GS owner allocation, for the actual retained-owner conflict guard. */
  public static RunOwnedCanonicalWorldLaunchEvidence secondAssignment(
      RunOwnedCanonicalWorldLaunchEvidence original) {
    var gs = original.gameSessionAssociation();
    long otherInstance = gs.gameInstanceId() == Long.MAX_VALUE ? 1L : gs.gameInstanceId() + 1L;
    var changed =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult(
            gs.request(),
            gs.gameSessionTenantId(),
            otherInstance,
            gs.canonicalTenantId(),
            UUID.randomUUID(),
            gs.canonicalVersionId(),
            gs.canonicalRealmId(),
            gs.playableStateNamespaceId(),
            gs.catalogRevision(),
            gs.currentGameInstanceStatus(),
            gs.currentRowVersion(),
            gs.fixturePolicyRequestBytes(),
            gs.fixturePolicySeal());
    return copy(original, original.sourceReceiptBytes(), changed);
  }

  public static PreparedWorldInstanceRequest request(
      RunOwnedCanonicalWorldLaunchEvidence evidence) {
    var gs = evidence.gameSessionAssociation();
    var descriptor = evidence.launchDescriptor();
    return new PreparedWorldInstanceRequest(
        gs.gameSessionTenantId(),
        gs.gameInstanceId(),
        descriptor.gameTemplateId(),
        evidence.capability().operationId(),
        descriptor.launchDescriptorId(),
        descriptor.versionId(),
        null,
        descriptor.runtimeFlagsJson(),
        descriptor.generationConfigRevision(),
        descriptor.releaseBundleId(),
        descriptor.publishedReleaseBundleRef(),
        descriptor.versionStateEpoch(),
        null);
  }

  private static RunOwnedCanonicalWorldLaunchEvidence copy(
      RunOwnedCanonicalWorldLaunchEvidence original,
      byte[] receiptBytes,
      CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult gs) {
    return new RunOwnedCanonicalWorldLaunchEvidence(
        original.capability(),
        original.sourceReceipt(),
        original.launchDescriptor(),
        original.capabilityBytes(),
        receiptBytes,
        original.gameSessionLeafSha256(),
        original.worldManagementUriSan(),
        original.worldManagementLeafSha256(),
        original.caCertificateSha256(),
        gs);
  }

  private static long positiveLong() {
    return Math.max(1L, UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE);
  }
}
