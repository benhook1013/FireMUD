package net.firedevops.firemud.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.client.GameDesignFreshTenantIdentityClient;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.service.impl.TenantIdentityGrpcService;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationRequest;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Actual Account client and GDS adapter over loopback mTLS; mocked persistence, no DB/deployment
 * proof.
 */
class AccountFreshTenantReceiptMtlsCrossServiceTest {
  private static final String NAMESPACE = "test";
  private static final String DESIGN_URI = "spiffe://firemud/ns/test/sa/game-design-service";
  private static final String WRONG_WORKLOAD_URI =
      "spiffe://firemud/ns/test/sa/world-management-service";
  private static final String WRONG_NAMESPACE_URI =
      "spiffe://firemud/ns/other/sa/game-design-service";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String STORE_PASSWORD = "test-only-store-password";
  private static final UUID CREATION_REQUEST_ID =
      UUID.fromString("12345678-1234-4234-8234-123456789abc");
  private static final UUID OPERATION_ID = UUID.fromString("22345678-1234-4234-8234-123456789abc");
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("32345678-1234-4234-8234-123456789abc");

  @TempDir static Path temporaryDirectory;

  private static TestPki pki;
  private Server server;
  private GameDesignFreshTenantIdentityClient client;
  private AtomicInteger rpcStarts;
  private GameTenantCreationRepository repository;
  private AtomicReference<GrpcPeerIdentity> receivedPeer;
  private AtomicReference<ResolveFreshTenantCreationRequest> receivedRequest;

  @BeforeAll
  static void createLocalTestCertificates() throws Exception {
    pki = TestPki.create(temporaryDirectory);
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
  void readsClosedBoundEvidenceFromExactSameNamespaceGameDesignPeer() throws Exception {
    FreshTenantCreationEvidence expected = evidence();
    startServer(pki.gameDesignServer(), expected);
    client = newClient(server);
    client.init();

    FreshTenantCreationEvidence result =
        client.resolveCreation(CREATION_REQUEST_ID, expected.requestDigest());

    assertThat(result).isEqualTo(expected);
    assertThat(rpcStarts).hasValue(1);
    assertThat(client.resolveCreation(CREATION_REQUEST_ID, expected.requestDigest()))
        .isEqualTo(expected);
    verify(repository, times(2)).read(CREATION_REQUEST_ID, NAMESPACE);
    assertThat(receivedPeer.get())
        .isNotNull()
        .extracting(GrpcPeerIdentity::uri)
        .isEqualTo(ACCOUNT_URI);
    assertThat(receivedRequest.get())
        .isEqualTo(
            ResolveFreshTenantCreationRequest.newBuilder()
                .setCreationRequestId(CREATION_REQUEST_ID.toString())
                .setExpectedRequestDigest(expected.requestDigest())
                .build());
  }

  @Test
  void wrongServerWorkloadAndNamespaceCannotReturnAcceptedReceipt() throws Exception {
    FreshTenantCreationEvidence expected = evidence();
    for (TestIdentity wrongServer :
        List.of(pki.wrongWorkloadServer(), pki.wrongNamespaceServer())) {
      startServer(wrongServer, expected);
      client = newClient(server);
      client.init();

      assertThatThrownBy(
              () -> client.resolveCreation(CREATION_REQUEST_ID, expected.requestDigest()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.UNAUTHENTICATED));

      // Server identity rejection is client-side: a read may race with rejection,
      // but no foreign-server result is accepted by the Account client.
      stopTransport();
    }
  }

  @Test
  void wrongAccountNamespaceAndWrongWorkloadCannotReadOwner() throws Exception {
    for (TestIdentity peer : List.of(pki.wrongClient(), pki.foreignClient())) {
      startServer(pki.gameDesignServer(), evidence());
      ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
      endpoints.setGameDesignService("localhost:" + server.getPort());
      client =
          new GameDesignFreshTenantIdentityClient(
              endpoints,
              pki.clientProperties(temporaryDirectory, peer),
              new GrpcChannelFactory(),
              NAMESPACE);
      client.init();
      assertThatThrownBy(
              () -> client.resolveCreation(CREATION_REQUEST_ID, evidence().requestDigest()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));
      verifyNoInteractions(repository);
      stopTransport();
    }
  }

  @Test
  void changedReceiptIsRejectedByActualOwnerWithoutSuccessfulReadback() throws Exception {
    startServer(pki.gameDesignServer(), evidence());
    var other =
        new FreshTenantCreationEvidence(
            1,
            NAMESPACE,
            UUID.fromString("92345678-1234-4234-8234-123456789abc"),
            OPERATION_ID,
            evidence().requestDigest(),
            CANONICAL_TENANT_ID,
            42L,
            "42",
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                NAMESPACE,
                UUID.fromString("92345678-1234-4234-8234-123456789abc"),
                OPERATION_ID,
                evidence().requestDigest(),
                CANONICAL_TENANT_ID,
                42L,
                "42",
                "NEW_GAME_ROW"));
    when(repository.read(CREATION_REQUEST_ID, NAMESPACE)).thenReturn(Optional.of(other));
    client = newClient(server);
    client.init();
    assertThatThrownBy(
            () -> client.resolveCreation(CREATION_REQUEST_ID, evidence().requestDigest()))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.FAILED_PRECONDITION));
    verify(repository).read(CREATION_REQUEST_ID, NAMESPACE);
  }

  @Test
  void certificateLessTransportCannotReachOwner() throws Exception {
    startServer(pki.gameDesignServer(), evidence());
    var channel =
        io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder.forAddress(
                "localhost", server.getPort())
            .sslContext(GrpcSslContexts.forClient().trustManager(pki.caCertificate()).build())
            .build();
    try {
      assertThatThrownBy(
              () ->
                  TenantIdentityServiceGrpc.newBlockingStub(channel)
                      .withDeadlineAfter(3, TimeUnit.SECONDS)
                      .resolveFreshTenantCreation(
                          ResolveFreshTenantCreationRequest.newBuilder()
                              .setCreationRequestId(CREATION_REQUEST_ID.toString())
                              .setExpectedRequestDigest(evidence().requestDigest())
                              .build()))
          .isInstanceOf(StatusRuntimeException.class);
      verifyNoInteractions(repository);
      assertThat(rpcStarts).hasValue(0);
    } finally {
      channel.shutdownNow();
      assertThat(channel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
  }

  private void startServer(TestIdentity identity, FreshTenantCreationEvidence expected)
      throws Exception {
    rpcStarts = new AtomicInteger();
    receivedPeer = new AtomicReference<>();
    receivedRequest = new AtomicReference<>();
    repository = mock(GameTenantCreationRepository.class);
    when(repository.read(CREATION_REQUEST_ID, NAMESPACE)).thenReturn(Optional.of(expected));
    var service = new TenantIdentityGrpcService(repository, NAMESPACE);
    ServerInterceptor countRpcStarts =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            rpcStarts.incrementAndGet();
            receivedPeer.set(GrpcPeerIdentity.current());
            var delegate = next.startCall(call, headers);
            return new io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener<
                ReqT>(delegate) {
              @Override
              public void onMessage(ReqT message) {
                receivedRequest.set((ResolveFreshTenantCreationRequest) message);
                super.onMessage(message);
              }
            };
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
                    service, countRpcStarts, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private GameDesignFreshTenantIdentityClient newClient(Server target) throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("localhost:" + target.getPort());
    return new GameDesignFreshTenantIdentityClient(
        endpoints, pki.clientProperties(temporaryDirectory), new GrpcChannelFactory(), NAMESPACE);
  }

  private static FreshTenantCreationEvidence evidence() {
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            NAMESPACE, CREATION_REQUEST_ID, "42", "Test game", "mTLS fixture");
    return new FreshTenantCreationEvidence(
        1,
        NAMESPACE,
        CREATION_REQUEST_ID,
        OPERATION_ID,
        requestDigest,
        CANONICAL_TENANT_ID,
        42L,
        "42",
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            NAMESPACE,
            CREATION_REQUEST_ID,
            OPERATION_ID,
            requestDigest,
            CANONICAL_TENANT_ID,
            42L,
            "42",
            "NEW_GAME_ROW"));
  }

  private record TestIdentity(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestIdentity gameDesignServer,
      TestIdentity wrongWorkloadServer,
      TestIdentity wrongNamespaceServer,
      TestIdentity gameSessionClient,
      TestIdentity wrongClient,
      TestIdentity foreignClient) {
    private static TestPki create(Path directory) throws Exception {
      Path caStore = directory.resolve("fresh-tenant-test-ca.p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=FireMUD fresh-tenant test CA",
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
      Path caFile = directory.resolve("fresh-tenant-test-ca.crt");
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
          issueIdentity(directory, caStore, caFile, "design-server", DESIGN_URI, true),
          issueIdentity(
              directory, caStore, caFile, "wrong-workload-server", WRONG_WORKLOAD_URI, true),
          issueIdentity(
              directory, caStore, caFile, "wrong-namespace-server", WRONG_NAMESPACE_URI, true),
          issueIdentity(directory, caStore, caFile, "game-session-client", ACCOUNT_URI, false),
          issueIdentity(
              directory,
              caStore,
              caFile,
              "wrong-client",
              "spiffe://firemud/ns/test/sa/world-management-service",
              false),
          issueIdentity(
              directory,
              caStore,
              caFile,
              "foreign-client",
              "spiffe://firemud/ns/other/sa/account-service",
              false));
    }

    private CommonGrpcClientProperties clientProperties(Path directory) throws Exception {
      return clientProperties(directory, gameSessionClient);
    }

    private CommonGrpcClientProperties clientProperties(Path directory, TestIdentity peer)
        throws Exception {
      Path clientCertificate =
          writePem(
              directory.resolve("game-session-client.crt"),
              "CERTIFICATE",
              peer.certificate().getEncoded());
      Path clientPrivateKey =
          writePem(
              directory.resolve("game-session-client.key"),
              "PRIVATE KEY",
              peer.privateKey().getEncoded());
      Path trustCa =
          writePem(
              directory.resolve("fresh-tenant-client-trust-ca.crt"),
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
