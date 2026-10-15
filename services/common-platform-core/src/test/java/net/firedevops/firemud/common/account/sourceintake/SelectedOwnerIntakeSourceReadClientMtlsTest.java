package net.firedevops.firemud.common.account.sourceintake;

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
import net.firedevops.firemud.account.v1.AccountSelectedOwnerIntakeSourceReadServiceGrpc;
import net.firedevops.firemud.account.v1.SelectedOwnerIntakeSourcePermissionRequest;
import net.firedevops.firemud.account.v1.SelectedOwnerIntakeSourcePermissionResponse;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Synthetic scopes exercise client mTLS and correlation, not Account database/source proof. */
class SelectedOwnerIntakeSourceReadClientMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String WRONG_SERVER_URI = "spiffe://firemud/ns/test/sa/game-logic-service";
  private static final String GAME_DESIGN_URI = "spiffe://firemud/ns/test/sa/game-design-service";
  private static final String STORE_PASSWORD = "test-only-store-password";

  @TempDir static Path tempDirectory;

  private static TestPki pki;
  private final AtomicInteger calls = new AtomicInteger();
  private Server server;
  private boolean stall;
  private boolean substitute;

  @BeforeAll
  static void certificates() throws Exception {
    pki = TestPki.create(tempDirectory);
  }

  @AfterEach
  void closeServer() throws Exception {
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      server = null;
    }
    TransactionSynchronizationManager.clear();
  }

  @Test
  void pinsAccountServerAndRoundTripsBothPreliminaryOwnerScopes() throws Exception {
    start(pki.accountServer());
    try (var client = newClient(pki.gameDesignClient())) {
      client.init();
      for (var owner :
          List.of(
              DraftCommitBinding.Owner.ENTITY_MANAGEMENT,
              DraftCommitBinding.Owner.AUTOMATION_SCRIPTING)) {
        var request =
            SelectedOwnerIntakeSourceReadEvidence.Request.create(
                NAMESPACE, scope(owner, NAMESPACE));
        assertThat(client.read(request).request()).isEqualTo(request);
      }
      assertThat(calls).hasValue(2);
    }

    server.shutdownNow();
    assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    server = null;
    start(pki.wrongServer());
    try (var client = newClient(pki.gameDesignClient())) {
      client.init();
      var request =
          SelectedOwnerIntakeSourceReadEvidence.Request.create(
              NAMESPACE, scope(DraftCommitBinding.Owner.ENTITY_MANAGEMENT, NAMESPACE));
      assertThatThrownBy(() -> client.read(request)).isInstanceOf(StatusRuntimeException.class);
      assertThat(calls).hasValue(2);
    }
  }

  @Test
  void transportsNearFourMiBCanonicalScopeAndEchoWithinEightMiBWireBudget() throws Exception {
    var scope = scopeAtCanonicalLimit();
    assertThat(scope.canonicalBytes().length)
        .isGreaterThan(SelectedOwnerIntakeSourceReadScope.MAX_BYTES - 16)
        .isLessThanOrEqualTo(SelectedOwnerIntakeSourceReadScope.MAX_BYTES);
    var request = SelectedOwnerIntakeSourceReadEvidence.Request.create(NAMESPACE, scope);
    assertThat(SelectedOwnerIntakeSourceReadProtoCodec.toResponse(request).getSerializedSize())
        .isGreaterThan(4 * 1024 * 1024)
        .isLessThanOrEqualTo(SelectedOwnerIntakeSourceReadProtoCodec.MAX_WIRE_BYTES);

    start(pki.accountServer());
    try (var client = newClient(pki.gameDesignClient())) {
      client.init();
      assertThat(client.read(request).request()).isEqualTo(request);
    }
  }

  @Test
  void rejectsOtherNamespaceAmbientSqlAndSubstitutedResponse() throws Exception {
    start(pki.accountServer());
    substitute = true;
    try (var client = newClient(pki.gameDesignClient())) {
      client.init();
      var otherNamespace =
          SelectedOwnerIntakeSourceReadEvidence.Request.create(
              "other", scope(DraftCommitBinding.Owner.ENTITY_MANAGEMENT, "other"));
      assertThatThrownBy(() -> client.read(otherNamespace))
          .isInstanceOf(IllegalArgumentException.class);

      var request =
          SelectedOwnerIntakeSourceReadEvidence.Request.create(
              NAMESPACE, scope(DraftCommitBinding.Owner.ENTITY_MANAGEMENT, NAMESPACE));
      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        assertThatThrownBy(() -> client.read(request)).isInstanceOf(IllegalStateException.class);
      } finally {
        TransactionSynchronizationManager.setActualTransactionActive(false);
      }
      TransactionSynchronizationManager.initSynchronization();
      try {
        assertThatThrownBy(() -> client.read(request)).isInstanceOf(IllegalStateException.class);
      } finally {
        TransactionSynchronizationManager.clearSynchronization();
      }
      assertThatThrownBy(() -> client.read(request)).isInstanceOf(IllegalStateException.class);
      assertThat(calls).hasValue(1);
    }
  }

  @Test
  void boundsUnavailableAccountReadByFiveSecondDeadline() throws Exception {
    stall = true;
    start(pki.accountServer());
    try (var client = newClient(pki.gameDesignClient())) {
      client.init();
      var request =
          SelectedOwnerIntakeSourceReadEvidence.Request.create(
              NAMESPACE, scope(DraftCommitBinding.Owner.AUTOMATION_SCRIPTING, NAMESPACE));
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              error ->
                  assertThat(Status.fromThrowable(error).getCode())
                      .isEqualTo(Status.Code.DEADLINE_EXCEEDED));
    }
  }

  @Test
  void requiresTlsAndReadableFileBackedCredentials() {
    var endpoints = new ServiceEndpointsProperties();
    var plaintext = new CommonGrpcClientProperties();
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new GrpcSelectedOwnerIntakeSourceReadClient(
                    endpoints, plaintext, new GrpcChannelFactory(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GrpcSelectedOwnerIntakeSourceReadClient(
                    endpoints,
                    new CommonGrpcClientProperties(),
                    new GrpcChannelFactory(),
                    NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private void start(TestIdentity identity) throws Exception {
    var service =
        new AccountSelectedOwnerIntakeSourceReadServiceGrpc
            .AccountSelectedOwnerIntakeSourceReadServiceImplBase() {
          @Override
          public void readSourceScope(
              SelectedOwnerIntakeSourcePermissionRequest wire,
              StreamObserver<SelectedOwnerIntakeSourcePermissionResponse> observer) {
            calls.incrementAndGet();
            if (!GAME_DESIGN_URI.equals(GrpcPeerIdentity.current().uri())) {
              observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
              return;
            }
            if (stall) return;
            var request = SelectedOwnerIntakeSourceReadProtoCodec.fromRequest(wire);
            var response = SelectedOwnerIntakeSourceReadProtoCodec.toResponse(request);
            if (substitute) {
              response =
                  response.toBuilder()
                      .setRequest(
                          wire.toBuilder().setReadRequestId(UUID.randomUUID().toString()).build())
                      .build();
            }
            observer.onNext(response);
            observer.onCompleted();
          }
        };
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .maxInboundMessageSize(SelectedOwnerIntakeSourceReadProtoCodec.MAX_WIRE_BYTES)
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private GrpcSelectedOwnerIntakeSourceReadClient newClient(TestIdentity identity)
      throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + server.getPort());
    return new GrpcSelectedOwnerIntakeSourceReadClient(
        endpoints,
        pki.clientProperties(tempDirectory, identity),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static SelectedOwnerIntakeSourceReadScope scope(
      DraftCommitBinding.Owner owner, String namespace) {
    return scope(owner, namespace, "{}");
  }

  private static SelectedOwnerIntakeSourceReadScope scope(
      DraftCommitBinding.Owner owner, String namespace, String revisionPayload) {
    UUID tenant = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    var selected =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                tenant,
                UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                1L,
                "tenant-key",
                2L,
                "tenant-key",
                "NEW_GAME_ROW"),
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            "base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    revisionPayload)),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "TEMPLATE_CONFIG",
                    "templates",
                    "TENANT",
                    tenant.toString(),
                    "0")));
    return new SelectedOwnerIntakeSourceReadScope(
        owner,
        namespace,
        UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff"),
        UUID.fromString("99999999-9999-4999-8999-999999999999"),
        selected.requestId(),
        UUID.fromString("12121212-1212-4212-8212-121212121212"),
        selected);
  }

  private static SelectedOwnerIntakeSourceReadScope scopeAtCanonicalLimit() {
    int lower = 0;
    int upper = SelectedOwnerIntakeSourceReadScope.MAX_BYTES;
    while (lower < upper) {
      int candidate = lower + (upper - lower + 1) / 2;
      try {
        scope(DraftCommitBinding.Owner.ENTITY_MANAGEMENT, NAMESPACE, "x".repeat(candidate));
        lower = candidate;
      } catch (IllegalArgumentException tooLarge) {
        upper = candidate - 1;
      }
    }
    return scope(DraftCommitBinding.Owner.ENTITY_MANAGEMENT, NAMESPACE, "x".repeat(lower));
  }

  private record TestIdentity(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestIdentity accountServer,
      TestIdentity wrongServer,
      TestIdentity gameDesignClient) {
    private static TestPki create(Path directory) throws Exception {
      Path caStore = directory.resolve("selected-owner-test-ca.p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=FireMUD selected-owner test CA",
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
      Path caFile = directory.resolve("selected-owner-test-ca.crt");
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
          issueIdentity(directory, caStore, caFile, "account-server", ACCOUNT_URI, true),
          issueIdentity(directory, caStore, caFile, "wrong-server", WRONG_SERVER_URI, true),
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
              directory.resolve("selected-owner-client-ca.crt"),
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
