package unit.net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
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
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.service.impl.HistoricalOriginalStartSessionOwnerEvidenceReadGrpcService;
import net.firedevops.firemud.gamesession.v1.HistoricalOriginalStartSessionOwnerEvidenceReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadHistoricalOriginalStartSessionOwnerEvidenceRequest;
import net.firedevops.firemud.test.TlsTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Controlled mTLS denial proof for the current unregistered historical receiver; owner reads are
 * mocked.
 */
class HistoricalOriginalStartSessionOwnerEvidenceReadGrpcMtlsTest {
  private static final String NAMESPACE = "gameplay";
  private static final String STORE_PASSWORD = "test-only-store-password";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/gameplay/sa/account-service";
  private static final String WORLD_URI =
      "spiffe://firemud/ns/gameplay/sa/world-management-service";
  private static final String WRONG_SERVICE_URI =
      "spiffe://firemud/ns/gameplay/sa/logging-admin-service";
  private static final String WRONG_NAMESPACE_URI =
      "spiffe://firemud/ns/other/sa/world-management-service";
  private static final String GAME_SESSION_URI =
      "spiffe://firemud/ns/gameplay/sa/game-session-service";
  private static final String WRONG_SERVER_NAMESPACE_URI =
      "spiffe://firemud/ns/other/sa/game-session-service";
  private static final HistoricalOriginalStartSessionOwnerEvidence.Request REQUEST = request();

  @TempDir static Path tempDirectory;

  private static TestPki pki;
  private final CanonicalGameInstanceLaunchAssociationRepository owner =
      mock(CanonicalGameInstanceLaunchAssociationRepository.class);
  private Server server;
  private ManagedChannel noCertificateChannel;

  @BeforeAll
  static void createTestPki() throws Exception {
    pki = TestPki.create(tempDirectory);
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
  void exactWorldAndAccountPeersReachCurrentHistoricalOwnerAndEmptyEvidenceDenies()
      throws Exception {
    when(owner.readHistoricalOriginalStartSessionOwnerEvidence(REQUEST))
        .thenReturn(java.util.Optional.empty());
    startReceiver(false);
    for (TestIdentity identity : List.of(pki.world(), pki.account())) {
      try (var client = newClient(identity)) {
        client.init();
        assertThatThrownBy(() -> client.read(REQUEST))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .isEqualTo(Status.Code.FAILED_PRECONDITION));
      }
    }
    org.mockito.Mockito.verify(owner, org.mockito.Mockito.times(2))
        .readHistoricalOriginalStartSessionOwnerEvidence(REQUEST);
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

  private record TestIdentity(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestIdentity world,
      TestIdentity account,
      TestIdentity wrongService,
      TestIdentity wrongNamespace,
      TestIdentity wrongServiceServer,
      TestIdentity wrongNamespaceServer,
      TestIdentity gameSession) {
    private static TestPki create(Path directory) throws Exception {
      Path caStore = directory.resolve("historical-owner-test-ca.p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=FireMUD historical-owner test CA",
          "-validity",
          "30",
          "-ext",
          "BC=ca:true",
          "-ext",
          "KU=keyCertSign,cRLSign",
          "-storetype",
          "PKCS12",
          "-keystore",
          caStore.toString(),
          "-storepass",
          STORE_PASSWORD,
          "-keypass",
          STORE_PASSWORD);
      Path caFile = directory.resolve("historical-owner-test-ca.crt");
      runKeytool(
          "-exportcert",
          "-alias",
          "test-ca",
          "-keystore",
          caStore.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          caFile.toString(),
          "-rfc");
      X509Certificate caCertificate = readCertificate(caFile);
      return new TestPki(
          caCertificate,
          issueIdentity(directory, caStore, caFile, "world-client", WORLD_URI, false),
          issueIdentity(directory, caStore, caFile, "account-client", ACCOUNT_URI, false),
          issueIdentity(directory, caStore, caFile, "wrong-client", WRONG_SERVICE_URI, false),
          issueIdentity(directory, caStore, caFile, "foreign-client", WRONG_NAMESPACE_URI, false),
          issueIdentity(directory, caStore, caFile, "wrong-server", WRONG_SERVICE_URI, true),
          issueIdentity(
              directory, caStore, caFile, "foreign-server", WRONG_SERVER_NAMESPACE_URI, true),
          issueIdentity(directory, caStore, caFile, "game-session-server", GAME_SESSION_URI, true));
    }

    private CommonGrpcClientProperties clientProperties(TestIdentity identity) throws Exception {
      Path directory = tempDirectory;
      Path clientCertificate =
          writePem(
              directory.resolve("game-session-client.crt"),
              "CERTIFICATE",
              identity.certificate().getEncoded());
      Path clientPrivateKey =
          writePem(
              directory.resolve("game-session-client.key"),
              "PRIVATE KEY",
              identity.privateKey().getEncoded());
      Path trustCa =
          writePem(
              directory.resolve("historical-owner-client-trust-ca.crt"),
              "CERTIFICATE",
              caCertificate.getEncoded());
      CommonGrpcClientProperties properties = new CommonGrpcClientProperties();
      properties.setCertChain(clientCertificate.toString());
      properties.setPrivateKey(clientPrivateKey.toString());
      properties.setCaCert(trustCa.toString());
      return properties;
    }

    private static TestIdentity issueIdentity(
        Path directory, Path caStore, Path caFile, String alias, String workloadUri, boolean server)
        throws Exception {
      Path store = directory.resolve(alias + ".p12");
      Path request = directory.resolve(alias + ".csr");
      Path certificate = directory.resolve(alias + ".crt");
      String san = "URI:" + workloadUri + ",DNS:localhost,IP:127.0.0.1";
      runKeytool(
          "-genkeypair",
          "-alias",
          alias,
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=" + alias,
          "-validity",
          "30",
          "-ext",
          "KU=digitalSignature,keyEncipherment",
          "-ext",
          "EKU=" + (server ? "serverAuth" : "clientAuth"),
          "-ext",
          "SAN=" + san,
          "-storetype",
          "PKCS12",
          "-keystore",
          store.toString(),
          "-storepass",
          STORE_PASSWORD,
          "-keypass",
          STORE_PASSWORD);
      runKeytool(
          "-certreq",
          "-alias",
          alias,
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          request.toString(),
          "-ext",
          "SAN=" + san);
      runKeytool(
          "-gencert",
          "-alias",
          "test-ca",
          "-keystore",
          caStore.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-infile",
          request.toString(),
          "-outfile",
          certificate.toString(),
          "-validity",
          "30",
          "-rfc",
          "-ext",
          "BC=ca:false",
          "-ext",
          "KU=digitalSignature,keyEncipherment",
          "-ext",
          "EKU=" + (server ? "serverAuth" : "clientAuth"),
          "-ext",
          "SAN=" + san);
      runKeytool(
          "-importcert",
          "-alias",
          "test-ca",
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          caFile.toString(),
          "-noprompt");
      runKeytool(
          "-importcert",
          "-alias",
          alias,
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          STORE_PASSWORD,
          "-file",
          certificate.toString(),
          "-noprompt");
      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      try (var input = Files.newInputStream(store)) {
        keyStore.load(input, STORE_PASSWORD.toCharArray());
      }
      return new TestIdentity(
          (PrivateKey) keyStore.getKey(alias, STORE_PASSWORD.toCharArray()),
          (X509Certificate) keyStore.getCertificate(alias));
    }

    private static void runKeytool(String... arguments) throws Exception {
      Path keytool =
          Path.of(
              System.getProperty("java.home"),
              "bin",
              System.getProperty("os.name").toLowerCase().contains("windows")
                  ? "keytool.exe"
                  : "keytool");
      List<String> command = new ArrayList<>();
      command.add(keytool.toString());
      command.addAll(List.of(arguments));
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("keytool timed out");
      }
      String output;
      try (var stream = process.getInputStream()) {
        output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      }
      if (process.exitValue() != 0) {
        throw new IllegalStateException("keytool failed: " + output);
      }
    }

    private static X509Certificate readCertificate(Path path) throws Exception {
      try (var input = Files.newInputStream(path)) {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
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
}
