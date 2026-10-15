package net.firedevops.firemud.worldmanagement.data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

/**
 * Complete controlled upstream test values; this factory proves no owner writes or TLS authority.
 */
public final class RunOwnedCanonicalWorldLaunchEvidenceTestFixtures {
  private static final String CLAIM_DIGEST = controlledClaimDigest();
  private static final String RELEASE_REF = "8ce42642-53ae-4d07-aab8-37c43ce66e0c";

  private RunOwnedCanonicalWorldLaunchEvidenceTestFixtures() {}

  private static String controlledClaimDigest() {
    try {
      // Exact controlled fixture bytes; this is not a physical run claim.
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest("fixture-run\nfiremud-fixture-run\n".getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  public static RunOwnedCanonicalWorldLaunchEvidence create() {
    return createWithDescriptorOverrides(false, false);
  }

  public static RunOwnedCanonicalWorldLaunchEvidence createWithDescriptorOverrides(
      boolean scriptPatchVersionPresent, boolean remapSetIdPresent) {
    return create(
        false,
        "a".repeat(64),
        "c".repeat(64),
        "b".repeat(64),
        UUID.fromString("ed5d1541-a33e-4f8b-9b54-38c27d76ba6d"),
        9L,
        scriptPatchVersionPresent,
        remapSetIdPresent);
  }

  static RunOwnedCanonicalWorldLaunchEvidence create(
      boolean mismatchedSeal,
      String gameSessionLeafSha256,
      String worldManagementLeafSha256,
      String caCertificateSha256,
      UUID canonicalTenantId,
      long ownerAccountId,
      boolean scriptPatchVersionPresent,
      boolean remapSetIdPresent) {
    String operationId =
        RunOwnedInitialAdmissionFixtureWireCodec.fixtureOperationId(
                CLAIM_DIGEST,
                "fixture-run",
                "firemud-fixture-run",
                gameSessionLeafSha256,
                caCertificateSha256)
            .toString();
    UUID registrationId = UUID.fromString(operationId);
    UUID sourceOperationId = UUID.fromString("3f773701-e812-44f5-8ae0-7268d543beed");
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            "dev", registrationId, canonicalTenantId, "fixture-tenant", "demo", "Demo World");
    String sourceEvidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            "dev",
            registrationId,
            sourceOperationId,
            requestDigest,
            canonicalTenantId,
            "fixture-tenant",
            "demo",
            "Demo World",
            29L,
            "42",
            "NEW_GAME_ROW");
    AuthoredWorldSourceEvidence source =
        new AuthoredWorldSourceEvidence(
            1,
            "dev",
            registrationId,
            sourceOperationId,
            requestDigest,
            canonicalTenantId,
            "fixture-tenant",
            "demo",
            "Demo World",
            29L,
            "42",
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
            scriptPatchVersionPresent,
            scriptPatchVersionPresent ? "script-patch-fixture-11" : null,
            "{}",
            "genrev-11",
            77L,
            77L,
            RELEASE_REF,
            remapSetIdPresent,
            remapSetIdPresent ? "remap-set-fixture-11" : null);
    String manifestHash = "sha256:" + "c".repeat(64);
    var bundle =
        PublishedReleaseBundle.newBuilder()
            .setId(77L)
            .setVersionId(11L)
            .setVersionNumber(1)
            .setAttestationSchemaVersion("v1")
            .setCanonicalTenantId(canonicalTenantId.toString())
            .setCanonicalVersionId("10000000-0000-4000-8000-000000000011")
            .setPublishedReleaseBundleRef(RELEASE_REF)
            .setGenerationConfigRevision("genrev-11")
            .setManifestHash(manifestHash)
            .build();
    var artifact =
        VersionAssetArtifactState.newBuilder()
            .setTenantId("42")
            .setVersionId(11L)
            .setStateEpoch(2L)
            .setExportedVersionNumber(1)
            .setArtifactState(ArtifactState.ARTIFACT_STATE_PUBLISHED)
            .setManifestHash(manifestHash)
            .build();
    var version =
        VersionStateSnapshot.newBuilder()
            .setTenantId("42")
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
            42L,
            17L,
            ownerAccountId,
            "demo",
            "Demo World",
            "production",
            "Live Realm",
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            gameSessionLeafSha256,
            RunOwnedInitialAdmissionFixtureWireCodec.GAME_SESSION_URI_SAN,
            caCertificateSha256,
            RunOwnedInitialAdmissionFixtureWireCodec.WORLD_MANAGEMENT_URI_SAN,
            worldManagementLeafSha256,
            CLAIM_DIGEST);
    // Controlled constructor data remains untrusted; these commitments do not prove owner writes.
    UUID creationRequestId = UUID.fromString("1b3fdbda-a9f6-4cf5-b21e-b43f0e855f65");
    UUID creationOperationId = UUID.fromString("31e36104-3336-4cf5-899a-cb419f2f361e");
    String creationRequestDigest =
        GameTenantCreationDigest.requestDigest(
            "dev", creationRequestId, "42", "Fixture Game", null);
    var creation =
        new FreshTenantCreationEvidence(
            1,
            "dev",
            creationRequestId,
            creationOperationId,
            creationRequestDigest,
            canonicalTenantId,
            29L,
            "42",
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                "dev",
                creationRequestId,
                creationOperationId,
                creationRequestDigest,
                canonicalTenantId,
                29L,
                "42",
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
    UUID otherSealOperation = UUID.fromString("20000000-0000-4000-8000-000000000012");
    var receiptSeal =
        mismatchedSeal
            ? new RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicySeal(
                policySeal.schema(),
                policySeal.ownerService(),
                otherSealOperation,
                policySeal.requestDigest(),
                RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyEvidenceDigest(
                    otherSealOperation, policySeal.requestDigest()))
            : policySeal;
    var receipt =
        new RunOwnedInitialAdmissionFixtureWireCodec.SourceReceipt(
            RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_RECEIPT_SCHEMA,
            "fixture-run",
            "firemud-fixture-run",
            operationId,
            RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_RECEIPT_OWNER_SERVICE,
            RunOwnedInitialAdmissionFixtureWireCodec.GAME_SESSION_URI_SAN,
            gameSessionLeafSha256,
            caCertificateSha256,
            RunOwnedInitialAdmissionFixtureWireCodec.WORLD_MANAGEMENT_URI_SAN,
            worldManagementLeafSha256,
            source,
            launchRead,
            creation,
            receiptSeal);
    var policyRequestBytes =
        RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestBytes(
            capability, creation, source, launchRead);
    UUID readRequestId = UUID.fromString("5d85bfbe-ae28-4a86-a72a-57471a5761bf");
    var associationRequest =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureRequest(
            readRequestId, "dev", UUID.fromString(operationId), policySeal.evidenceDigest());
    var association =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult(
            associationRequest,
            142L,
            1001L,
            canonicalTenantId,
            UUID.fromString("20000000-0000-4000-8000-000000000011"),
            UUID.fromString(bundle.getCanonicalVersionId()),
            UUID.fromString("30000000-0000-4000-8000-000000000011"),
            UUID.fromString("40000000-0000-4000-8000-000000000011"),
            1L,
            CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus.STARTING,
            0L,
            policyRequestBytes,
            policySeal);
    return new RunOwnedCanonicalWorldLaunchEvidence(
        capability,
        receipt,
        descriptor,
        new byte[] {1},
        new byte[] {2},
        gameSessionLeafSha256,
        RunOwnedInitialAdmissionFixtureWireCodec.WORLD_MANAGEMENT_URI_SAN,
        worldManagementLeafSha256,
        caCertificateSha256,
        association);
  }
}
