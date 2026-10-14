package net.firedevops.firemud.worldmanagement.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
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
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class RunOwnedWorldLaunchEvidenceProviderTest {
  private static final String CLAIM_DIGEST = controlledClaimDigest();
  private static final String OPERATION_ID =
      RunOwnedInitialAdmissionFixtureWireCodec.fixtureOperationId(
              CLAIM_DIGEST, "fixture-run", "firemud-fixture-run", "a".repeat(64), "b".repeat(64))
          .toString();
  private static final String RELEASE_REF = "8ce42642-53ae-4d07-aab8-37c43ce66e0c";

  @TempDir Path temporaryDirectory;

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

  @Test
  void bindingDigestNormalizesAcceptedBlankOptionalOverridesToAbsent() {
    RunOwnedWorldLaunchEvidence evidence = fixtureEvidence();

    String absent = evidence.bindingDigest(request(null, null), 1L);
    assertThat(evidence.bindingDigest(request("", ""), 1L)).isEqualTo(absent);
    assertThat(evidence.bindingDigest(request("  ", "  "), 1L)).isEqualTo(absent);
  }

  @Test
  void launchEvidenceBindsStartingAllocationPrivateKeysFromOwnerProjection() {
    RunOwnedWorldLaunchEvidence evidence = fixtureEvidence();

    evidence.requireMatches(request(null, null));
    assertThat(evidence.gameSessionAssociation().gameSessionTenantId()).isEqualTo(142L);
    assertThat(evidence.gameSessionAssociation().gameInstanceId()).isEqualTo(1001L);
    assertThat(evidence.gameSessionAssociation().currentGameInstanceStatus())
        .isEqualTo(
            CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus.STARTING);
    assertThat(evidence.capability().tenantId()).isEqualTo(42L);
    assertThat(evidence.gameSessionAssociation().gameSessionTenantId())
        .isNotEqualTo(evidence.capability().tenantId());
    assertThatThrownBy(() -> evidence.requireGameSessionAllocation(42L, 1001L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("RUN_OWNED_WORLD_GAME_SESSION_ALLOCATION_MISMATCH:");
  }

  @Test
  void disabledFixtureFailsBeforeReadingFilesOrCallingGameDesign(CapturedOutput output) {
    GameDesignClient gameDesignClient = mock(GameDesignClient.class);
    RunOwnedWorldLaunchEvidenceProvider provider =
        provider(
            false,
            temporaryDirectory.resolve("missing-capability.json").toString(),
            temporaryDirectory.resolve("missing-receipt.json").toString(),
            "fixture-run",
            "firemud-fixture-run",
            new CommonGrpcClientProperties(),
            gameDesignClient);

    assertThatThrownBy(provider::requireAuthenticatedEvidence)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Run-owned World launch evidence is invalid");
    verifyNoInteractions(gameDesignClient);

    assertThat(output)
        .contains("Run-owned World launch evidence verification rejected; stage=ENABLED")
        .doesNotContain("missing-capability.json", "missing-receipt.json", "fixture-run");
  }

  @Test
  void malformedWireLogsOnlyTheFixedVerificationStageAndPreservesWireError(CapturedOutput output)
      throws Exception {
    GameDesignClient gameDesignClient = mock(GameDesignClient.class);
    Path capability = temporaryDirectory.resolve("sensitive-capability-path.json");
    Path receipt = temporaryDirectory.resolve("sensitive-receipt-path.json");
    Files.writeString(capability, "capability-payload-secret-marker", StandardCharsets.UTF_8);
    Files.writeString(receipt, "source-receipt-payload-secret-marker", StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(
        capability, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ));
    Files.setPosixFilePermissions(
        receipt, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ));
    RunOwnedWorldLaunchEvidenceProvider provider =
        provider(
            true,
            capability.toString(),
            receipt.toString(),
            "fixture-run",
            "firemud-fixture-run",
            new CommonGrpcClientProperties(),
            gameDesignClient);

    assertThatThrownBy(provider::requireAuthenticatedEvidence)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Run-owned initial-admission fixture wire data is invalid");
    verifyNoInteractions(gameDesignClient);

    assertThat(output)
        .contains("Run-owned World launch evidence verification rejected; stage=WIRE_PARSE")
        .doesNotContain(
            "sensitive-capability-path.json",
            "sensitive-receipt-path.json",
            "capability-payload-secret-marker",
            "source-receipt-payload-secret-marker",
            "fixture-run",
            "firemud-fixture-run",
            "InvalidWireDataException",
            "Caused by:");
  }

  @Test
  void enabledFixtureRejectsRelativeCapabilityPathAtFileReadStage(CapturedOutput output)
      throws Exception {
    GameDesignClient gameDesignClient = mock(GameDesignClient.class);
    Path capability = temporaryDirectory.resolve("relative-capability.json");
    Path receipt = temporaryDirectory.resolve("relative-receipt.json");
    Files.writeString(capability, "{}", StandardCharsets.UTF_8);
    Files.writeString(receipt, "{}", StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(
        capability, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ));
    Files.setPosixFilePermissions(
        receipt, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ));
    Path workingDirectory = Path.of("").toAbsolutePath().normalize();
    String relativeCapabilityPath =
        workingDirectory.relativize(capability.toAbsolutePath().normalize()).toString();
    RunOwnedWorldLaunchEvidenceProvider provider =
        provider(
            true,
            relativeCapabilityPath,
            receipt.toString(),
            "fixture-run",
            "firemud-fixture-run",
            new CommonGrpcClientProperties(),
            gameDesignClient);

    assertThatThrownBy(provider::requireAuthenticatedEvidence)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Run-owned World launch evidence is invalid");
    verifyNoInteractions(gameDesignClient);
    assertFileRejectionStoppedBeforeWireParse(output);
  }

  @Test
  void enabledFixtureRejectsWritableCapabilityAtFileReadStage(CapturedOutput output)
      throws Exception {
    GameDesignClient gameDesignClient = mock(GameDesignClient.class);
    Path capability = temporaryDirectory.resolve("writable-capability.json");
    Files.writeString(capability, "{}", StandardCharsets.UTF_8);
    Path receipt = temporaryDirectory.resolve("receipt.json");
    Files.writeString(receipt, "{}", StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(
        receipt, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ));
    RunOwnedWorldLaunchEvidenceProvider provider =
        provider(
            true,
            capability.toString(),
            receipt.toString(),
            "fixture-run",
            "firemud-fixture-run",
            new CommonGrpcClientProperties(),
            gameDesignClient);

    assertThatThrownBy(provider::requireAuthenticatedEvidence)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Run-owned World launch evidence is invalid");
    verifyNoInteractions(gameDesignClient);
    assertFileRejectionStoppedBeforeWireParse(output);
  }

  @Test
  void enabledFixtureRejectsCapabilitySymlinkAtFileReadStage(CapturedOutput output)
      throws Exception {
    GameDesignClient gameDesignClient = mock(GameDesignClient.class);
    Path target = temporaryDirectory.resolve("target.json");
    Path capability = temporaryDirectory.resolve("capability.json");
    Path receipt = temporaryDirectory.resolve("receipt.json");
    Files.writeString(target, "{}", StandardCharsets.UTF_8);
    Files.writeString(receipt, "{}", StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(
        target, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ));
    Files.setPosixFilePermissions(
        receipt, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ));
    Files.createSymbolicLink(capability, target.getFileName());
    RunOwnedWorldLaunchEvidenceProvider provider =
        provider(
            true,
            capability.toString(),
            receipt.toString(),
            "fixture-run",
            "firemud-fixture-run",
            new CommonGrpcClientProperties(),
            gameDesignClient);

    assertThatThrownBy(provider::requireAuthenticatedEvidence)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Run-owned World launch evidence is invalid");
    verifyNoInteractions(gameDesignClient);
    assertFileRejectionStoppedBeforeWireParse(output);
  }

  private static void assertFileRejectionStoppedBeforeWireParse(CapturedOutput output) {
    assertThat(output)
        .contains("Run-owned World launch evidence verification rejected; stage=CAPABILITY_FILE")
        .doesNotContain("stage=WIRE_PARSE");
  }

  private static PreparedWorldInstanceRequest request(String patch, String remap) {
    return new PreparedWorldInstanceRequest(
        142L,
        1001L,
        17L,
        OPERATION_ID,
        "launch-descriptor-fixture-11",
        11L,
        patch,
        "{}",
        "genrev-11",
        77L,
        RELEASE_REF,
        77L,
        remap);
  }

  private static RunOwnedWorldLaunchEvidence fixtureEvidence() {
    UUID registrationId = UUID.fromString(OPERATION_ID);
    UUID sourceOperationId = UUID.fromString("3f773701-e812-44f5-8ae0-7268d543beed");
    UUID canonicalTenantId = UUID.fromString("ed5d1541-a33e-4f8b-9b54-38c27d76ba6d");
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
            OPERATION_ID,
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
            RELEASE_REF,
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
            OPERATION_ID,
            42L,
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
            UUID.fromString(OPERATION_ID),
            policyRequestDigest,
            RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyEvidenceDigest(
                UUID.fromString(OPERATION_ID), policyRequestDigest));
    var receipt =
        new RunOwnedInitialAdmissionFixtureWireCodec.SourceReceipt(
            RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_RECEIPT_SCHEMA,
            "fixture-run",
            "firemud-fixture-run",
            OPERATION_ID,
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
    var policyRequestBytes =
        RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestBytes(
            capability, creation, source, launchRead);
    UUID readRequestId = UUID.fromString("5d85bfbe-ae28-4a86-a72a-57471a5761bf");
    var associationRequest =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureRequest(
            readRequestId, "dev", UUID.fromString(OPERATION_ID), policySeal.evidenceDigest());
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
    return new RunOwnedWorldLaunchEvidence(
        capability,
        receipt,
        descriptor,
        new byte[] {1},
        new byte[] {2},
        "a".repeat(64),
        RunOwnedInitialAdmissionFixtureWireCodec.WORLD_MANAGEMENT_URI_SAN,
        "c".repeat(64),
        "b".repeat(64),
        association);
  }

  private static RunOwnedWorldLaunchEvidenceProvider provider(
      boolean enabled,
      String capabilityPath,
      String receiptPath,
      String runId,
      String composeProjectName,
      CommonGrpcClientProperties grpcProperties,
      GameDesignClient gameDesignClient) {
    return new RunOwnedWorldLaunchEvidenceProvider(
        enabled,
        capabilityPath,
        receiptPath,
        runId,
        composeProjectName,
        grpcProperties,
        gameDesignClient,
        null,
        null,
        "dev");
  }
}
