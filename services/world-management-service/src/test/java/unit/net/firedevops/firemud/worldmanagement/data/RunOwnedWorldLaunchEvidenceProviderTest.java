package net.firedevops.firemud.worldmanagement.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.ArtifactState;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunOwnedWorldLaunchEvidenceProviderTest {
  private static final String OPERATION_ID = "7f1e5306-e9a7-46d9-b5cf-75348e20ffbd";
  private static final String RELEASE_REF = "8ce42642-53ae-4d07-aab8-37c43ce66e0c";

  @TempDir Path temporaryDirectory;

  @Test
  void bindingDigestNormalizesAcceptedBlankOptionalOverridesToAbsent() {
    RunOwnedWorldLaunchEvidence evidence = fixtureEvidence();

    String absent = evidence.bindingDigest(request(null, null), 1L);
    assertThat(evidence.bindingDigest(request("", ""), 1L)).isEqualTo(absent);
    assertThat(evidence.bindingDigest(request("  ", "  "), 1L)).isEqualTo(absent);
  }

  @Test
  void disabledFixtureFailsBeforeReadingFilesOrCallingGameDesign() {
    GameDesignClient gameDesignClient = mock(GameDesignClient.class);
    RunOwnedWorldLaunchEvidenceProvider provider =
        new RunOwnedWorldLaunchEvidenceProvider(
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
  }

  @Test
  void enabledFixtureRequiresAbsoluteBoundedReadOnlyRegularWireFilesBeforeOwnerReads()
      throws Exception {
    GameDesignClient gameDesignClient = mock(GameDesignClient.class);
    Path relativeCapability = temporaryDirectory.resolve("capability.json");
    Path receipt = temporaryDirectory.resolve("receipt.json");
    Files.writeString(relativeCapability, "{}", StandardCharsets.UTF_8);
    Files.writeString(receipt, "{}", StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(
        relativeCapability, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ));
    Files.setPosixFilePermissions(
        receipt, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ));
    RunOwnedWorldLaunchEvidenceProvider provider =
        new RunOwnedWorldLaunchEvidenceProvider(
            true,
            relativeCapability.toString(),
            receipt.toString(),
            "fixture-run",
            "firemud-fixture-run",
            new CommonGrpcClientProperties(),
            gameDesignClient);

    assertThatThrownBy(provider::requireAuthenticatedEvidence)
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(gameDesignClient);

    Files.setPosixFilePermissions(
        relativeCapability,
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    RunOwnedWorldLaunchEvidenceProvider writable =
        new RunOwnedWorldLaunchEvidenceProvider(
            true,
            relativeCapability.toString(),
            receipt.toString(),
            "fixture-run",
            "firemud-fixture-run",
            new CommonGrpcClientProperties(),
            gameDesignClient);
    assertThatThrownBy(writable::requireAuthenticatedEvidence)
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(gameDesignClient);
  }

  @Test
  void aSymlinkIsNotAcceptedAsAnImmutableCapabilityFile() throws Exception {
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
        new RunOwnedWorldLaunchEvidenceProvider(
            true,
            capability.toString(),
            receipt.toString(),
            "fixture-run",
            "firemud-fixture-run",
            new CommonGrpcClientProperties(),
            gameDesignClient);

    assertThatThrownBy(provider::requireAuthenticatedEvidence)
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(gameDesignClient);
  }

  private static PreparedWorldInstanceRequest request(String patch, String remap) {
    return new PreparedWorldInstanceRequest(
        42L,
        101L,
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
            .setPublishedReleaseBundleRef(RELEASE_REF)
            .setGenerationConfigRevision("genrev-11")
            .setManifestHash(manifestHash)
            .build();
    var artifact =
        VersionAssetArtifactState.newBuilder()
            .setVersionId(11L)
            .setArtifactState(ArtifactState.ARTIFACT_STATE_PUBLISHED)
            .setManifestHash(manifestHash)
            .build();
    var version =
        VersionStateSnapshot.newBuilder()
            .setVersionId(11L)
            .setVersionState(VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED)
            .setVersionStateEpoch(77L)
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
            "c".repeat(64));
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
            new RunOwnedInitialAdmissionFixtureWireCodec.LaunchReadEvidence(
                RunOwnedInitialAdmissionFixtureWireCodec.LAUNCH_READ_EVIDENCE_SCHEMA,
                17L,
                bundle,
                artifact,
                version,
                net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec
                    .toLaunchDescriptorMessage(descriptor)));
    return new RunOwnedWorldLaunchEvidence(
        capability,
        receipt,
        descriptor,
        new byte[] {1},
        new byte[] {2},
        "a".repeat(64),
        RunOwnedInitialAdmissionFixtureWireCodec.WORLD_MANAGEMENT_URI_SAN,
        "c".repeat(64),
        "b".repeat(64));
  }
}
