package net.firedevops.firemud.common.security.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Context;
import io.grpc.Deadline;
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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeCommandEvidence;
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeCommandGrpcCodec;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.publication.AccountSelectedPublicationOrderCredentials;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.v1.EntitySelectedSourceIntakeCommandServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.RetainSelectedEntitySourceRequest;
import net.firedevops.firemud.entitymanagement.v1.RetainSelectedEntitySourceResponse;
import net.firedevops.firemud.testsupport.entity.EntitySelectedSourceIntakeTerminalReadFixtures;
import net.firedevops.firemud.testsupport.entity.EntitySelectedSourceIntakeTerminalReadFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Positive command mTLS proof; synthetic fixture is not Entity producer or physical census proof.
 */
class GrpcEntitySelectedSourceIntakeCommandClientMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String ENTITY_URI = "spiffe://firemud/ns/test/sa/entity-management-service";
  private static final String WRONG_ENTITY_URI = "spiffe://firemud/ns/test/sa/entity-other";
  private static final String GAME_DESIGN_URI = "spiffe://firemud/ns/test/sa/game-design-service";
  private static final String STORE_PASSWORD = "synthetic-test-store-password";

  @TempDir static Path tempDirectory;
  private static Fixture fixture;
  private static TestPki pki;
  private Server server;
  private GrpcEntitySelectedSourceIntakeCommandClient client;
  private AtomicInteger headers;
  private AtomicInteger bodies;
  private AtomicReference<Deadline> deadline;

  @BeforeAll
  static void createSyntheticInputsAndTrustedCertificates() throws Exception {
    fixture = EntitySelectedSourceIntakeTerminalReadFixtures.create();
    pki = TestPki.create(tempDirectory);
  }

  @AfterEach
  void stopTransportAndClearAmbientContext() throws Exception {
    if (client != null) {
      client.close();
      client = null;
    }
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      server = null;
    }
    TransactionSynchronizationManager.clear();
    SessionContext.clear();
  }

  @Test
  void loopbackMtlsReturnsExactReceiptAndPinsEntityServerWithBoundedDeadline() throws Exception {
    var freeze = fixture.inputs().worldInventoryReadEvidence().request().freezeEvidence();
    var request =
        EntitySelectedSourceIntakeCommandEvidence.Request.create(
            NAMESPACE, fixture.authorization(), freeze);
    byte[] receiptBytes = fixture.receipt().canonicalBytes();

    startServer(pki.entityServer());
    client = newClient(server, pki.gameDesignClient());
    client.init();
    var actual = client.retain(request);
    assertThat(actual.request()).isEqualTo(request);
    assertThat(actual.receipt().canonicalBytes()).isEqualTo(receiptBytes);
    assertThat(actual.receipt().authorizationBindingBytes())
        .isEqualTo(fixture.authorization().canonicalBytes());
    assertThat(headers).hasValue(1);
    assertThat(bodies).hasValue(1);
    assertThat(deadline.get()).isNotNull();
    assertThat(deadline.get().timeRemaining(TimeUnit.SECONDS)).isBetween(1L, 5L);

    stopCurrentTransport();
    startServer(pki.wrongServer());
    client = newClient(server, pki.gameDesignClient());
    client.init();
    assertThatThrownBy(() -> client.retain(request))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.UNAUTHENTICATED));
    assertThat(headers).hasValue(0);
    assertThat(bodies).hasValue(0);
  }

  @Test
  void namespaceSqlSynchronizationEndUserAndSelectedOrderAreDeniedBeforeTransport(@TempDir Path dir)
      throws Exception {
    var factory = mock(GrpcChannelFactory.class);
    var request =
        EntitySelectedSourceIntakeCommandEvidence.Request.create(
            NAMESPACE,
            fixture.authorization(),
            fixture.inputs().worldInventoryReadEvidence().request().freezeEvidence());
    var wrongNamespaceClient =
        new GrpcEntitySelectedSourceIntakeCommandClient(
            new ServiceEndpointsProperties(), placeholderTls(dir), factory, "other");
    try {
      assertThatThrownBy(() -> wrongNamespaceClient.retain(request))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("configured workload namespace");
      verifyNoInteractions(factory);
    } finally {
      wrongNamespaceClient.close();
    }

    var client =
        new GrpcEntitySelectedSourceIntakeCommandClient(
            new ServiceEndpointsProperties(), placeholderTls(dir), factory, NAMESPACE);
    try {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> client.retain(null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ambient SQL");
      TransactionSynchronizationManager.setActualTransactionActive(false);

      TransactionSynchronizationManager.initSynchronization();
      assertThatThrownBy(() -> client.retain(null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ambient SQL");
      TransactionSynchronizationManager.clearSynchronization();

      SessionContext.setContext("101", List.of(), Map.of());
      assertThatThrownBy(() -> client.retain(null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("workload-only context");
      SessionContext.clear();

      var selectedOrder =
          Context.current()
              .withValue(
                  AccountSelectedPublicationOrderCredentials.CONTEXT_KEY,
                  AccountSelectedPublicationOrderCredentials.Credential.of(
                      "synthetic-selected-order-credential"));
      var previous = selectedOrder.attach();
      try {
        assertThatThrownBy(() -> client.retain(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("workload-only context");
      } finally {
        selectedOrder.detach(previous);
      }
      verifyNoInteractions(factory);
    } finally {
      client.close();
      TransactionSynchronizationManager.clear();
      SessionContext.clear();
    }
  }

  private void startServer(TestIdentity identity) throws Exception {
    headers = new AtomicInteger();
    bodies = new AtomicInteger();
    deadline = new AtomicReference<>();
    var service =
        new EntitySelectedSourceIntakeCommandServiceGrpc
            .EntitySelectedSourceIntakeCommandServiceImplBase() {
          @Override
          public void retainSelectedEntitySource(
              RetainSelectedEntitySourceRequest wire,
              StreamObserver<RetainSelectedEntitySourceResponse> observer) {
            bodies.incrementAndGet();
            assertThat(GrpcPeerIdentity.current().uri()).isEqualTo(GAME_DESIGN_URI);
            deadline.set(Context.current().getDeadline());
            var request = EntitySelectedSourceIntakeCommandGrpcCodec.fromRequest(wire);
            assertThat(request.originalAuthorizationBinding().canonicalBytes())
                .isEqualTo(fixture.authorization().canonicalBytes());
            assertThat(request.freezeEvidence())
                .isEqualTo(
                    fixture.inputs().worldInventoryReadEvidence().request().freezeEvidence());
            observer.onNext(
                EntitySelectedSourceIntakeCommandGrpcCodec.toResponse(
                    new EntitySelectedSourceIntakeCommandEvidence(request, fixture.receipt())));
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
            .maxInboundMessageSize(
                EntitySelectedSourceIntakeCommandGrpcCodec.MAX_REQUEST_WIRE_BYTES)
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

  private GrpcEntitySelectedSourceIntakeCommandClient newClient(Server target, TestIdentity local)
      throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setEntityManagementService("localhost:" + target.getPort());
    return new GrpcEntitySelectedSourceIntakeCommandClient(
        endpoints, pki.clientProperties(tempDirectory, local), new GrpcChannelFactory(), NAMESPACE);
  }

  private void stopCurrentTransport() throws Exception {
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

  private static CommonGrpcClientProperties placeholderTls(Path directory) throws IOException {
    var tls = new CommonGrpcClientProperties();
    tls.setPlaintext(false);
    tls.setCertChain(
        Files.writeString(directory.resolve("client.crt"), "synthetic cert").toString());
    tls.setPrivateKey(
        Files.writeString(directory.resolve("client.key"), "synthetic key").toString());
    tls.setCaCert(Files.writeString(directory.resolve("ca.crt"), "synthetic CA").toString());
    return tls;
  }

  private record TestIdentity(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestIdentity entityServer,
      TestIdentity wrongServer,
      TestIdentity gameDesignClient) {
    private static TestPki create(Path directory) throws Exception {
      Path caStore = directory.resolve("entity-command-test-ca.p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=FireMUD Entity command test CA",
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
      Path caFile = directory.resolve("entity-command-test-ca.crt");
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
      var caCertificate = readCertificate(caFile);
      return new TestPki(
          caCertificate,
          issueIdentity(directory, caStore, caFile, "entity-server", ENTITY_URI, true),
          issueIdentity(directory, caStore, caFile, "wrong-entity-server", WRONG_ENTITY_URI, true),
          issueIdentity(directory, caStore, caFile, "game-design-client", GAME_DESIGN_URI, false));
    }

    private CommonGrpcClientProperties clientProperties(Path directory, TestIdentity identity)
        throws Exception {
      Path certificate =
          writePem(
              directory.resolve("game-design-client.crt"),
              "CERTIFICATE",
              identity.certificate().getEncoded());
      Path privateKey =
          writePem(
              directory.resolve("game-design-client.key"),
              "PRIVATE KEY",
              identity.privateKey().getEncoded());
      Path caFile =
          writePem(
              directory.resolve("entity-command-client-ca.crt"),
              "CERTIFICATE",
              caCertificate.getEncoded());
      var properties = new CommonGrpcClientProperties();
      properties.setPlaintext(false);
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
}
