package net.firedevops.firemud.common.security.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

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
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeTerminalReadEvidence;
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeTerminalReadGrpcCodec;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.v1.EntitySelectedSourceIntakeTerminalReadServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.ReadSelectedSourceIntakeTerminalRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadSelectedSourceIntakeTerminalResponse;
import net.firedevops.firemud.testsupport.entity.EntitySelectedSourceIntakeTerminalReadFixtures;
import net.firedevops.firemud.testsupport.entity.EntitySelectedSourceIntakeTerminalReadFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Positive large-response loopback mTLS proof and client pre-transport denial tests. */
class GrpcEntitySelectedSourceIntakeTerminalReadClientMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String ENTITY_URI = "spiffe://firemud/ns/test/sa/entity-management-service";
  private static final String WRONG_ENTITY_URI = "spiffe://firemud/ns/test/sa/entity-other";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String STORE_PASSWORD = "synthetic-test-store-password";

  @TempDir static Path tempDirectory;
  private static Fixture fixture;
  private static TestPki pki;
  private Server server;
  private GrpcEntitySelectedSourceIntakeTerminalReadClient client;
  private AtomicInteger headers;
  private AtomicInteger bodies;

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
  void loopbackMtlsAcceptsCompleteLargeCanonicalReceiptAndPinsEntityPeer() throws Exception {
    var request =
        EntitySelectedSourceIntakeTerminalReadEvidence.Request.create(
            NAMESPACE, fixture.authorization());
    byte[] receiptBytes = fixture.receipt().canonicalBytes();
    var expectedResponse =
        EntitySelectedSourceIntakeTerminalReadGrpcCodec.toResponse(
            new EntitySelectedSourceIntakeTerminalReadEvidence(request, fixture.receipt()));
    assertThat(expectedResponse.getSerializedSize()).isGreaterThan(4 * 1024 * 1024);
    assertThat(expectedResponse.getSerializedSize())
        .isLessThanOrEqualTo(EntitySelectedSourceIntakeTerminalReadGrpcCodec.MAX_WIRE_BYTES);

    startServer(pki.entityServer());
    client = newClient(server, pki.accountClient());
    client.init();
    var actual = client.read(request);
    assertThat(actual.request()).isEqualTo(request);
    assertThat(actual.receipt().canonicalBytes()).isEqualTo(receiptBytes);
    assertThat(actual.receipt().authorizationBindingBytes())
        .isEqualTo(fixture.authorization().canonicalBytes());
    assertThat(headers).hasValue(1);
    assertThat(bodies).hasValue(1);

    stopCurrentTransport();
    startServer(pki.wrongServer());
    client = newClient(server, pki.accountClient());
    client.init();
    assertThatThrownBy(() -> client.read(request))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.UNAUTHENTICATED));
    assertThat(headers).hasValue(0);
    assertThat(bodies).hasValue(0);
  }

  @Test
  void namespaceSqlSynchronizationAndEndUserContextAreDeniedBeforeTransport(@TempDir Path dir)
      throws Exception {
    var factory = mock(GrpcChannelFactory.class);
    var request =
        EntitySelectedSourceIntakeTerminalReadEvidence.Request.create(
            NAMESPACE, fixture.authorization());
    var wrongNamespaceClient =
        new GrpcEntitySelectedSourceIntakeTerminalReadClient(
            new ServiceEndpointsProperties(), placeholderTls(dir), factory, "other");
    try {
      assertThatThrownBy(() -> wrongNamespaceClient.read(request))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("configured workload namespace");
      verifyNoInteractions(factory);
    } finally {
      wrongNamespaceClient.close();
    }

    var client =
        new GrpcEntitySelectedSourceIntakeTerminalReadClient(
            new ServiceEndpointsProperties(), placeholderTls(dir), factory, NAMESPACE);
    try {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> client.read(null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ambient SQL");
      TransactionSynchronizationManager.setActualTransactionActive(false);

      TransactionSynchronizationManager.initSynchronization();
      assertThatThrownBy(() -> client.read(null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ambient SQL");
      TransactionSynchronizationManager.clearSynchronization();

      SessionContext.setContext("101", List.of(), Map.of());
      assertThatThrownBy(() -> client.read(null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("workload-only context");
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
    var service =
        new EntitySelectedSourceIntakeTerminalReadServiceGrpc
            .EntitySelectedSourceIntakeTerminalReadServiceImplBase() {
          @Override
          public void readSelectedSourceIntakeTerminal(
              ReadSelectedSourceIntakeTerminalRequest wire,
              StreamObserver<ReadSelectedSourceIntakeTerminalResponse> observer) {
            bodies.incrementAndGet();
            assertThat(GrpcPeerIdentity.current().uri()).isEqualTo(ACCOUNT_URI);
            var request = EntitySelectedSourceIntakeTerminalReadGrpcCodec.fromRequest(wire);
            assertThat(request.binding().canonicalBytes())
                .isEqualTo(fixture.authorization().canonicalBytes());
            observer.onNext(
                EntitySelectedSourceIntakeTerminalReadGrpcCodec.toResponse(
                    new EntitySelectedSourceIntakeTerminalReadEvidence(
                        request, fixture.receipt())));
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
                EntitySelectedSourceIntakeTerminalReadGrpcCodec.MAX_REQUEST_WIRE_BYTES)
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

  private GrpcEntitySelectedSourceIntakeTerminalReadClient newClient(
      Server target, TestIdentity local) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setEntityManagementService("localhost:" + target.getPort());
    return new GrpcEntitySelectedSourceIntakeTerminalReadClient(
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
      TestIdentity accountClient) {
    private static TestPki create(Path directory) throws Exception {
      Path caStore = directory.resolve("entity-terminal-test-ca.p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=FireMUD Entity terminal-read test CA",
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
      Path caFile = directory.resolve("entity-terminal-test-ca.crt");
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
          issueIdentity(directory, caStore, caFile, "account-client", ACCOUNT_URI, false));
    }

    private CommonGrpcClientProperties clientProperties(Path directory, TestIdentity identity)
        throws Exception {
      Path certificate =
          writePem(
              directory.resolve("account-client.crt"),
              "CERTIFICATE",
              identity.certificate().getEncoded());
      Path privateKey =
          writePem(
              directory.resolve("account-client.key"),
              "PRIVATE KEY",
              identity.privateKey().getEncoded());
      Path caFile =
          writePem(
              directory.resolve("entity-terminal-client-ca.crt"),
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
