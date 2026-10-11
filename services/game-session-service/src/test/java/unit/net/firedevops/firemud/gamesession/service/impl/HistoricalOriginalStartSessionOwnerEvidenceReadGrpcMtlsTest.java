package unit.net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ForwardingServerCallListener;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import java.io.IOException;
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
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidence;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidenceReadClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.service.impl.GameSessionOriginalStartSessionSelectionPinService;
import net.firedevops.firemud.gamesession.service.impl.HistoricalOriginalStartSessionOwnerEvidenceReadGrpcService;
import net.firedevops.firemud.gamesession.v1.HistoricalOriginalStartSessionOwnerEvidenceReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadHistoricalOriginalStartSessionOwnerEvidenceRequest;
import net.firedevops.firemud.test.TlsTestSupport;
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

/** Real loopback mTLS transport proof for the deliberately unregistered historical reader. */
class HistoricalOriginalStartSessionOwnerEvidenceReadGrpcMtlsTest {
  private static final String NAMESPACE = "gameplay";
  private static final String WORLD_URI =
      "spiffe://firemud/ns/gameplay/sa/world-management-service";
  private static final String WRONG_SERVICE_URI = "spiffe://firemud/ns/gameplay/sa/account-service";
  private static final String WRONG_NAMESPACE_URI =
      "spiffe://firemud/ns/other/sa/world-management-service";
  private static final String GAME_SESSION_URI =
      "spiffe://firemud/ns/gameplay/sa/game-session-service";
  private static final String WRONG_SERVER_NAMESPACE_URI =
      "spiffe://firemud/ns/other/sa/game-session-service";
  private static final HistoricalOriginalStartSessionOwnerEvidence.Request REQUEST = request();

  @TempDir static Path tempDirectory;

  private static TestPki pki;
  private final GameSessionOriginalStartSessionSelectionPinService owner =
      mock(GameSessionOriginalStartSessionSelectionPinService.class);
  private Server server;
  private ManagedChannel noCertificateChannel;

  @BeforeAll
  static void createTestPki() throws Exception {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    KeyPair authorityKey = keyPair();
    X500Name authorityName = new X500Name("CN=Historical reader test CA, O=FireMUD Test");
    X509Certificate authorityCertificate =
        issueCertificate(
            authorityName,
            authorityKey.getPublic(),
            authorityName,
            authorityKey.getPrivate(),
            true,
            null,
            false);
    pki =
        new TestPki(
            authorityCertificate,
            issueIdentity(authorityName, authorityKey.getPrivate(), "world", WORLD_URI, false),
            issueIdentity(
                authorityName,
                authorityKey.getPrivate(),
                "wrong-service",
                WRONG_SERVICE_URI,
                false),
            issueIdentity(
                authorityName,
                authorityKey.getPrivate(),
                "wrong-namespace",
                WRONG_NAMESPACE_URI,
                false),
            issueIdentity(
                authorityName,
                authorityKey.getPrivate(),
                "wrong-service-server",
                WRONG_SERVICE_URI,
                true),
            issueIdentity(
                authorityName,
                authorityKey.getPrivate(),
                "wrong-namespace-server",
                WRONG_SERVER_NAMESPACE_URI,
                true),
            issueIdentity(
                authorityName, authorityKey.getPrivate(), "game-session", GAME_SESSION_URI, true));
  }

  @AfterEach
  void stopTransportAndClearAuthContext() throws Exception {
    if (noCertificateChannel != null) {
      noCertificateChannel.shutdownNow();
      assertThat(noCertificateChannel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      noCertificateChannel = null;
    }
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      server = null;
    }
    SessionContext.clear();
  }

  @Test
  void exactSameNamespaceWorldClientReachesReceiverAndOwnerFailureDeniesRead() throws Exception {
    when(owner.readHistorical(REQUEST)).thenReturn(java.util.Optional.empty());
    startReceiver(false);

    try (var client = newClient(pki.world())) {
      client.init();
      assertThatThrownBy(() -> client.read(REQUEST))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.FAILED_PRECONDITION));
    }

    verify(owner).readHistorical(REQUEST);
  }

  @Test
  void trustedWrongServiceAndWrongNamespaceCertificatesNeverReachOwner() throws Exception {
    startReceiver(false);

    for (TestIdentity identity : List.of(pki.wrongService(), pki.wrongNamespace())) {
      try (var client = newClient(identity)) {
        client.init();
        assertThatThrownBy(() -> client.read(REQUEST))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
      }
    }

    verifyNoInteractions(owner);
  }

  @Test
  void trustedWrongServiceAndWrongNamespaceServerPeersAreRejectedBeforeOwnerAccess()
      throws Exception {
    for (TestIdentity serverIdentity :
        List.of(pki.wrongServiceServer(), pki.wrongNamespaceServer())) {
      startReceiver(serverIdentity, false);
      try (var client = newClient(pki.world())) {
        client.init();
        assertThatThrownBy(() -> client.read(REQUEST))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
      }
      stopReceiver();
    }

    verifyNoInteractions(owner);
  }

  @Test
  void missingClientCertificateIsRejectedByTlsBeforeOwnerAccess() throws Exception {
    startReceiver(false);
    noCertificateChannel =
        NettyChannelBuilder.forAddress("127.0.0.1", server.getPort())
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forClient().trustManager(pki.caCertificate()))
                    .build())
            .build();

    assertThatThrownBy(
            () ->
                HistoricalOriginalStartSessionOwnerEvidenceReadServiceGrpc.newBlockingStub(
                        noCertificateChannel)
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .readHistoricalOriginalStartSessionOwnerEvidence(toWireRequest()))
        .satisfies(
            failure ->
                assertThat(TlsTestSupport.isTlsHandshakeRejection(failure))
                    .as("server requires a trusted client certificate")
                    .isTrue());

    verifyNoInteractions(owner);
  }

  @Test
  void authenticatedWorldPeerWithEndUserContextNeverReachesOwner() throws Exception {
    startReceiver(true);

    try (var client = newClient(pki.world())) {
      client.init();
      assertThatThrownBy(() -> client.read(REQUEST))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    verifyNoInteractions(owner);
  }

  private void startReceiver(boolean injectEndUserContext) throws Exception {
    startReceiver(pki.gameSession(), injectEndUserContext);
  }

  private void startReceiver(TestIdentity serverIdentity, boolean injectEndUserContext)
      throws Exception {
    var receiver = new HistoricalOriginalStartSessionOwnerEvidenceReadGrpcService(owner, NAMESPACE);
    var builder =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(serverTlsContext(serverIdentity))
            .addService(
                ServerInterceptors.intercept(
                    receiver,
                    new GrpcPeerIdentityInterceptor(),
                    endUserContextInterceptor(injectEndUserContext)));
    server = builder.build().start();
  }

  private void stopReceiver() throws Exception {
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      server = null;
    }
  }

  private HistoricalOriginalStartSessionOwnerEvidenceReadClient newClient(TestIdentity identity)
      throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameSessionService("127.0.0.1:" + server.getPort());
    return new HistoricalOriginalStartSessionOwnerEvidenceReadClient(
        endpoints, pki.clientProperties(identity), new GrpcChannelFactory(), NAMESPACE);
  }

  private io.grpc.netty.shaded.io.netty.handler.ssl.SslContext serverTlsContext(
      TestIdentity serverIdentity) throws Exception {
    return GrpcSslContexts.configure(
            SslContextBuilder.forServer(serverIdentity.privateKey(), serverIdentity.certificate()))
        .trustManager(pki.caCertificate())
        .clientAuth(ClientAuth.REQUIRE)
        .build();
  }

  private static io.grpc.ServerInterceptor endUserContextInterceptor(boolean inject) {
    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(
            next.startCall(call, headers)) {
          @Override
          public void onHalfClose() {
            if (inject) {
              SessionContext.setContext("42", List.of("tenantAdmin"), java.util.Map.of());
            }
            try {
              super.onHalfClose();
            } finally {
              SessionContext.clear();
            }
          }
        };
      }
    };
  }

  private static HistoricalOriginalStartSessionOwnerEvidence.Request request() {
    return new HistoricalOriginalStartSessionOwnerEvidence.Request(
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            NAMESPACE,
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            "world",
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            "start-session-request",
            "launch-descriptor",
            "sha256:" + "a".repeat(64),
            "sha256:" + "b".repeat(64),
            "sha256:" + "c".repeat(64)),
        UUID.fromString("44444444-4444-4444-8444-444444444444"),
        9L);
  }

  private static ReadHistoricalOriginalStartSessionOwnerEvidenceRequest toWireRequest() {
    return net.firedevops.firemud.common.gamesession
        .HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec.toRequest(REQUEST);
  }

  private static KeyPair keyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private static TestIdentity issueIdentity(
      X500Name authorityName,
      PrivateKey authorityKey,
      String commonName,
      String workloadUri,
      boolean server)
      throws Exception {
    KeyPair pair = keyPair();
    X500Name subject = new X500Name("CN=" + commonName + ", O=FireMUD Test");
    X509Certificate certificate =
        issueCertificate(
            subject, pair.getPublic(), authorityName, authorityKey, false, workloadUri, server);
    return new TestIdentity(commonName, pair.getPrivate(), certificate);
  }

  private static X509Certificate issueCertificate(
      X500Name subject,
      java.security.PublicKey publicKey,
      X500Name issuer,
      PrivateKey issuerKey,
      boolean ca,
      String workloadUri,
      boolean server)
      throws Exception {
    long serial = Math.abs(UUID.randomUUID().getMostSignificantBits()) + 1L;
    var builder =
        new JcaX509v3CertificateBuilder(
            issuer,
            java.math.BigInteger.valueOf(serial),
            new java.util.Date(System.currentTimeMillis() - 60_000L),
            new java.util.Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30)),
            subject,
            publicKey);
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
    builder.addExtension(
        Extension.keyUsage,
        true,
        new KeyUsage(ca ? KeyUsage.keyCertSign | KeyUsage.cRLSign : KeyUsage.digitalSignature));
    if (workloadUri != null) {
      builder.addExtension(
          Extension.extendedKeyUsage,
          false,
          new ExtendedKeyUsage(
              server ? KeyPurposeId.id_kp_serverAuth : KeyPurposeId.id_kp_clientAuth));
      builder.addExtension(
          Extension.subjectAlternativeName,
          false,
          new GeneralNames(
              new GeneralName[] {
                new GeneralName(GeneralName.uniformResourceIdentifier, workloadUri),
                new GeneralName(GeneralName.dNSName, "localhost"),
                new GeneralName(GeneralName.iPAddress, "127.0.0.1")
              }));
    }
    return new JcaX509CertificateConverter()
        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
        .getCertificate(
            builder.build(
                new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(issuerKey)));
  }

  private record TestIdentity(String name, PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestIdentity world,
      TestIdentity wrongService,
      TestIdentity wrongNamespace,
      TestIdentity wrongServiceServer,
      TestIdentity wrongNamespaceServer,
      TestIdentity gameSession) {
    private CommonGrpcClientProperties clientProperties(TestIdentity identity)
        throws IOException, CertificateEncodingException {
      Path cert =
          writePem(identity.name() + ".crt", "CERTIFICATE", identity.certificate().getEncoded());
      Path key =
          writePem(identity.name() + ".key", "PRIVATE KEY", identity.privateKey().getEncoded());
      Path ca = writePem("test-ca.crt", "CERTIFICATE", caCertificate.getEncoded());
      var properties = new CommonGrpcClientProperties();
      properties.setCertChain(cert.toString());
      properties.setPrivateKey(key.toString());
      properties.setCaCert(ca.toString());
      return properties;
    }

    private Path writePem(String filename, String label, byte[] bytes) throws IOException {
      String encoded = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes);
      Path path = tempDirectory.resolve(filename);
      Files.writeString(
          path,
          "-----BEGIN " + label + "-----\n" + encoded + "\n-----END " + label + "-----\n",
          StandardCharsets.US_ASCII);
      return path;
    }
  }
}
