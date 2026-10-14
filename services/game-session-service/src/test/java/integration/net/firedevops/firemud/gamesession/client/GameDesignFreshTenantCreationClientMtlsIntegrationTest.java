package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationResponse;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Physical mTLS proof for exact Game Design fresh-tenant evidence reads. */
class GameDesignFreshTenantCreationClientMtlsIntegrationTest {
  private static final String NAMESPACE = "test";
  private static final String DESIGN_URI = "spiffe://firemud/ns/test/sa/game-design-service";
  private static final String WRONG_WORKLOAD_URI =
      "spiffe://firemud/ns/test/sa/world-management-service";
  private static final String WRONG_NAMESPACE_URI =
      "spiffe://firemud/ns/other/sa/game-design-service";
  private static final String GAME_SESSION_URI = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String STORE_PASSWORD = "test-only-store-password";
  private static final UUID CREATION_REQUEST_ID =
      UUID.fromString("12345678-1234-4234-8234-123456789abc");
  private static final UUID OPERATION_ID = UUID.fromString("22345678-1234-4234-8234-123456789abc");
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("32345678-1234-4234-8234-123456789abc");

  @TempDir static Path temporaryDirectory;

  private static TestPki pki;
  private Server server;
  private GameDesignFreshTenantCreationClient client;
  private AtomicInteger rpcStarts;
  private AtomicInteger applicationCalls;
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
    assertThat(applicationCalls).hasValue(1);
    assertThat(receivedPeer.get())
        .isNotNull()
        .extracting(GrpcPeerIdentity::uri)
        .isEqualTo(GAME_SESSION_URI);
    assertThat(receivedRequest.get())
        .isEqualTo(
            ResolveFreshTenantCreationRequest.newBuilder()
                .setCreationRequestId(CREATION_REQUEST_ID.toString())
                .setExpectedRequestDigest(expected.requestDigest())
                .build());
  }

  @Test
  void wrongWorkloadAndNamespacePeersReceiveNoResolveRpc() throws Exception {
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

      assertThat(rpcStarts).hasValue(0);
      assertThat(applicationCalls).hasValue(0);
      assertThat(receivedRequest.get()).isNull();
      stopTransport();
    }
  }

  private void startServer(TestIdentity identity, FreshTenantCreationEvidence expected)
      throws Exception {
    rpcStarts = new AtomicInteger();
    applicationCalls = new AtomicInteger();
    receivedPeer = new AtomicReference<>();
    receivedRequest = new AtomicReference<>();
    var service =
        new TenantIdentityServiceGrpc.TenantIdentityServiceImplBase() {
          @Override
          public void resolveFreshTenantCreation(
              ResolveFreshTenantCreationRequest request,
              StreamObserver<ResolveFreshTenantCreationResponse> observer) {
            applicationCalls.incrementAndGet();
            receivedPeer.set(GrpcPeerIdentity.current());
            receivedRequest.set(request);
            observer.onNext(response(expected));
            observer.onCompleted();
          }
        };
    ServerInterceptor countRpcStarts =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            rpcStarts.incrementAndGet();
            return next.startCall(call, headers);
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

  private GameDesignFreshTenantCreationClient newClient(Server target) throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("localhost:" + target.getPort());
    return new GameDesignFreshTenantCreationClient(
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

  private static ResolveFreshTenantCreationResponse response(FreshTenantCreationEvidence evidence) {
    return ResolveFreshTenantCreationResponse.newBuilder()
        .setSchemaVersion(evidence.schemaVersion())
        .setTargetNamespace(evidence.targetNamespace())
        .setCreationRequestId(evidence.creationRequestId().toString())
        .setOperationId(evidence.operationId().toString())
        .setRequestDigest(evidence.requestDigest())
        .setCanonicalTenantId(evidence.canonicalTenantId().toString())
        .setSourceGameRowId(evidence.sourceGameRowId())
        .setSourceGameTenantKey(evidence.sourceGameTenantKey())
        .setProvenanceKind(evidence.provenanceKind())
        .setEvidenceDigest(evidence.evidenceDigest())
        .build();
  }

  private record TestIdentity(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestIdentity gameDesignServer,
      TestIdentity wrongWorkloadServer,
      TestIdentity wrongNamespaceServer,
      TestIdentity gameSessionClient) {
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
          issueIdentity(
              directory, caStore, caFile, "game-session-client", GAME_SESSION_URI, false));
    }

    private CommonGrpcClientProperties clientProperties(Path directory) throws Exception {
      Path clientCertificate =
          writePem(
              directory.resolve("game-session-client.crt"),
              "CERTIFICATE",
              gameSessionClient.certificate().getEncoded());
      Path clientPrivateKey =
          writePem(
              directory.resolve("game-session-client.key"),
              "PRIVATE KEY",
              gameSessionClient.privateKey().getEncoded());
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
