package net.firedevops.firemud.common.world;

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
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublishedSpawnRequirementsRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublishedSpawnRequirementsResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldPublishedSpawnRequirementsReadServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Physical mTLS proof against a labeled transport-only World source double. */
class WorldPublishedSpawnRequirementsClientMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String WORLD_URI = "spiffe://firemud/ns/test/sa/world-management-service";
  private static final String OTHER_WORLD_URI =
      "spiffe://firemud/ns/other-test/sa/world-management-service";
  private static final String WRONG_WORKLOAD_URI =
      "spiffe://firemud/ns/test/sa/game-design-service";
  private static final String ENTITY_URI = "spiffe://firemud/ns/test/sa/entity-management-service";
  private static final String WRONG_CLIENT_URI = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String STORE_PASSWORD = "test-only-store-password";

  @TempDir static Path tempDirectory;
  private static TestPki pki;
  private Server server;
  private WorldPublishedSpawnRequirementsClient client;
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
  void exactWorldPeerReturnsUnchangedSourceCarrierAndExactRequestId() throws Exception {
    var expected = WorldPublishedSpawnRequirementsGrpcCodecTest.evidence();
    startServer(pki.worldServer(), Mode.EXACT);
    client = newClient(server, pki.entityClient());
    client.init();

    var actual = client.read(expected.request());

    assertThat(actual).isEqualTo(expected);
    assertThat(actual.request().readRequestId()).isEqualTo(expected.request().readRequestId());
    assertThat(actual.familyCounts()).isEqualTo(expected.familyCounts());
    assertThat(actual.spawnRequirements()).isEqualTo(expected.spawnRequirements());
    assertThat(actual.generationRequirements()).isEqualTo(expected.generationRequirements());
    assertThat(headers).hasValue(1);
    assertThat(bodies).hasValue(1);
  }

  @Test
  void trustedWrongWorldWorkloadAndNamespaceReleaseNoRequestMetadataOrBody() throws Exception {
    var request = WorldPublishedSpawnRequirementsGrpcCodecTest.evidence().request();
    for (TestIdentity identity : List.of(pki.wrongWorkloadServer(), pki.otherNamespaceServer())) {
      startServer(identity, Mode.EXACT);
      client = newClient(server, pki.entityClient());
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
  void rejectsChangedRequestReleaseGraphAndFamilyEvidenceAfterAuthenticatedRead() throws Exception {
    var request = WorldPublishedSpawnRequirementsGrpcCodecTest.evidence().request();
    for (Mode mode :
        List.of(
            Mode.CHANGED_REQUEST, Mode.CHANGED_RELEASE, Mode.CHANGED_GRAPH, Mode.CHANGED_FAMILY)) {
      startServer(pki.worldServer(), mode);
      client = newClient(server, pki.entityClient());
      client.init();
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid published spawn-requirements evidence");
      assertThat(headers).hasValue(1);
      assertThat(bodies).hasValue(1);
      stopTransport();
    }
  }

  @Test
  void rejectsWrongLocalWorkloadPlaintextAndNonFileBackedTls() throws Exception {
    var request = WorldPublishedSpawnRequirementsGrpcCodecTest.evidence().request();
    startServer(pki.worldServer(), Mode.EXACT);

    assertThatThrownBy(() -> newClient(server, pki.wrongClient()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact same-namespace Entity workload identity");

    var plaintext = pki.clientProperties(tempDirectory, pki.entityClient());
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new WorldPublishedSpawnRequirementsClient(
                    endpoints(server), plaintext, new GrpcChannelFactory(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workload mTLS");

    var classpath = pki.clientProperties(tempDirectory, pki.entityClient());
    classpath.setCaCert("classpath:ca.crt");
    assertThatThrownBy(
            () ->
                new WorldPublishedSpawnRequirementsClient(
                    endpoints(server), classpath, new GrpcChannelFactory(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");

    client = newClient(server, pki.entityClient());
    assertThatThrownBy(() -> client.read(request)).isInstanceOf(IllegalStateException.class);
    client.init();
    client.close();
    assertThatThrownBy(() -> client.read(request)).isInstanceOf(IllegalStateException.class);
    assertThat(headers).hasValue(0);
  }

  private enum Mode {
    EXACT,
    CHANGED_REQUEST,
    CHANGED_RELEASE,
    CHANGED_GRAPH,
    CHANGED_FAMILY
  }

  private void startServer(TestIdentity identity, Mode mode) throws Exception {
    headers = new AtomicInteger();
    bodies = new AtomicInteger();
    var service =
        new WorldPublishedSpawnRequirementsReadServiceGrpc
            .WorldPublishedSpawnRequirementsReadServiceImplBase() {
          @Override
          public void readWorldPublishedSpawnRequirements(
              ReadWorldPublishedSpawnRequirementsRequest wire,
              StreamObserver<ReadWorldPublishedSpawnRequirementsResponse> observer) {
            bodies.incrementAndGet();
            assertThat(GrpcPeerIdentity.current().uri()).isEqualTo(ENTITY_URI);
            var request = WorldPublishedSpawnRequirementsGrpcCodec.fromRequest(wire);
            var evidence = WorldPublishedSpawnRequirementsGrpcCodecTest.evidence();
            var response = WorldPublishedSpawnRequirementsGrpcCodec.toResponse(request, evidence);
            if (mode == Mode.CHANGED_REQUEST) {
              response =
                  response.toBuilder()
                      .setRequest(
                          response.getRequest().toBuilder()
                              .setReadRequestId("ffffffff-ffff-4fff-8fff-ffffffffffff"))
                      .build();
            } else if (mode == Mode.CHANGED_RELEASE) {
              response =
                  response.toBuilder()
                      .setRequest(
                          response.getRequest().toBuilder()
                              .setExpectedReleaseAttestationDigest("sha256:" + "f".repeat(64)))
                      .build();
            } else if (mode == Mode.CHANGED_GRAPH) {
              response = response.toBuilder().setGraphDigest("sha256:" + "f".repeat(64)).build();
            } else if (mode == Mode.CHANGED_FAMILY) {
              response =
                  response.toBuilder()
                      .setFamilyCounts(
                          0,
                          response.getFamilyCounts(0).toBuilder()
                              .setFamily(
                                  net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType
                                      .WORLD_DESIGN_AGGREGATE_TYPE_UNSPECIFIED))
                      .build();
            }
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

  private WorldPublishedSpawnRequirementsClient newClient(Server target, TestIdentity local)
      throws Exception {
    return new WorldPublishedSpawnRequirementsClient(
        endpoints(target),
        pki.clientProperties(tempDirectory, local),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static ServiceEndpointsProperties endpoints(Server target) {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setWorldManagementService("localhost:" + target.getPort());
    return endpoints;
  }

  private record TestIdentity(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestIdentity worldServer,
      TestIdentity wrongWorkloadServer,
      TestIdentity otherNamespaceServer,
      TestIdentity entityClient,
      TestIdentity wrongClient) {
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
          "CN=FireMUD spawn-requirements test CA",
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
          issueIdentity(
              directory, caStore, caFile, "wrong-workload-server", WRONG_WORKLOAD_URI, true),
          issueIdentity(
              directory, caStore, caFile, "wrong-namespace-server", OTHER_WORLD_URI, true),
          issueIdentity(directory, caStore, caFile, "entity-client", ENTITY_URI, false),
          issueIdentity(directory, caStore, caFile, "wrong-client", WRONG_CLIENT_URI, false));
    }

    private CommonGrpcClientProperties clientProperties(Path directory, TestIdentity identity)
        throws Exception {
      Path certificate =
          writePem(
              directory.resolve("entity-client.crt"),
              "CERTIFICATE",
              identity.certificate().getEncoded());
      Path privateKey =
          writePem(
              directory.resolve("entity-client.key"),
              "PRIVATE KEY",
              identity.privateKey().getEncoded());
      Path caFile =
          writePem(
              directory.resolve("client-trust-ca.crt"), "CERTIFICATE", caCertificate.getEncoded());
      var properties = new CommonGrpcClientProperties();
      properties.setCertChain(certificate.toString());
      properties.setPrivateKey(privateKey.toString());
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
