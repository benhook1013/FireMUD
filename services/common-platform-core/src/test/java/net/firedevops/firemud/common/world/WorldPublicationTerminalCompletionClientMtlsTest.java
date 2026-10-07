package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
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
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.worldmanagement.v1.CompleteWorldPublicationTerminalRequest;
import net.firedevops.firemud.worldmanagement.v1.CompleteWorldPublicationTerminalResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldPublicationTerminalCompletionServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Physical mTLS transport proof against a labeled terminal owner-source double. */
class WorldPublicationTerminalCompletionClientMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String WORLD_URI = "spiffe://firemud/ns/test/sa/world-management-service";
  private static final String OTHER_WORLD_URI =
      "spiffe://firemud/ns/other-test/sa/world-management-service";
  private static final String WRONG_WORKLOAD_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String GAME_DESIGN_URI = "spiffe://firemud/ns/test/sa/game-design-service";
  private static final String STORE_PASSWORD = "test-only-store-password";
  @TempDir static Path tempDirectory;
  private static TestPki pki;
  private Server server;
  private WorldPublicationTerminalCompletionClient client;
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
  void exactWorldPeerReturnsTheSameCompleteTerminalAndOperation() throws Exception {
    var request = request();
    startServer(pki.worldServer(), Mode.EXACT);
    client = newClient(server);
    client.init();

    var result = client.complete(request);

    assertThat(result.request().canonicalBytes()).containsExactly(request.canonicalBytes());
    assertThat(result.terminalEvidence().canonicalBytes())
        .containsExactly(request.terminalEvidenceBytes());
    assertThat(headers).hasValue(1);
    assertThat(bodies).hasValue(1);
  }

  @Test
  void wrongWorldWorkloadAndNamespaceReleaseNoMetadataOrBody() throws Exception {
    var request = request();
    for (TestIdentity identity : List.of(pki.wrongWorkloadServer(), pki.otherNamespaceServer())) {
      startServer(identity, Mode.EXACT);
      client = newClient(server);
      client.init();
      assertThatThrownBy(() -> client.complete(request))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.UNAUTHENTICATED));
      assertThat(headers).hasValue(0);
      assertThat(bodies).hasValue(0);
      stopTransport();
    }
  }

  @Test
  void authenticatedWorldResponseCannotChangeRequestOrTerminal() throws Exception {
    var request = request();
    for (Mode mode : List.of(Mode.WRONG_REQUEST, Mode.MALFORMED)) {
      startServer(pki.worldServer(), mode);
      client = newClient(server);
      client.init();
      assertThatThrownBy(() -> client.complete(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid publication terminal completion evidence");
      assertThat(headers).hasValue(1);
      assertThat(bodies).hasValue(1);
      stopTransport();
    }
  }

  @Test
  void requiresFileBackedMtlsAndExplicitOpenClient() throws Exception {
    var plain = pki.clientProperties(tempDirectory);
    plain.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new WorldPublicationTerminalCompletionClient(
                    new ServiceEndpointsProperties(), plain, new GrpcChannelFactory(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    var classpath = pki.clientProperties(tempDirectory);
    classpath.setCaCert("classpath:ca.crt");
    assertThatThrownBy(
            () ->
                new WorldPublicationTerminalCompletionClient(
                    new ServiceEndpointsProperties(),
                    classpath,
                    new GrpcChannelFactory(),
                    NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    var missing = pki.clientProperties(tempDirectory);
    missing.setCaCert(tempDirectory.resolve("absent-ca.crt").toString());
    assertThatThrownBy(
            () ->
                new WorldPublicationTerminalCompletionClient(
                    new ServiceEndpointsProperties(), missing, new GrpcChannelFactory(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);

    startServer(pki.worldServer(), Mode.EXACT);
    client = newClient(server);
    var request = request();
    assertThatThrownBy(() -> client.complete(request)).isInstanceOf(IllegalStateException.class);
    client.init();
    client.close();
    assertThatThrownBy(() -> client.complete(request)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(client::init).isInstanceOf(IllegalStateException.class);
    assertThat(headers).hasValue(0);
  }

  private enum Mode {
    EXACT,
    WRONG_REQUEST,
    MALFORMED
  }

  private void startServer(TestIdentity identity, Mode mode) throws Exception {
    headers = new AtomicInteger();
    bodies = new AtomicInteger();
    var request = request();
    var responseRequest =
        mode == Mode.WRONG_REQUEST
            ? WorldPublicationTerminalCompletionGrpcCodecTest.changedRequest(request)
            : request;
    var service =
        new WorldPublicationTerminalCompletionServiceGrpc
            .WorldPublicationTerminalCompletionServiceImplBase() {
          @Override
          public void completeWorldPublicationTerminal(
              CompleteWorldPublicationTerminalRequest wire,
              StreamObserver<CompleteWorldPublicationTerminalResponse> observer) {
            bodies.incrementAndGet();
            assertThat(GrpcPeerIdentity.current().uri()).isEqualTo(GAME_DESIGN_URI);
            WorldPublicationTerminalCompletionGrpcCodec.fromRequest(wire);
            var response =
                mode == Mode.MALFORMED
                    ? CompleteWorldPublicationTerminalResponse.newBuilder()
                        .setCanonicalResponseBytes(ByteString.copyFrom(new byte[] {(byte) 0xff}))
                        .build()
                    : WorldPublicationTerminalCompletionGrpcCodec.toResponse(
                        responseRequest, responseRequest.terminalEvidence());
            observer.onNext(response);
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

  private WorldPublicationTerminalCompletionClient newClient(Server target) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setWorldManagementService("localhost:" + target.getPort());
    return new WorldPublicationTerminalCompletionClient(
        endpoints, pki.clientProperties(tempDirectory), new GrpcChannelFactory(), NAMESPACE);
  }

  private static WorldPublicationTerminalCompletionGrpcCodec.Request request() throws Exception {
    var readRequest = WorldPublicationTerminalReadGrpcCodecTest.request(false);
    var terminal = readRequest.terminalEvidence();
    return new WorldPublicationTerminalCompletionGrpcCodec.Request(
        1, readRequest.targetNamespace(), terminal.operationBytes(), terminal.canonicalBytes());
  }

  private record TestIdentity(String alias, PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestIdentity worldServer,
      TestIdentity wrongWorkloadServer,
      TestIdentity otherNamespaceServer,
      TestIdentity gameDesignClient) {
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
          "CN=FireMUD lifecycle test CA",
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
          issueIdentity(directory, caStore, caFile, "world-server", WORLD_URI, true),
          issueIdentity(directory, caStore, caFile, "wrong-world-server", WRONG_WORKLOAD_URI, true),
          issueIdentity(directory, caStore, caFile, "other-world-server", OTHER_WORLD_URI, true),
          issueIdentity(directory, caStore, caFile, "game-design-client", GAME_DESIGN_URI, false));
    }

    private CommonGrpcClientProperties clientProperties(Path directory) throws Exception {
      Path clientCertificate =
          writePem(
              directory.resolve("game-design-client.crt"),
              "CERTIFICATE",
              gameDesignClient.certificate().getEncoded());
      Path clientPrivateKey =
          writePem(
              directory.resolve("game-design-client.key"),
              "PRIVATE KEY",
              gameDesignClient.privateKey().getEncoded());
      Path caFile =
          writePem(
              directory.resolve("client-trust-ca.crt"), "CERTIFICATE", caCertificate.getEncoded());
      CommonGrpcClientProperties properties = new CommonGrpcClientProperties();
      properties.setCertChain(clientCertificate.toString());
      properties.setPrivateKey(clientPrivateKey.toString());
      properties.setCaCert(caFile.toString());
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
          alias,
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
      var command = new java.util.ArrayList<String>();
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
}
