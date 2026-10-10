package net.firedevops.firemud.common.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Result;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.gamesession.v1.OriginalStartSessionCurrentAttemptReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionCurrentAttemptRequest;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionCurrentAttemptResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Synthetic loopback mTLS proof; it does not read a real Game Session database. */
class OriginalStartSessionCurrentAttemptClientMtlsTest {
  private static final String NAMESPACE = "world-runtime";
  private static final String GAME_SESSION_URI =
      "spiffe://firemud/ns/world-runtime/sa/game-session-service";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/world-runtime/sa/account-service";
  private static final String OTHER_CLIENT_URI =
      "spiffe://firemud/ns/world-runtime/sa/entity-management-service";
  private static final String STORE_PASSWORD = "test-only-store-password";
  private static final byte[] PROJECTION =
      "synthetic-attached-account-projection".getBytes(StandardCharsets.UTF_8);

  @TempDir static Path tempDirectory;

  private static TestPki pki;
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicReference<String> lastPeerUri = new AtomicReference<>();
  private Server server;
  private boolean substituteEcho;
  private boolean malformedExpiry;
  private boolean stall;

  @BeforeAll
  static void certificates() throws Exception {
    pki = TestPki.create(tempDirectory);
  }

  @AfterEach
  void closeServerAndClearTransactionContext() throws Exception {
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      server = null;
    }
    TransactionSynchronizationManager.clear();
  }

  @Test
  void readsExactPendingObservationOverMutualTlsWithAccountWorkloadPeer() throws Exception {
    start(pki.gameSessionServer());
    try (var client = newClient(pki.accountClient())) {
      client.init();

      Result result =
          client.read(OriginalStartSessionCurrentAttemptEvidenceGrpcCodecTest.request());

      assertThat(result.phaseState()).isEqualTo("OWNER_EXECUTION_PENDING");
      assertThat(result.request())
          .isEqualTo(OriginalStartSessionCurrentAttemptEvidenceGrpcCodecTest.request());
      assertThat(result.accountRedemptionProjection()).containsExactly(PROJECTION);
      assertThat(result.accountRedemptionProjectionDigest())
          .isEqualTo(OriginalStartSessionCurrentAttemptEvidence.projectionDigest(PROJECTION));
      assertThat(lastPeerUri).hasValue(ACCOUNT_URI);
      assertThat(calls).hasValue(1);
    }
  }

  @Test
  void rejectsWrongClientPeerAndChangedGameSessionServerPeer() throws Exception {
    start(pki.gameSessionServer());
    try (var client = newClient(pki.otherClient())) {
      client.init();
      assertThatThrownBy(
              () -> client.read(OriginalStartSessionCurrentAttemptEvidenceGrpcCodecTest.request()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              error ->
                  assertThat(Status.fromThrowable(error).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));
      assertThat(lastPeerUri).hasValue(OTHER_CLIENT_URI);
      assertThat(calls).hasValue(1);
    }

    stopServer();
    start(pki.wrongGameSessionServer());
    int callsBeforeWrongServer = calls.get();
    try (var client = newClient(pki.accountClient())) {
      client.init();
      assertThatThrownBy(
              () -> client.read(OriginalStartSessionCurrentAttemptEvidenceGrpcCodecTest.request()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              error ->
                  assertThat(Status.fromThrowable(error).getCode())
                      .isEqualTo(Status.Code.UNAUTHENTICATED));
      assertThat(calls).hasValue(callsBeforeWrongServer);
    }
  }

  @Test
  void rejectsWrongNamespaceAmbientTransactionAndSynchronizationBeforeCallingPeer()
      throws Exception {
    start(pki.gameSessionServer());
    try (var client = newClient(pki.accountClient())) {
      client.init();
      var valid = OriginalStartSessionCurrentAttemptEvidenceGrpcCodecTest.request();
      try (var wrongNamespaceClient = newClient(pki.accountClient(), "another-runtime")) {
        wrongNamespaceClient.init();
        assertThatThrownBy(() -> wrongNamespaceClient.read(valid))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("configured workload namespace");
      }

      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        assertThatThrownBy(() -> client.read(valid))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ambient transaction or synchronization");
      } finally {
        TransactionSynchronizationManager.setActualTransactionActive(false);
      }
      TransactionSynchronizationManager.initSynchronization();
      try {
        assertThatThrownBy(() -> client.read(valid))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ambient transaction or synchronization");
      } finally {
        TransactionSynchronizationManager.clearSynchronization();
      }
      assertThat(calls).hasValue(0);
    }
  }

  @Test
  void rejectsSubstitutedRequestEchoAndMalformedLeaseExpiry() throws Exception {
    start(pki.gameSessionServer());
    try (var client = newClient(pki.accountClient())) {
      client.init();
      var request = OriginalStartSessionCurrentAttemptEvidenceGrpcCodecTest.request();

      substituteEcho = true;
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid current original StartSession attempt evidence");
      substituteEcho = false;
      malformedExpiry = true;
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid current original StartSession attempt evidence");
      assertThat(calls).hasValue(2);
    }
  }

  @Test
  void boundsUnavailableReadWithFiveSecondRpcDeadline() throws Exception {
    stall = true;
    start(pki.gameSessionServer());
    try (var client = newClient(pki.accountClient())) {
      client.init();
      assertThatThrownBy(
              () -> client.read(OriginalStartSessionCurrentAttemptEvidenceGrpcCodecTest.request()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              error ->
                  assertThat(Status.fromThrowable(error).getCode())
                      .isEqualTo(Status.Code.DEADLINE_EXCEEDED));
    }
  }

  @Test
  void requiresMutualTlsFileBackedCredentialsAndExplicitInitialization() throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    var plaintext = pki.clientProperties(tempDirectory, pki.accountClient());
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new GrpcOriginalStartSessionCurrentAttemptClient(
                    endpoints, plaintext, new GrpcChannelFactory(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");

    var client =
        new GrpcOriginalStartSessionCurrentAttemptClient(
            endpoints,
            pki.clientProperties(tempDirectory, pki.accountClient()),
            new GrpcChannelFactory(),
            NAMESPACE);
    try {
      assertThatThrownBy(
              () -> client.read(OriginalStartSessionCurrentAttemptEvidenceGrpcCodecTest.request()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not initialized and available");
    } finally {
      client.close();
    }
  }

  private void start(TestIdentity serverIdentity) throws Exception {
    var service =
        new OriginalStartSessionCurrentAttemptReadServiceGrpc
            .OriginalStartSessionCurrentAttemptReadServiceImplBase() {
          @Override
          public void readOriginalStartSessionCurrentAttempt(
              ReadOriginalStartSessionCurrentAttemptRequest wire,
              StreamObserver<ReadOriginalStartSessionCurrentAttemptResponse> observer) {
            calls.incrementAndGet();
            var peer = GrpcPeerIdentity.current();
            lastPeerUri.set(peer == null ? null : peer.uri());
            if (peer == null || !ACCOUNT_URI.equals(peer.uri())) {
              observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
              return;
            }
            if (stall) {
              return;
            }
            var request = OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromRequest(wire);
            var result =
                new Result(request, java.time.Instant.parse("2035-01-01T00:00:00Z"), PROJECTION);
            var response = OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(result);
            if (substituteEcho) {
              response =
                  response.toBuilder()
                      .setRequest(
                          wire.toBuilder()
                              .setReadRequestId("04444444-4444-4444-8444-444444444444")
                              .build())
                      .build();
            }
            if (malformedExpiry) {
              response =
                  response.toBuilder()
                      .setOriginalLeaseExpiresAt(
                          response.getOriginalLeaseExpiresAt().toBuilder()
                              .setNanos(1_000_000_000)
                              .build())
                      .build();
            }
            observer.onNext(response);
            observer.onCompleted();
          }
        };
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            serverIdentity.privateKey(), serverIdentity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private void stopServer() throws Exception {
    server.shutdownNow();
    assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    server = null;
  }

  private GrpcOriginalStartSessionCurrentAttemptClient newClient(TestIdentity identity)
      throws Exception {
    return newClient(identity, NAMESPACE);
  }

  private GrpcOriginalStartSessionCurrentAttemptClient newClient(
      TestIdentity identity, String workloadNamespace) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameSessionService("localhost:" + server.getPort());
    return new GrpcOriginalStartSessionCurrentAttemptClient(
        endpoints,
        pki.clientProperties(tempDirectory, identity),
        new GrpcChannelFactory(),
        workloadNamespace);
  }

  private record TestIdentity(String alias, PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestIdentity gameSessionServer,
      TestIdentity wrongGameSessionServer,
      TestIdentity accountClient,
      TestIdentity otherClient) {
    private static TestPki create(Path directory) throws Exception {
      Path caStore = directory.resolve("current-attempt-test-ca.p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=FireMUD current-attempt test CA",
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
      Path caFile = directory.resolve("current-attempt-test-ca.crt");
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
      return new TestPki(
          readCertificate(caFile),
          issueIdentity(directory, caStore, caFile, "game-session-server", GAME_SESSION_URI, true),
          issueIdentity(
              directory,
              caStore,
              caFile,
              "wrong-game-session-server",
              "spiffe://firemud/ns/world-runtime/sa/world-management-service",
              true),
          issueIdentity(directory, caStore, caFile, "account-client", ACCOUNT_URI, false),
          issueIdentity(directory, caStore, caFile, "other-client", OTHER_CLIENT_URI, false));
    }

    private CommonGrpcClientProperties clientProperties(Path directory, TestIdentity identity)
        throws Exception {
      Path certificate =
          writePem(
              directory.resolve(identity.alias() + ".client.crt"),
              "CERTIFICATE",
              identity.certificate().getEncoded());
      Path privateKey =
          writePem(
              directory.resolve(identity.alias() + ".client.key"),
              "PRIVATE KEY",
              identity.privateKey().getEncoded());
      Path caFile =
          writePem(
              directory.resolve("current-attempt-client-ca.crt"),
              "CERTIFICATE",
              caCertificate.getEncoded());
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
          alias,
          (PrivateKey) keyStore.getKey(alias, STORE_PASSWORD.toCharArray()),
          (X509Certificate) keyStore.getCertificate(alias));
    }
  }

  private static X509Certificate readCertificate(Path path) throws Exception {
    try (var input = Files.newInputStream(path)) {
      return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
    }
  }

  private static Path writePem(Path path, String label, byte[] encoded) throws IOException {
    String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
    Files.writeString(
        path,
        "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n",
        StandardCharsets.US_ASCII);
    return path;
  }

  private static void runKeytool(String... arguments) throws Exception {
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
    command.addAll(List.of(arguments));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    process.getInputStream().transferTo(output);
    int exitCode = process.waitFor();
    assertThat(exitCode)
        .withFailMessage("keytool failed: %s", output.toString(StandardCharsets.UTF_8))
        .isZero();
  }
}
