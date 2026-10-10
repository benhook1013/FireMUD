package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.world.WorldCanonicalInitialPlayerLocation;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalCurrentPlayerLocationService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialPlayerLocationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialPlayerLocationService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalPlayerLocationGrpcService;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialPlayerLocationOutcomeRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialPlayerLocationOutcomeResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldCanonicalPlayerLocationServiceGrpc;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Socket mTLS proof for terminal readback with a mocked owner, not database-to-socket proof. */
class WorldCanonicalPlacementOutcomeMtlsTest {
  private static final String NAMESPACE = "test";
  private static final UUID READ_ID = uuid("c0000000-0000-4000-8000-000000000001");
  private static final AtomicLong SERIAL = new AtomicLong(1);
  private static TestPki pki;

  private Server server;
  private WorldCanonicalInitialPlayerLocationService owner;

  @BeforeAll
  static void createTestPki() throws Exception {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    KeyPair caKeys = newKeyPair();
    X500Name caName = new X500Name("CN=World Placement Outcome Test CA, O=FireMUD Test");
    X509Certificate ca =
        issueCertificate(caName, caKeys.getPublic(), caName, caKeys.getPrivate(), true, null);
    pki =
        new TestPki(
            ca,
            issueLeaf(
                caName,
                caKeys.getPrivate(),
                "world-management-service",
                "spiffe://firemud/ns/test/sa/world-management-service"),
            issueLeaf(
                caName,
                caKeys.getPrivate(),
                "entity-management-service",
                "spiffe://firemud/ns/test/sa/entity-management-service"),
            issueLeaf(
                caName,
                caKeys.getPrivate(),
                "game-session-service",
                "spiffe://firemud/ns/test/sa/game-session-service"),
            issueLeaf(
                caName,
                caKeys.getPrivate(),
                "account-service",
                "spiffe://firemud/ns/test/sa/account-service"),
            issueLeaf(
                caName,
                caKeys.getPrivate(),
                "other-namespace-entity",
                "spiffe://firemud/ns/other/sa/entity-management-service"),
            issueLeaf(caName, caKeys.getPrivate(), "no-workload-uri", null));
  }

  @AfterEach
  void stopServer() throws Exception {
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void sameNamespaceEntityCertificateReadsExactAppliedAndConflictEvidence() throws Exception {
    Fixture appliedFixture = fixture("d0000000-0000-4000-8000-000000000001");
    Fixture conflictFixture =
        withOperation(
            fixture("e0000000-0000-4000-8000-000000000001"),
            uuid("f0000000-0000-4000-8000-000000000001"));
    var applied =
        WorldCanonicalInitialPlayerLocation.Result.applied(
            appliedFixture.request(),
            appliedFixture.request().activeLifecycleEvidence().startLocation(),
            appliedFixture.request().activeLifecycleEvidence().runtimeRoomInstanceId());
    var conflict =
        WorldCanonicalInitialPlayerLocation.Result.conflict(
            conflictFixture.request(), "INITIAL_LOCATION_ALREADY_ASSIGNED");
    startServer();
    when(owner.readTerminalOutcome(any()))
        .thenReturn(
            Optional.of(
                new WorldCanonicalInitialPlayerLocationRepository.TerminalReadback(
                    applied, appliedFixture.originalLifecycleBytes())),
            Optional.of(
                new WorldCanonicalInitialPlayerLocationRepository.TerminalReadback(
                    conflict, conflictFixture.originalLifecycleBytes())));

    ManagedChannel channel = channel(pki.entityClient());
    try {
      var stub =
          WorldCanonicalPlayerLocationServiceGrpc.newBlockingStub(channel)
              .withDeadlineAfter(3, TimeUnit.SECONDS);
      ReadCanonicalInitialPlayerLocationOutcomeResponse appliedResponse =
          stub.readCanonicalInitialPlayerLocationOutcome(request(appliedFixture));
      ReadCanonicalInitialPlayerLocationOutcomeResponse conflictResponse =
          stub.readCanonicalInitialPlayerLocationOutcome(request(conflictFixture));

      assertEvidence(appliedResponse, appliedFixture, applied);
      assertEvidence(conflictResponse, conflictFixture, conflict);
    } finally {
      stopChannel(channel);
    }
    verify(owner, times(2)).readTerminalOutcome(any());
  }

  @Test
  void wrongWorkloadsAndWrongNamespaceAreDeniedBeforeOwnerInvocation() throws Exception {
    Fixture fixture = fixture("d0000000-0000-4000-8000-000000000002");
    startServer();
    for (TestCertificate caller :
        List.of(pki.gameSessionClient(), pki.accountClient(), pki.otherNamespaceEntityClient())) {
      ManagedChannel channel = channel(caller);
      try {
        Throwable failure =
            catchFailure(
                () ->
                    WorldCanonicalPlayerLocationServiceGrpc.newBlockingStub(channel)
                        .withDeadlineAfter(3, TimeUnit.SECONDS)
                        .readCanonicalInitialPlayerLocationOutcome(request(fixture)));
        assertThat(failure).isInstanceOf(StatusRuntimeException.class);
        assertThat(Status.fromThrowable(failure).getCode())
            .isEqualTo(Status.Code.PERMISSION_DENIED);
      } finally {
        stopChannel(channel);
      }
    }
    verifyNoInteractions(owner);
  }

  @Test
  void missingOrUnidentifiedClientCertificateIsRejectedAtTheSocketBoundary() throws Exception {
    Fixture fixture = fixture("d0000000-0000-4000-8000-000000000003");
    startServer();
    for (TestCertificate caller : new TestCertificate[] {null, pki.noWorkloadUriClient()}) {
      ManagedChannel channel = channel(caller);
      try {
        Throwable failure =
            catchFailure(
                () ->
                    WorldCanonicalPlayerLocationServiceGrpc.newBlockingStub(channel)
                        .withDeadlineAfter(3, TimeUnit.SECONDS)
                        .readCanonicalInitialPlayerLocationOutcome(request(fixture)));
        assertThat(failure).isInstanceOf(StatusRuntimeException.class);
        assertThat(Status.fromThrowable(failure).getCode())
            .isEqualTo(caller == null ? Status.Code.UNAVAILABLE : Status.Code.PERMISSION_DENIED);
      } finally {
        stopChannel(channel);
      }
    }
    verifyNoInteractions(owner);
  }

  private void startServer() throws Exception {
    owner = mock(WorldCanonicalInitialPlayerLocationService.class);
    var handler =
        new WorldCanonicalPlayerLocationGrpcService(
            owner, mock(WorldCanonicalCurrentPlayerLocationService.class), NAMESPACE);
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            pki.worldServer().privateKey(), pki.worldServer().certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                io.grpc.ServerInterceptors.intercept(handler, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private ManagedChannel channel(TestCertificate certificate) throws Exception {
    var ssl = GrpcSslContexts.forClient().trustManager(pki.caCertificate());
    if (certificate != null) ssl.keyManager(certificate.privateKey(), certificate.certificate());
    return NettyChannelBuilder.forAddress(new InetSocketAddress("127.0.0.1", server.getPort()))
        .sslContext(ssl.build())
        .build();
  }

  private static void stopChannel(ManagedChannel channel) throws InterruptedException {
    channel.shutdownNow();
    assertThat(channel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
  }

  private static ReadCanonicalInitialPlayerLocationOutcomeRequest request(Fixture fixture) {
    return ReadCanonicalInitialPlayerLocationOutcomeRequest.newBuilder()
        .setReadRequestId(READ_ID.toString())
        .setCanonicalPlacementRequestBytes(
            ByteString.copyFrom(fixture.request().canonicalRequestBytes()))
        .setOriginalLifecycleEvidenceBytes(ByteString.copyFrom(fixture.originalLifecycleBytes()))
        .build();
  }

  private static void assertEvidence(
      ReadCanonicalInitialPlayerLocationOutcomeResponse response,
      Fixture fixture,
      WorldCanonicalInitialPlayerLocation.Result expected) {
    assertThat(response.getReadRequestId()).isEqualTo(READ_ID.toString());
    assertThat(response.getPlacementOperationId())
        .isEqualTo(fixture.request().operationId().toString());
    assertThat(response.getPlacementRequestDigest()).isEqualTo(fixture.request().requestDigest());
    assertThat(response.getImmutablePlacementResultBytes().toByteArray())
        .containsExactly(expected.canonicalBytes());
    assertThat(response.getOriginalPlacementLifecycleEvidenceBytes().toByteArray())
        .containsExactly(fixture.originalLifecycleBytes());
  }

  /** Reuses the established complete canonical request fixture for positive socket requests. */
  private static Fixture fixture(String lifecycleReadId) throws Exception {
    WorldCanonicalPlayerLocationGrpcServiceTest.Fixture canonicalFixture =
        WorldCanonicalPlayerLocationGrpcServiceTest.fixture(NAMESPACE, lifecycleReadId);
    return new Fixture(canonicalFixture.request(), canonicalFixture.originalLifecycleBytes());
  }

  private static Fixture withOperation(Fixture fixture, UUID operationId) {
    var request = fixture.request();
    return new Fixture(
        new WorldCanonicalInitialPlayerLocation.Request(
            operationId,
            request.canonicalTenantId(),
            request.realmId(),
            request.worldSlug(),
            request.canonicalGameInstanceId(),
            request.playableStateNamespaceId(),
            request.playableStateScope(),
            request.canonicalAccountId(),
            request.characterId(),
            request.entityAssignmentOperationId(),
            request.entityAssignmentDigest(),
            request.initialAdmissionHoldId(),
            request.initialAdmissionHoldFence(),
            request.initialAdmissionRequestId(),
            request.initialAdmissionRequestDigest(),
            request.catalogRevision(),
            request.initialAdmissionOwnerProofId(),
            request.initialAdmissionOwnerProofDigest(),
            request.pointerAuditId(),
            request.pointerVersion(),
            request.initialAdmissionOrigin(),
            request.activeLifecycleEvidence()),
        fixture.originalLifecycleBytes());
  }

  private static Throwable catchFailure(Runnable action) {
    try {
      action.run();
      return null;
    } catch (Throwable failure) {
      return failure;
    }
  }

  private static TestCertificate issueLeaf(
      X500Name caName, PrivateKey caKey, String commonName, String workloadUri) throws Exception {
    KeyPair keys = newKeyPair();
    GeneralName[] names =
        workloadUri == null
            ? new GeneralName[] {
              new GeneralName(GeneralName.dNSName, "localhost"),
              new GeneralName(GeneralName.iPAddress, "127.0.0.1")
            }
            : new GeneralName[] {
              new GeneralName(GeneralName.uniformResourceIdentifier, workloadUri),
              new GeneralName(GeneralName.dNSName, "localhost"),
              new GeneralName(GeneralName.iPAddress, "127.0.0.1")
            };
    return new TestCertificate(
        keys.getPrivate(),
        issueCertificate(
            new X500Name("CN=" + commonName + ", O=FireMUD Test"),
            keys.getPublic(),
            caName,
            caKey,
            false,
            new GeneralNames(names)));
  }

  private static X509Certificate issueCertificate(
      X500Name subject,
      java.security.PublicKey publicKey,
      X500Name issuer,
      PrivateKey issuerKey,
      boolean ca,
      GeneralNames names)
      throws Exception {
    Instant now = Instant.now().minusSeconds(60);
    var builder =
        new JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(SERIAL.getAndIncrement()),
            Date.from(now),
            Date.from(now.plusSeconds(14L * 24 * 60 * 60)),
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
      builder.addExtension(Extension.subjectAlternativeName, false, names);
    }
    return new JcaX509CertificateConverter()
        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
        .getCertificate(
            builder.build(
                new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(issuerKey)));
  }

  private static KeyPair newKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record Fixture(
      WorldCanonicalInitialPlayerLocation.Request request, byte[] originalLifecycleBytes) {}

  private record TestCertificate(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestCertificate worldServer,
      TestCertificate entityClient,
      TestCertificate gameSessionClient,
      TestCertificate accountClient,
      TestCertificate otherNamespaceEntityClient,
      TestCertificate noWorkloadUriClient) {}
}
