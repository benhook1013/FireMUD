package net.firedevops.firemud.worldmanagement.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcPeerCertificateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class RunOwnedWorldLaunchEvidenceProviderTest {
  @TempDir Path temporaryDirectory;

  @Test
  void retainedCarrierRequiresOriginalFixedPeerAndLeafForEveryOwnerOperation() {
    RunOwnedWorldLaunchEvidence evidence = RunOwnedWorldLaunchEvidenceTestFixtures.create();
    assertThatThrownBy(() -> withControlledPeer(null, null, evidence::requireFixedGameSessionPeer))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                withControlledPeer(
                    peer("dev", "game-session-service"),
                    null,
                    evidence::requireFixedGameSessionPeer))
        .isInstanceOf(SecurityException.class);
    for (GrpcPeerIdentity wrongPeer :
        new GrpcPeerIdentity[] {
          peer("dev", "world-management-service"), peer("other", "game-session-service")
        }) {
      assertThatThrownBy(
              () ->
                  withControlledPeer(
                      wrongPeer, "a".repeat(64), evidence::requireFixedGameSessionPeer))
          .isInstanceOf(SecurityException.class);
    }
    assertThatThrownBy(
            () ->
                withControlledPeer(
                    peer("dev", "game-session-service"),
                    "d".repeat(64),
                    evidence::requireFixedGameSessionPeer))
        .isInstanceOf(SecurityException.class);
    withControlledPeer(
        peer("dev", "game-session-service"),
        "a".repeat(64),
        () -> {
          evidence.requireFixedGameSessionPeer();
          SessionContext.setContext(
              UUID.randomUUID().toString(), java.util.List.of(), java.util.Map.of());
          try {
            assertThatThrownBy(evidence::requireFixedGameSessionPeer)
                .isInstanceOf(SecurityException.class);
          } finally {
            SessionContext.clear();
          }
        });
    assertThat(evidence.fixtureProvenanceDigest())
        .isEqualTo(evidence.sourceReceipt().fixturePolicySeal().evidenceDigest());
    assertThatThrownBy(evidence::requireFixedGameSessionPeer).isInstanceOf(SecurityException.class);
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static void withControlledPeer(GrpcPeerIdentity peer, String leaf, Runnable action) {
    // Context/certificate doubles exercise only local checks, never a real TLS handshake.
    Context context = Context.ROOT.withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    if (leaf != null) {
      GrpcPeerCertificateEvidence certificate = mock(GrpcPeerCertificateEvidence.class);
      when(certificate.leafSha256()).thenReturn(leaf);
      context = context.withValue(GrpcPeerCertificateEvidence.CONTEXT_KEY, certificate);
    }
    Context previous = context.attach();
    try {
      action.run();
    } finally {
      context.detach(previous);
    }
  }

  @Test
  void bindingDigestNormalizesAcceptedBlankOptionalOverridesToAbsent() {
    RunOwnedWorldLaunchEvidence evidence = RunOwnedWorldLaunchEvidenceTestFixtures.create();

    String absent = evidence.bindingDigest(request(null, null), 1L);
    assertThat(evidence.bindingDigest(request("", ""), 1L)).isEqualTo(absent);
    assertThat(evidence.bindingDigest(request("  ", "  "), 1L)).isEqualTo(absent);
  }

  @Test
  void launchEvidenceBindsStartingAllocationPrivateKeysFromOwnerProjection() {
    RunOwnedWorldLaunchEvidence evidence = RunOwnedWorldLaunchEvidenceTestFixtures.create();

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
        RunOwnedWorldLaunchEvidenceTestFixtures.create().capability().operationId(),
        "launch-descriptor-fixture-11",
        11L,
        patch,
        "{}",
        "genrev-11",
        77L,
        RunOwnedWorldLaunchEvidenceTestFixtures.create()
            .launchDescriptor()
            .publishedReleaseBundleRef(),
        77L,
        remap);
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
