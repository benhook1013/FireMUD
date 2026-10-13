package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Timestamp;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOrigin;
import net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOwnerProofOutcome;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetCanonicalInitialAdmissionOwnerProofRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalInitialAdmissionOwnerProofResponse;
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
import org.junit.jupiter.api.io.TempDir;

/** Physical loopback mTLS proof against a transport-only Game Session owner-result double. */
class GameSessionCanonicalInitialAdmissionOwnerClientMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String WORLD_URI = "spiffe://firemud/ns/test/sa/world-management-service";
  private static final String GAME_SESSION_URI = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String OTHER_NAMESPACE_URI =
      "spiffe://firemud/ns/other-test/sa/game-session-service";
  private static final String WRONG_WORKLOAD_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final AtomicInteger CERTIFICATE_SERIAL = new AtomicInteger(1);

  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYABLE_NAMESPACE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID GAME_INSTANCE = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID VERSION = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID HOLD_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID HOLD_FENCE = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  @TempDir static Path tempDirectory;
  private static TestPki pki;
  private Server server;
  private GameSessionCanonicalInitialAdmissionOwnerClient client;
  private AtomicInteger metadataCalls;
  private AtomicInteger bodyCalls;
  private ResponseMode responseMode;

  @BeforeAll
  static void createTrustedCertificates() throws Exception {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    KeyPair caKeys = newRsaKeyPair();
    X500Name caName = new X500Name("CN=Canonical Initial Admission Test CA, O=FireMUD Test");
    X509Certificate ca =
        issueCertificate(caName, caKeys.getPublic(), caName, caKeys.getPrivate(), true, null);
    pki =
        new TestPki(
            ca,
            issueLeaf(caName, caKeys.getPrivate(), GAME_SESSION_URI),
            issueLeaf(caName, caKeys.getPrivate(), WRONG_WORKLOAD_URI),
            issueLeaf(caName, caKeys.getPrivate(), OTHER_NAMESPACE_URI),
            issueLeaf(caName, caKeys.getPrivate(), null),
            issueLeaf(caName, caKeys.getPrivate(), WORLD_URI));
  }

  @AfterEach
  void stopTransport() throws Exception {
    if (client != null) {
      client.close();
      client = null;
    }
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      server = null;
    }
  }

  @Test
  void exactGameSessionServerReturnsCompleteCommittedProofForBothAcceptedOrigins()
      throws Exception {
    for (InitialAdmissionOrigin origin : InitialAdmissionOrigin.values()) {
      startServer(pki.gameSessionServer(), ResponseMode.COMMITTED);
      client = newClient(server);
      client.init();
      HoldIdentity identity = identity(origin);

      var held =
          client.verifyAndHold(
              identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
      try {
        held.requireHeld();
        assertThat(held.proof().holdIdentity()).isEqualTo(identity);
        assertThat(held.proof().outcome())
            .isEqualTo(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
        assertThat(held.proof().committedPointerVersion())
            .isEqualTo(origin == InitialAdmissionOrigin.NO_PRIOR_POINTER ? 1L : 4L);
        assertThat(held.proof().auditEventId()).isEqualTo(42L);
      } finally {
        held.close();
      }
      assertThatThrownBy(held::requireHeld)
          .isInstanceOf(
              WorldCanonicalInitialAdmissionHoldFinalizationService.FinalizationDeniedException
                  .class);
      assertThat(metadataCalls).hasValue(1);
      assertThat(bodyCalls).hasValue(1);
      stopTransport();
    }
  }

  @Test
  void positiveDurableAbortIsReturnedAsTerminalOwnerEvidence() throws Exception {
    startServer(pki.gameSessionServer(), ResponseMode.ABORTED);
    client = newClient(server);
    client.init();
    HoldIdentity identity = identity(InitialAdmissionOrigin.EXPECT_CLOSED);

    var held =
        client.verifyAndHold(
            identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED);
    try {
      held.requireHeld();
      assertThat(held.proof().holdIdentity()).isEqualTo(identity);
      assertThat(held.proof().outcome())
          .isEqualTo(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED);
      assertThat(held.proof().positiveDurableAbort()).isTrue();
      assertThat(held.proof().committedPointerVersion()).isNull();
      assertThat(held.proof().auditEventId()).isNull();
    } finally {
      held.close();
    }
    assertThatThrownBy(held::requireHeld)
        .isInstanceOf(
            WorldCanonicalInitialAdmissionHoldFinalizationService.FinalizationDeniedException
                .class);
    assertThat(metadataCalls).hasValue(1);
    assertThat(bodyCalls).hasValue(1);
  }

  @Test
  void observedReadAcceptsExactPendingWithoutTerminalFieldsFromTheAuthenticatedPeer()
      throws Exception {
    startServer(pki.gameSessionServer(), ResponseMode.PENDING);
    client = newClient(server);
    client.init();
    HoldIdentity identity = identity(InitialAdmissionOrigin.NO_PRIOR_POINTER);

    var held = client.verifyAndHoldObserved(identity);
    try {
      held.requireHeld();
      assertThat(held.proof().holdIdentity()).isEqualTo(identity);
      assertThat(held.proof().outcome())
          .isEqualTo(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING);
      assertThat(held.proof().terminalAt()).isNull();
      assertThat(held.proof().proofDigest()).isNull();
    } finally {
      held.close();
    }
    assertThat(metadataCalls).hasValue(1);
    assertThat(bodyCalls).hasValue(1);
  }

  @Test
  void changedResponseIdentityAndChangedOutcomeAreRejected() throws Exception {
    startServer(pki.gameSessionServer(), ResponseMode.SUBSTITUTED_REALM);
    client = newClient(server);
    client.init();
    HoldIdentity identity = identity(InitialAdmissionOrigin.NO_PRIOR_POINTER);

    assertThatThrownBy(
            () ->
                client.verifyAndHold(
                    identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED))
        .isInstanceOf(IllegalStateException.class);
    stopTransport();

    startServer(pki.gameSessionServer(), ResponseMode.ABORTED);
    client = newClient(server);
    client.init();
    assertThatThrownBy(
            () ->
                client.verifyAndHold(
                    identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void sameCaWrongServiceNamespaceAndMissingUriServerCertificatesAreRejectedBeforeRequest()
      throws Exception {
    for (TestIdentity identity :
        List.of(
            pki.wrongWorkloadServer(),
            pki.otherNamespaceServer(),
            pki.missingWorkloadUriServer())) {
      startServer(identity, ResponseMode.COMMITTED);
      client = newClient(server);
      client.init();
      HoldIdentity request = identity(InitialAdmissionOrigin.NO_PRIOR_POINTER);

      assertThatThrownBy(
              () ->
                  client.verifyAndHold(
                      request, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.UNAUTHENTICATED));
      assertThat(metadataCalls).hasValue(0);
      assertThat(bodyCalls).hasValue(0);
      stopTransport();
    }
  }

  @Test
  void closedClientHandleCannotVerifyAnotherProof() throws Exception {
    startServer(pki.gameSessionServer(), ResponseMode.COMMITTED);
    client = newClient(server);
    var identity = identity(InitialAdmissionOrigin.NO_PRIOR_POINTER);

    assertThatThrownBy(
            () ->
                client.verifyAndHold(
                    identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED))
        .isInstanceOf(IllegalStateException.class);
    client.init();
    client.close();
    assertThatThrownBy(
            () ->
                client.verifyAndHold(
                    identity, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED))
        .isInstanceOf(IllegalStateException.class);
    assertThat(metadataCalls).hasValue(0);
    assertThat(bodyCalls).hasValue(0);
  }

  private enum ResponseMode {
    PENDING,
    COMMITTED,
    ABORTED,
    SUBSTITUTED_REALM
  }

  private void startServer(TestIdentity identity, ResponseMode mode) throws Exception {
    responseMode = mode;
    metadataCalls = new AtomicInteger();
    bodyCalls = new AtomicInteger();
    var service =
        new GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceImplBase() {
          @Override
          public void getCanonicalInitialAdmissionOwnerProof(
              GetCanonicalInitialAdmissionOwnerProofRequest request,
              StreamObserver<GetCanonicalInitialAdmissionOwnerProofResponse> observer) {
            bodyCalls.incrementAndGet();
            assertThat(GrpcPeerIdentity.current().uri()).isEqualTo(WORLD_URI);
            GetCanonicalInitialAdmissionOwnerProofResponse response =
                responseFor(request, responseMode);
            observer.onNext(response);
            observer.onCompleted();
          }
        };
    ServerInterceptor countMetadata =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call,
              Metadata metadata,
              ServerCallHandler<ReqT, RespT> next) {
            metadataCalls.incrementAndGet();
            return next.startCall(call, metadata);
          }
        };
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    service, countMetadata, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private GameSessionCanonicalInitialAdmissionOwnerClient newClient(Server target)
      throws IOException, CertificateEncodingException {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameSessionService("localhost:" + target.getPort());
    return new GameSessionCanonicalInitialAdmissionOwnerClient(
        endpoints, pki.clientProperties(tempDirectory), new GrpcChannelFactory(), NAMESPACE);
  }

  private static GetCanonicalInitialAdmissionOwnerProofResponse responseFor(
      GetCanonicalInitialAdmissionOwnerProofRequest request, ResponseMode mode) {
    var builder =
        GetCanonicalInitialAdmissionOwnerProofResponse.newBuilder()
            .setTargetNamespace(request.getTargetNamespace())
            .setInitialAdmissionRequestId(request.getInitialAdmissionRequestId())
            .setInitialAdmissionRequestDigest(request.getInitialAdmissionRequestDigest())
            .setCanonicalTenantId(request.getCanonicalTenantId())
            .setWorldSlug(request.getWorldSlug())
            .setRealmId(
                mode == ResponseMode.SUBSTITUTED_REALM
                    ? "ffffffff-ffff-4fff-8fff-ffffffffffff"
                    : request.getRealmId())
            .setPlayableStateNamespaceId(request.getPlayableStateNamespaceId())
            .setPlayableStateScope(request.getPlayableStateScope())
            .setCanonicalGameInstanceId(request.getCanonicalGameInstanceId())
            .setCanonicalVersionId(request.getCanonicalVersionId())
            .setActiveLifecycleEpoch(request.getActiveLifecycleEpoch())
            .setExpectedCatalogRevision(request.getExpectedCatalogRevision())
            .setOrigin(request.getOrigin())
            .setHoldId(request.getHoldId())
            .setHoldFence(request.getHoldFence())
            .setHoldBindingDigest(request.getHoldBindingDigest());
    if (request.hasExpectedPriorPointerVersion()) {
      builder.setExpectedPriorPointerVersion(request.getExpectedPriorPointerVersion());
    }
    if (mode == ResponseMode.PENDING) {
      builder.setOutcome(
          CanonicalInitialAdmissionOwnerProofOutcome
              .CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_PENDING);
    } else if (mode == ResponseMode.ABORTED) {
      builder
          .setOutcome(
              CanonicalInitialAdmissionOwnerProofOutcome
                  .CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_ABORTED)
          .setProofDigest("sha256:" + "b".repeat(64))
          .setTerminalAt(Timestamp.newBuilder().setSeconds(1_791_331_200L))
          .setPositiveDurableAbort(true);
    } else {
      builder
          .setOutcome(
              CanonicalInitialAdmissionOwnerProofOutcome
                  .CANONICAL_INITIAL_ADMISSION_OWNER_PROOF_OUTCOME_COMMITTED)
          .setProofDigest("sha256:" + "b".repeat(64))
          .setTerminalAt(Timestamp.newBuilder().setSeconds(1_791_331_200L))
          .setCommittedPointerVersion(
              request.getOrigin()
                      == CanonicalInitialAdmissionOrigin
                          .CANONICAL_INITIAL_ADMISSION_ORIGIN_EXPECT_CLOSED
                  ? request.getExpectedPriorPointerVersion() + 1L
                  : 1L)
          .setAuditEventId(42L);
    }
    return builder.build();
  }

  private static HoldIdentity identity(InitialAdmissionOrigin origin) {
    var request =
        new Request(
            NAMESPACE,
            TENANT,
            "green-hollow",
            REALM,
            PLAYABLE_NAMESPACE,
            "SHARED",
            GAME_INSTANCE,
            VERSION,
            7L,
            "gs-initial-admission-17",
            "a".repeat(64),
            origin,
            12L,
            origin == InitialAdmissionOrigin.EXPECT_CLOSED ? 3L : null);
    return new HoldIdentity(request, HOLD_ID, HOLD_FENCE);
  }

  private static TestIdentity issueLeaf(
      X500Name caName, PrivateKey caPrivateKey, String workloadUri) throws Exception {
    KeyPair keys = newRsaKeyPair();
    var names =
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
    X500Name subject = new X500Name("CN=FireMUD Test Workload, O=FireMUD Test");
    X509Certificate certificate =
        issueCertificate(
            subject, keys.getPublic(), caName, caPrivateKey, false, new GeneralNames(names));
    return new TestIdentity(keys.getPrivate(), certificate);
  }

  private static X509Certificate issueCertificate(
      X500Name subject,
      java.security.PublicKey publicKey,
      X500Name issuer,
      PrivateKey issuerPrivateKey,
      boolean ca,
      GeneralNames subjectAltNames)
      throws Exception {
    Instant now = Instant.now().minusSeconds(60);
    var builder =
        new JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(CERTIFICATE_SERIAL.getAndIncrement()),
            Date.from(now),
            Date.from(now.plusSeconds(60L * 60L * 24L * 14L)),
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
                    .build(issuerPrivateKey)));
  }

  private static KeyPair newRsaKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record TestIdentity(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestIdentity gameSessionServer,
      TestIdentity wrongWorkloadServer,
      TestIdentity otherNamespaceServer,
      TestIdentity missingWorkloadUriServer,
      TestIdentity worldClient) {
    private CommonGrpcClientProperties clientProperties(Path directory)
        throws IOException, CertificateEncodingException {
      var properties = new CommonGrpcClientProperties();
      properties.setCertChain(
          writePem(
                  directory.resolve("world-client.crt"),
                  "CERTIFICATE",
                  worldClient.certificate().getEncoded())
              .toString());
      properties.setPrivateKey(
          writePem(
                  directory.resolve("world-client.key"),
                  "PRIVATE KEY",
                  worldClient.privateKey().getEncoded())
              .toString());
      properties.setCaCert(
          writePem(directory.resolve("test-ca.crt"), "CERTIFICATE", caCertificate.getEncoded())
              .toString());
      return properties;
    }
  }

  private static Path writePem(Path path, String label, byte[] bytes) throws IOException {
    String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes);
    return Files.writeString(
        path,
        "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n",
        StandardCharsets.US_ASCII);
  }
}
