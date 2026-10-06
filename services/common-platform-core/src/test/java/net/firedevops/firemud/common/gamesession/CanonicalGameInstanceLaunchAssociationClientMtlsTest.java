package net.firedevops.firemud.common.gamesession;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.Request;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameInstanceLaunchAssociationRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameInstanceLaunchAssociationResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Physical socket proof against a transport-only double, not production owner database proof. */
class CanonicalGameInstanceLaunchAssociationClientMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String GAME_SESSION_URI = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String OTHER_NAMESPACE_URI =
      "spiffe://firemud/ns/other-test/sa/game-session-service";
  private static final String WRONG_WORKLOAD_URI =
      "spiffe://firemud/ns/test/sa/world-management-service";
  private static final String WORLD_CLIENT_URI = WRONG_WORKLOAD_URI;
  private static final String STORE_PASSWORD = "test-only-store-password";

  @TempDir static Path tempDirectory;
  private static TestPki pki;
  private Server server;
  private CanonicalGameInstanceLaunchAssociationClient client;
  private AtomicInteger headers;
  private AtomicInteger bodies;

  @BeforeAll
  static void createTrustedCertificates() throws Exception {
    pki = TestPki.create(tempDirectory);
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
  void exactPeerReturnsCompleteReadbackAndAllowsLaterGameSessionStatus() throws Exception {
    startServer(pki.gameSessionServer());
    client = newClient(server);
    var request = CanonicalGameInstanceLaunchAssociationReadGrpcCodecTest.v2Request();
    var expectedBinding = CanonicalGameInstanceLaunchAssociationReadGrpcCodecTest.v2Binding();
    assertThatThrownBy(() -> client.read(request)).isInstanceOf(IllegalStateException.class);

    client.init();
    var result = client.read(request);
    assertThat(result.request()).isEqualTo(request);
    assertThat(result.currentGameInstanceStatus().name()).isEqualTo("RUNNING");
    assertThat(result.launchBindingEvidence()).isEqualTo(expectedBinding);
    assertThat(result.launchBindingEvidence().releaseAttestation().schemaVersion()).isEqualTo(2);
    assertThat(result.launchBindingEvidence().releaseAttestation().worldStartLocationEvidence())
        .isEqualTo(expectedBinding.releaseAttestation().worldStartLocationEvidence());
    assertThat(headers).hasValue(1);
    assertThat(bodies).hasValue(1);

    client.close();
    assertThatThrownBy(() -> client.read(request)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void trustedWrongWorkloadOrNamespaceReleasesNoRequestHeadersOrBody() throws Exception {
    var request = CanonicalGameInstanceLaunchAssociationReadGrpcCodecTest.v2Request();
    for (TestIdentity identity : List.of(pki.wrongWorkloadServer(), pki.otherNamespaceServer())) {
      startServer(identity);
      client = newClient(server);
      client.init();
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              exception ->
                  assertThat(Status.fromThrowable(exception).getCode())
                      .isEqualTo(Status.Code.UNAUTHENTICATED));
      assertThat(headers).hasValue(0);
      assertThat(bodies).hasValue(0);
      stopTransport();
    }
  }

  @Test
  void callerCannotReadOutsideItsConfiguredNamespaceOrUseNonFileBackedTls(@TempDir Path directory)
      throws Exception {
    startServer(pki.gameSessionServer());
    var plaintext = pki.clientProperties(directory);
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new CanonicalGameInstanceLaunchAssociationClient(
                    new ServiceEndpointsProperties(),
                    plaintext,
                    new GrpcChannelFactory(),
                    NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");

    var classpath = pki.clientProperties(directory);
    classpath.setCaCert("classpath:ca.crt");
    assertThatThrownBy(
            () ->
                new CanonicalGameInstanceLaunchAssociationClient(
                    new ServiceEndpointsProperties(),
                    classpath,
                    new GrpcChannelFactory(),
                    NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");

    var missing = pki.clientProperties(directory);
    missing.setCaCert(directory.resolve("missing-ca.crt").toString());
    assertThatThrownBy(
            () ->
                new CanonicalGameInstanceLaunchAssociationClient(
                    new ServiceEndpointsProperties(), missing, new GrpcChannelFactory(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("existing readable file");

    client = newClient(server);
    client.init();
    Request original = otherNamespaceRequest();
    assertThatThrownBy(() -> client.read(original))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("configured workload namespace");
    assertThat(headers).hasValue(0);
    assertThat(bodies).hasValue(0);
  }

  private void startServer(TestIdentity identity) throws Exception {
    headers = new AtomicInteger();
    bodies = new AtomicInteger();
    var service =
        new GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceImplBase() {
          @Override
          public void getCanonicalGameInstanceLaunchAssociation(
              GetCanonicalGameInstanceLaunchAssociationRequest wire,
              StreamObserver<GetCanonicalGameInstanceLaunchAssociationResponse> observer) {
            bodies.incrementAndGet();
            assertThat(GrpcPeerIdentity.current().uri()).isEqualTo(WORLD_CLIENT_URI);
            var expected = CanonicalGameInstanceLaunchAssociationReadGrpcCodecTest.v2Request();
            assertThat(wire.getReadRequestId()).isEqualTo(expected.readRequestId().toString());
            assertThat(wire.getGameInstanceUuid())
                .isEqualTo(expected.gameInstanceUuid().toString());
            observer.onNext(
                CanonicalGameInstanceLaunchAssociationReadGrpcCodecTest.v2Response().toBuilder()
                    .setCurrentGameInstanceStatus("RUNNING")
                    .build());
            observer.onCompleted();
          }
        };
    ServerInterceptor countHeaders =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call,
              Metadata metadata,
              ServerCallHandler<ReqT, RespT> next) {
            headers.incrementAndGet();
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
                    service, countHeaders, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private CanonicalGameInstanceLaunchAssociationClient newClient(Server target) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameSessionService("localhost:" + target.getPort());
    return new CanonicalGameInstanceLaunchAssociationClient(
        endpoints, pki.clientProperties(tempDirectory), new GrpcChannelFactory(), NAMESPACE);
  }

  private record TestIdentity(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestIdentity gameSessionServer,
      TestIdentity wrongWorkloadServer,
      TestIdentity otherNamespaceServer,
      TestIdentity worldClient) {
    private static TestPki create(Path directory) throws Exception {
      Path caStore = directory.resolve("test-ca.p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=FireMUD launch-association test CA",
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
      Path caFile = directory.resolve("test-ca.crt");
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
          issueIdentity(directory, caStore, caFile, "game-session-server", GAME_SESSION_URI, true),
          issueIdentity(
              directory, caStore, caFile, "wrong-workload-server", WRONG_WORKLOAD_URI, true),
          issueIdentity(
              directory, caStore, caFile, "wrong-namespace-server", OTHER_NAMESPACE_URI, true),
          issueIdentity(directory, caStore, caFile, "world-client", WORLD_CLIENT_URI, false));
    }

    private CommonGrpcClientProperties clientProperties(Path directory) throws Exception {
      Path cert =
          writePem(
              directory.resolve("world-client.crt"),
              "CERTIFICATE",
              worldClient.certificate().getEncoded());
      Path key =
          writePem(
              directory.resolve("world-client.key"),
              "PRIVATE KEY",
              worldClient.privateKey().getEncoded());
      Path ca =
          writePem(
              directory.resolve("client-trust-ca.crt"), "CERTIFICATE", caCertificate.getEncoded());
      var properties = new CommonGrpcClientProperties();
      properties.setCertChain(cert.toString());
      properties.setPrivateKey(key.toString());
      properties.setCaCert(ca.toString());
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
      var command = new ArrayList<String>();
      command.add(keytool.toString());
      command.addAll(List.of(arguments));
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      String output;
      try (var stream = process.getInputStream()) {
        output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      }
      if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
        process.destroyForcibly();
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

  private static Request otherNamespaceRequest() {
    var original = CanonicalGameInstanceLaunchAssociationReadGrpcCodecTest.request();
    return new Request(
        original.readRequestId(),
        "other",
        original.canonicalTenantId(),
        original.worldSlug(),
        original.gameInstanceUuid(),
        original.controlPlaneRequestId(),
        original.launchDescriptorId(),
        original.expectedDescriptorRequestDigest(),
        original.expectedDescriptorResultDigest(),
        original.expectedReleaseAttestationEvidenceDigest());
  }
}
