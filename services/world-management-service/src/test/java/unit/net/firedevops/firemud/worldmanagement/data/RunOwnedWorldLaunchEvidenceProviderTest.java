package net.firedevops.firemud.worldmanagement.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationClient;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerCertificateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
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
import net.firedevops.firemud.worldmanagement.client.GameDesignClient.LaunchDescriptorReadback;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
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
              CLAIM_DIGEST, "fixture-run", "firemud-fixture-run", "a".repeat(64), "c".repeat(64))
          .toString();
  private static final String RELEASE_REF = "8ce42642-53ae-4d07-aab8-37c43ce66e0c";

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

  static {
    Security.addProvider(new BouncyCastleProvider());
  }

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
  void launchEvidenceConstructorRejectsMismatchedGameSessionFixtureAssociation() {
    assertThatThrownBy(() -> fixtureEvidence(true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("RUN_OWNED_WORLD_GAME_SESSION_FIXTURE_ASSOCIATION_MISMATCH:");
  }

  @Test
  void fixtureOwnerWireResultRejectsNonStartingAllocationsBeforeWorldConsumption() {
    var original = fixtureEvidence().gameSessionAssociation();
    var response =
        net.firedevops.firemud.common.gamesession
            .CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toFixtureResponse(original);
    for (var status : java.util.List.of("RUNNING", "STOPPED")) {
      var changed = response.toBuilder().setCurrentGameInstanceStatus(status).build();
      assertThatThrownBy(
              () ->
                  net.firedevops.firemud.common.gamesession
                      .CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromFixtureResponse(
                      original.request(), changed))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Fixture launch association read requires the current STARTING snapshot");
    }
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

  @Test
  void fixtureOwnerReadFailureIsContentFreeAndLogsTheReadStage(CapturedOutput output)
      throws Exception {
    ProviderFixture fixture = providerFixture();
    GameDesignClient gameDesignClient = mockDescriptorReader(fixture.evidence());
    CanonicalGameInstanceLaunchAssociationClient fixtureClient = mock();
    when(fixtureClient.readFixture(any(), anyString()))
        .thenThrow(new IllegalStateException("secret-owner-response"));
    RunOwnedWorldLaunchEvidenceProvider provider =
        provider(
            fixture, gameDesignClient, fixtureClient, fixture.endpoints(), fixture.tlsProperties());

    try (PeerContext ignored = fixture.attachGameSessionPeer()) {
      assertThatThrownBy(provider::requireAuthenticatedEvidence)
          .isInstanceOf(
              RunOwnedWorldLaunchEvidenceProvider.InvalidRunOwnedWorldLaunchEvidenceException.class)
          .hasMessage("Run-owned World launch evidence is invalid");
    } finally {
      provider.closeGameSessionFixtureClient();
    }

    verify(fixtureClient)
        .readFixture(any(), org.mockito.ArgumentMatchers.eq(fixture.gameSessionLeafSha256()));
    verify(fixtureClient).close();
    assertThat(output)
        .contains(
            "Run-owned World launch evidence verification rejected; stage=GAME_SESSION_FIXTURE_READ")
        .doesNotContain("secret-owner-response", "IllegalStateException", "Caused by:");
  }

  @Test
  void validFixtureOwnerReadReachesCompareAndReturnsStartingEvidence(CapturedOutput output)
      throws Exception {
    ProviderFixture fixture = providerFixture();
    GameDesignClient gameDesignClient = mockDescriptorReader(fixture.evidence());
    CanonicalGameInstanceLaunchAssociationClient fixtureClient = mock();
    when(fixtureClient.readFixture(any(), anyString()))
        .thenAnswer(invocation -> fixture.association(invocation.getArgument(0)));
    RunOwnedWorldLaunchEvidenceProvider provider =
        provider(
            fixture, gameDesignClient, fixtureClient, fixture.endpoints(), fixture.tlsProperties());

    RunOwnedWorldLaunchEvidence result;
    try (PeerContext ignored = fixture.attachGameSessionPeer()) {
      result = provider.requireAuthenticatedEvidence();
    } finally {
      provider.closeGameSessionFixtureClient();
    }

    assertThat(result.gameSessionAssociation().currentGameInstanceStatus())
        .isEqualTo(
            CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus.STARTING);
    verify(fixtureClient)
        .readFixture(any(), org.mockito.ArgumentMatchers.eq(fixture.gameSessionLeafSha256()));
    verify(fixtureClient).close();
    assertThat(output)
        .doesNotContain("Run-owned World launch evidence verification rejected", "Caused by:");
  }

  // The controlled owner-client return is valid for its alternate policy; the real common client
  // rejects its different request digest before this provider comparison boundary.
  @Test
  void validAlternateFixtureAssociationIsRejectedAtCompareStage(CapturedOutput output)
      throws Exception {
    ProviderFixture fixture = providerFixture();
    GameDesignClient gameDesignClient = mockDescriptorReader(fixture.evidence());
    CanonicalGameInstanceLaunchAssociationClient fixtureClient = mock();
    RunOwnedWorldLaunchEvidence alternateFixture =
        fixtureEvidence(
            false,
            fixture.gameSessionLeafSha256(),
            fingerprint(fixture.pki().worldManagementCertificate()),
            fingerprint(fixture.pki().caCertificate()),
            UUID.fromString("ed5d1541-a33e-4f8b-9b54-38c27d76ba6e"));
    when(fixtureClient.readFixture(any(), anyString()))
        .thenReturn(alternateFixture.gameSessionAssociation());
    RunOwnedWorldLaunchEvidenceProvider provider =
        provider(
            fixture, gameDesignClient, fixtureClient, fixture.endpoints(), fixture.tlsProperties());

    try (PeerContext ignored = fixture.attachGameSessionPeer()) {
      assertThatThrownBy(provider::requireAuthenticatedEvidence)
          .isInstanceOf(
              RunOwnedWorldLaunchEvidenceProvider.InvalidRunOwnedWorldLaunchEvidenceException.class)
          .hasMessage("Run-owned World launch evidence is invalid");
    } finally {
      provider.closeGameSessionFixtureClient();
    }

    verify(fixtureClient)
        .readFixture(any(), org.mockito.ArgumentMatchers.eq(fixture.gameSessionLeafSha256()));
    verify(fixtureClient).close();
    assertThat(output)
        .contains(
            "Run-owned World launch evidence verification rejected; stage=GAME_SESSION_FIXTURE_COMPARE")
        .doesNotContain(
            "fixture-run", "firemud-fixture-run", "IllegalArgumentException", "Caused by:");
  }

  @Test
  void fixtureClientInitializationFailureClosesPartialClient(CapturedOutput output)
      throws Exception {
    ProviderFixture fixture = providerFixture();
    GameDesignClient gameDesignClient = mockDescriptorReader(fixture.evidence());
    CanonicalGameInstanceLaunchAssociationClient fixtureClient = mock();
    doThrow(new IOException("private-init-detail")).when(fixtureClient).init();
    RunOwnedWorldLaunchEvidenceProvider provider =
        new RunOwnedWorldLaunchEvidenceProvider(
            true,
            fixture.capabilityPath().toString(),
            fixture.receiptPath().toString(),
            "fixture-run",
            "firemud-fixture-run",
            fixture.tlsProperties(),
            gameDesignClient,
            fixture.endpoints(),
            new GrpcChannelFactory(),
            "dev",
            (endpoints, tls, channelFactory, namespace) -> fixtureClient);

    try (PeerContext ignored = fixture.attachGameSessionPeer()) {
      assertThatThrownBy(provider::requireAuthenticatedEvidence)
          .isInstanceOf(
              RunOwnedWorldLaunchEvidenceProvider.InvalidRunOwnedWorldLaunchEvidenceException.class)
          .hasMessage("Run-owned World launch evidence is invalid");
    }

    verify(fixtureClient).init();
    verify(fixtureClient).close();
    assertThat(output)
        .contains(
            "Run-owned World launch evidence verification rejected; stage=GAME_SESSION_FIXTURE_READ")
        .doesNotContain("private-init-detail", "IOException", "Caused by:");
  }

  @Test
  void fixtureClientMissingConfigurationFailsAtReadStageWithoutCreatingClient(CapturedOutput output)
      throws Exception {
    ProviderFixture fixture = providerFixture();
    GameDesignClient gameDesignClient = mockDescriptorReader(fixture.evidence());
    RunOwnedWorldLaunchEvidenceProvider.GameSessionFixtureClientFactory factory = mock();
    RunOwnedWorldLaunchEvidenceProvider provider =
        provider(fixture, gameDesignClient, null, null, fixture.tlsProperties(), factory);

    try (PeerContext ignored = fixture.attachGameSessionPeer()) {
      assertThatThrownBy(provider::requireAuthenticatedEvidence)
          .isInstanceOf(
              RunOwnedWorldLaunchEvidenceProvider.InvalidRunOwnedWorldLaunchEvidenceException.class)
          .hasMessage("Run-owned World launch evidence is invalid");
    }

    verifyNoInteractions(factory);
    assertThat(output)
        .contains(
            "Run-owned World launch evidence verification rejected; stage=GAME_SESSION_FIXTURE_READ")
        .doesNotContain("fixture-run", "firemud-fixture-run");
  }

  @Test
  void blankWorkloadNamespaceFailsBeforeCreatingFixtureClient() throws Exception {
    ProviderFixture fixture = providerFixture();
    for (String namespace : new String[] {null, "", "   "}) {
      GameDesignClient gameDesignClient = mockDescriptorReader(fixture.evidence());
      RunOwnedWorldLaunchEvidenceProvider.GameSessionFixtureClientFactory factory = mock();
      RunOwnedWorldLaunchEvidenceProvider provider =
          provider(
              fixture,
              gameDesignClient,
              null,
              fixture.endpoints(),
              fixture.tlsProperties(),
              factory,
              namespace);

      try (PeerContext ignored = fixture.attachGameSessionPeer()) {
        assertThatThrownBy(provider::requireAuthenticatedEvidence)
            .isInstanceOf(
                RunOwnedWorldLaunchEvidenceProvider.InvalidRunOwnedWorldLaunchEvidenceException
                    .class)
            .hasMessage("Run-owned World launch evidence is invalid");
      }

      verifyNoInteractions(factory);
    }
  }

  @Test
  void configuredWorkloadNamespaceIsPassedToFixtureClientFactory() throws Exception {
    ProviderFixture fixture = providerFixture();
    GameDesignClient gameDesignClient = mockDescriptorReader(fixture.evidence());
    CanonicalGameInstanceLaunchAssociationClient fixtureClient = mock();
    when(fixtureClient.readFixture(any(), anyString()))
        .thenAnswer(invocation -> fixture.association(invocation.getArgument(0)));
    RunOwnedWorldLaunchEvidenceProvider.GameSessionFixtureClientFactory factory = mock();
    when(factory.create(any(), any(), any(), anyString())).thenReturn(fixtureClient);
    RunOwnedWorldLaunchEvidenceProvider provider =
        provider(
            fixture,
            gameDesignClient,
            fixtureClient,
            fixture.endpoints(),
            fixture.tlsProperties(),
            factory,
            "dev");

    try (PeerContext ignored = fixture.attachGameSessionPeer()) {
      provider.requireAuthenticatedEvidence();
    } finally {
      provider.closeGameSessionFixtureClient();
    }

    verify(factory).create(any(), any(), any(), eq("dev"));
  }

  private static void assertFileRejectionStoppedBeforeWireParse(CapturedOutput output) {
    assertThat(output)
        .contains("Run-owned World launch evidence verification rejected; stage=CAPABILITY_FILE")
        .doesNotContain("stage=WIRE_PARSE");
  }

  private RunOwnedWorldLaunchEvidenceProvider provider(
      ProviderFixture fixture,
      GameDesignClient gameDesignClient,
      CanonicalGameInstanceLaunchAssociationClient fixtureClient,
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties) {
    return provider(
        fixture,
        gameDesignClient,
        fixtureClient,
        endpoints,
        tlsProperties,
        (ignoredEndpoints, ignoredTls, ignoredChannelFactory, ignoredNamespace) -> fixtureClient);
  }

  private RunOwnedWorldLaunchEvidenceProvider provider(
      ProviderFixture fixture,
      GameDesignClient gameDesignClient,
      CanonicalGameInstanceLaunchAssociationClient fixtureClient,
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      RunOwnedWorldLaunchEvidenceProvider.GameSessionFixtureClientFactory factory) {
    return provider(
        fixture, gameDesignClient, fixtureClient, endpoints, tlsProperties, factory, "dev");
  }

  private RunOwnedWorldLaunchEvidenceProvider provider(
      ProviderFixture fixture,
      GameDesignClient gameDesignClient,
      CanonicalGameInstanceLaunchAssociationClient fixtureClient,
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProperties,
      RunOwnedWorldLaunchEvidenceProvider.GameSessionFixtureClientFactory factory,
      String namespace) {
    return new RunOwnedWorldLaunchEvidenceProvider(
        true,
        fixture.capabilityPath().toString(),
        fixture.receiptPath().toString(),
        "fixture-run",
        "firemud-fixture-run",
        tlsProperties,
        gameDesignClient,
        endpoints,
        new GrpcChannelFactory(),
        namespace,
        factory);
  }

  private ProviderFixture providerFixture() throws Exception {
    Path directory =
        Files.createDirectories(temporaryDirectory.resolve("provider-" + UUID.randomUUID()));
    TestPki pki = TestPki.create();
    String gameSessionLeaf = fingerprint(pki.gameSessionCertificate());
    String worldManagementLeaf = fingerprint(pki.worldManagementCertificate());
    String caFingerprint = fingerprint(pki.caCertificate());
    RunOwnedWorldLaunchEvidence evidence =
        fixtureEvidence(false, gameSessionLeaf, worldManagementLeaf, caFingerprint);
    Path capability = directory.resolve("capability.json");
    Path receipt = directory.resolve("receipt.json");
    Files.writeString(capability, capabilityJson(evidence.capability()), StandardCharsets.UTF_8);
    Files.writeString(receipt, receiptJson(evidence.sourceReceipt()), StandardCharsets.UTF_8);
    Set<PosixFilePermission> immutablePermissions =
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ);
    Files.setPosixFilePermissions(capability, immutablePermissions);
    Files.setPosixFilePermissions(receipt, immutablePermissions);
    Path worldCertificate = directory.resolve("world-management.crt");
    Path caCertificate = directory.resolve("ca.crt");
    Files.writeString(
        worldCertificate, pem(pki.worldManagementCertificate()), StandardCharsets.UTF_8);
    Files.writeString(caCertificate, pem(pki.caCertificate()), StandardCharsets.UTF_8);
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(worldCertificate.toString());
    tls.setCaCert(caCertificate.toString());
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameSessionService("localhost:6565");
    return new ProviderFixture(evidence, capability, receipt, pki, tls, endpoints, gameSessionLeaf);
  }

  private static GameDesignClient mockDescriptorReader(RunOwnedWorldLaunchEvidence evidence) {
    GameDesignClient client = mock(GameDesignClient.class);
    var message =
        net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec
            .toLaunchDescriptorMessage(evidence.launchDescriptor());
    when(client.getLaunchDescriptor(any()))
        .thenReturn(new LaunchDescriptorReadback(message, evidence.launchDescriptor()));
    return client;
  }

  private static String capabilityJson(
      RunOwnedInitialAdmissionFixtureWireCodec.Capability capability) {
    return """
        {"schema":"%s","runId":"%s","composeProjectName":"%s","operationId":"%s","tenantId":%d,"gameTemplateId":%d,"ownerAccountId":%d,"worldSlug":"%s","worldDisplayName":"%s","realmSlug":"%s","realmDisplayName":"%s","visible":%s,"publicProductionRealm":%s,"requiresCharacterSelection":%s,"stateScope":"%s","characterCreationPolicy":"%s","gameSessionLeafSha256":"%s","gameSessionUriSan":"%s","caCertificateSha256":"%s","worldManagementUriSan":"%s","worldManagementLeafSha256":"%s","claimDigest":"%s"}%n\
        """
        .formatted(
            capability.schema(),
            capability.runId(),
            capability.composeProjectName(),
            capability.operationId(),
            capability.tenantId(),
            capability.gameTemplateId(),
            capability.ownerAccountId(),
            capability.worldSlug(),
            capability.worldDisplayName(),
            capability.realmSlug(),
            capability.realmDisplayName(),
            capability.visible(),
            capability.publicProductionRealm(),
            capability.requiresCharacterSelection(),
            capability.stateScope(),
            capability.characterCreationPolicy(),
            capability.gameSessionLeafSha256(),
            capability.gameSessionUriSan(),
            capability.caCertificateSha256(),
            capability.worldManagementUriSan(),
            capability.worldManagementLeafSha256(),
            capability.claimDigest());
  }

  private static String receiptJson(
      RunOwnedInitialAdmissionFixtureWireCodec.SourceReceipt receipt) {
    var source = receipt.sourceEvidence();
    var creation = receipt.creationEvidence();
    var launch = receipt.launchReadEvidence();
    var seal = receipt.fixturePolicySeal();
    return """
        {"schema":"%s","runId":"%s","composeProjectName":"%s","fixtureOperationId":"%s","ownerService":"%s","gameSessionUriSan":"%s","gameSessionLeafSha256":"%s","caCertificateSha256":"%s","worldManagementUriSan":"%s","worldManagementLeafSha256":"%s","sourceEvidence":{"schemaVersion":%d,"targetNamespace":"%s","registrationRequestId":"%s","operationId":"%s","requestDigest":"%s","canonicalTenantId":"%s","tenantSlug":"%s","worldSlug":"%s","worldDisplayName":"%s","sourceGameRowId":%d,"sourceGameTenantKey":"%s","provenanceKind":"%s","evidenceDigest":"%s"},"launchReadEvidence":{"schema":"%s","gameTemplateId":%d,"publishedReleaseBundleBase64":"%s","versionAssetArtifactStateBase64":"%s","versionStateBase64":"%s","launchDescriptorBase64":"%s"},"creationEvidence":{"schemaVersion":%d,"targetNamespace":"%s","creationRequestId":"%s","operationId":"%s","requestDigest":"%s","canonicalTenantId":"%s","sourceGameRowId":%d,"sourceGameTenantKey":"%s","provenanceKind":"%s","evidenceDigest":"%s"},"fixturePolicySeal":{"schema":"%s","ownerService":"%s","operationId":"%s","requestDigest":"%s","evidenceDigest":"%s"}}%n\
        """
        .formatted(
            receipt.schema(),
            receipt.runId(),
            receipt.composeProjectName(),
            receipt.fixtureOperationId(),
            receipt.ownerService(),
            receipt.gameSessionUriSan(),
            receipt.gameSessionLeafSha256(),
            receipt.caCertificateSha256(),
            receipt.worldManagementUriSan(),
            receipt.worldManagementLeafSha256(),
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
            launch.schema(),
            launch.gameTemplateId(),
            base64(launch.publishedReleaseBundle().toByteArray()),
            base64(launch.versionAssetArtifactState().toByteArray()),
            base64(launch.versionState().toByteArray()),
            base64(launch.launchDescriptor().toByteArray()),
            creation.schemaVersion(),
            creation.targetNamespace(),
            creation.creationRequestId(),
            creation.operationId(),
            creation.requestDigest(),
            creation.canonicalTenantId(),
            creation.sourceGameRowId(),
            creation.sourceGameTenantKey(),
            creation.provenanceKind(),
            creation.evidenceDigest(),
            seal.schema(),
            seal.ownerService(),
            seal.operationId(),
            seal.requestDigest(),
            seal.evidenceDigest());
  }

  private static String base64(byte[] value) {
    return Base64.getEncoder().encodeToString(value);
  }

  private static String pem(X509Certificate certificate) throws Exception {
    return "-----BEGIN CERTIFICATE-----\n"
        + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(certificate.getEncoded())
        + "\n-----END CERTIFICATE-----\n";
  }

  private static String fingerprint(X509Certificate certificate) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
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
    return fixtureEvidence(false);
  }

  private static RunOwnedWorldLaunchEvidence fixtureEvidence(boolean mismatchedSeal) {
    return fixtureEvidence(mismatchedSeal, "a".repeat(64), "b".repeat(64), "c".repeat(64));
  }

  private static RunOwnedWorldLaunchEvidence fixtureEvidence(
      boolean mismatchedSeal,
      String gameSessionLeafSha256,
      String worldManagementLeafSha256,
      String caCertificateSha256) {
    return fixtureEvidence(
        mismatchedSeal,
        gameSessionLeafSha256,
        worldManagementLeafSha256,
        caCertificateSha256,
        UUID.fromString("ed5d1541-a33e-4f8b-9b54-38c27d76ba6d"));
  }

  private static RunOwnedWorldLaunchEvidence fixtureEvidence(
      boolean mismatchedSeal,
      String gameSessionLeafSha256,
      String worldManagementLeafSha256,
      String caCertificateSha256,
      UUID canonicalTenantId) {
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
            operationId,
            42L,
            17L,
            1L,
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
    return new RunOwnedWorldLaunchEvidence(
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

  private record ProviderFixture(
      RunOwnedWorldLaunchEvidence evidence,
      Path capabilityPath,
      Path receiptPath,
      TestPki pki,
      CommonGrpcClientProperties tlsProperties,
      ServiceEndpointsProperties endpoints,
      String gameSessionLeafSha256) {
    private CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult association(
        CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureRequest request) {
      var original = evidence.gameSessionAssociation();
      return new CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult(
          request,
          original.gameSessionTenantId(),
          original.gameInstanceId(),
          original.canonicalTenantId(),
          original.gameInstanceUuid(),
          original.canonicalVersionId(),
          original.canonicalRealmId(),
          original.playableStateNamespaceId(),
          original.catalogRevision(),
          original.currentGameInstanceStatus(),
          original.currentRowVersion(),
          original.fixturePolicyRequestBytes(),
          original.fixturePolicySeal());
    }

    private PeerContext attachGameSessionPeer() throws Exception {
      Context context =
          Context.current()
              .withValue(
                  GrpcPeerIdentity.CONTEXT_KEY,
                  new GrpcPeerIdentity(
                      RunOwnedInitialAdmissionFixtureWireCodec.GAME_SESSION_URI_SAN,
                      "dev",
                      "game-session-service"))
              .withValue(
                  GrpcPeerCertificateEvidence.CONTEXT_KEY,
                  GrpcPeerCertificateEvidence.fromCertificate(pki.gameSessionCertificate())
                      .orElseThrow());
      return new PeerContext(context.attach());
    }
  }

  private record PeerContext(io.grpc.Context previous) implements AutoCloseable {
    @Override
    public void close() {
      Context.current().detach(previous);
    }
  }

  private record TestPki(
      X509Certificate caCertificate,
      X509Certificate worldManagementCertificate,
      X509Certificate gameSessionCertificate) {
    private static TestPki create() throws Exception {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KeyPair caKey = generator.generateKeyPair();
      X500Name caName = new X500Name("CN=World provider test CA");
      X509Certificate ca = issueCertificate(caName, caKey.getPublic(), caName, caKey, true, null);
      X509Certificate world =
          issueLeaf(
              caName,
              caKey,
              generator,
              "world-management-service",
              RunOwnedInitialAdmissionFixtureWireCodec.WORLD_MANAGEMENT_URI_SAN);
      X509Certificate gameSession =
          issueLeaf(
              caName,
              caKey,
              generator,
              "game-session-service",
              RunOwnedInitialAdmissionFixtureWireCodec.GAME_SESSION_URI_SAN);
      return new TestPki(ca, world, gameSession);
    }

    private static X509Certificate issueLeaf(
        X500Name caName,
        KeyPair caKey,
        KeyPairGenerator generator,
        String service,
        String workloadUri)
        throws Exception {
      KeyPair leafKey = generator.generateKeyPair();
      GeneralNames names =
          new GeneralNames(new GeneralName(GeneralName.uniformResourceIdentifier, workloadUri));
      return issueCertificate(
          new X500Name("CN=" + service), leafKey.getPublic(), caName, caKey, false, names);
    }

    private static X509Certificate issueCertificate(
        X500Name subject,
        java.security.PublicKey publicKey,
        X500Name issuer,
        KeyPair issuerKey,
        boolean ca,
        GeneralNames subjectAltNames)
        throws Exception {
      Instant now = Instant.now().minusSeconds(60);
      JcaX509v3CertificateBuilder builder =
          new JcaX509v3CertificateBuilder(
              issuer,
              BigInteger.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits()) + 1L),
              Date.from(now),
              Date.from(now.plusSeconds(86400L)),
              subject,
              publicKey);
      builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
      if (ca) {
        builder.addExtension(
            Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
      } else {
        builder.addExtension(
            Extension.keyUsage,
            true,
            new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        builder.addExtension(
            Extension.extendedKeyUsage,
            false,
            new ExtendedKeyUsage(
                new KeyPurposeId[] {KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth}));
        builder.addExtension(Extension.subjectAlternativeName, false, subjectAltNames);
      }
      return new JcaX509CertificateConverter()
          .setProvider(BouncyCastleProvider.PROVIDER_NAME)
          .getCertificate(
              builder.build(
                  new JcaContentSignerBuilder("SHA256withRSA")
                      .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                      .build(issuerKey.getPrivate())));
    }
  }
}
