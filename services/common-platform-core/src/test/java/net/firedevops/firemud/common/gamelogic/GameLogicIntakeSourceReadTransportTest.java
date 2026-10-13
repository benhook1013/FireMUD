package net.firedevops.firemud.common.gamelogic;

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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.account.v1.AccountGameLogicIntakeSourceReadServiceGrpc;
import net.firedevops.firemud.account.v1.GameLogicIntakeSourcePermissionRequest;
import net.firedevops.firemud.account.v1.GameLogicIntakeSourcePermissionResponse;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
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

/** Actual loopback mTLS transport fixtures; these bytes do not establish creator provenance. */
class GameLogicIntakeSourceReadTransportTest {
  private static final String NAMESPACE = "test";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String WRONG_SERVER_URI = "spiffe://firemud/ns/test/sa/game-logic-service";
  private static final String GD_URI = "spiffe://firemud/ns/test/sa/game-design-service";
  private static final String STORE_PASSWORD = "test-only-store-password";
  @TempDir static Path tempDirectory;
  private static TestPki pki;
  private Server server;
  private GrpcGameLogicIntakeSourceReadClient client;
  private final AtomicInteger calls = new AtomicInteger();
  private boolean stall;
  private boolean substitute;

  @BeforeAll
  static void certificates() throws Exception {
    pki = TestPki.create(tempDirectory);
  }

  @AfterEach
  void close() throws Exception {
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
  void roundTripsBothClosedProofsAndRejectsChangedEchoAndUnknownOrAbsentProof() {
    for (var request : requests()) {
      var wire = GameLogicIntakeSourceReadProtoCodec.toRequest(request);
      assertThat(GameLogicIntakeSourceReadProtoCodec.fromRequest(wire)).isEqualTo(request);
      var response = GameLogicIntakeSourceReadProtoCodec.toResponse(request);
      assertThat(GameLogicIntakeSourceReadProtoCodec.fromResponse(request, response).request())
          .isEqualTo(request);
      assertThatThrownBy(
              () ->
                  GameLogicIntakeSourceReadProtoCodec.fromResponse(
                      request,
                      response.toBuilder()
                          .setRequest(
                              wire.toBuilder().setReadRequestId(UUID.randomUUID().toString()))
                          .build()))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  GameLogicIntakeSourceReadProtoCodec.fromResponse(
                      request, response.toBuilder().setPermitted(false).build()))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  GameLogicIntakeSourceReadProtoCodec.fromRequest(
                      wire.toBuilder().clearAuthorizationProof().build()))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  GameLogicIntakeSourceReadProtoCodec.fromRequest(
                      wire.toBuilder()
                          .setAuthorizationProofDigest("sha256:" + "0".repeat(64))
                          .build()))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  GameLogicIntakeSourceReadProtoCodec.fromRequest(
                      wire.toBuilder().setPurpose("PUBLICATION").build()))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  GameLogicIntakeSourceReadProtoCodec.fromRequest(
                      wire.toBuilder()
                          .setUnknownFields(
                              com.google.protobuf.UnknownFieldSet.newBuilder()
                                  .addField(
                                      99,
                                      com.google.protobuf.UnknownFieldSet.Field.newBuilder()
                                          .addVarint(1)
                                          .build())
                                  .build())
                          .build()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void pinsAccountServerAndAuthenticatedGdClientForBothMethods() throws Exception {
    start(pki.gameLogicServer());
    client = newClient(pki.accountClient());
    client.init();
    for (var request : requests()) assertThat(client.read(request).request()).isEqualTo(request);
    assertThat(calls).hasValue(2);
    close();
    start(pki.wrongServer());
    client = newClient(pki.accountClient());
    client.init();
    assertThatThrownBy(() -> client.read(requests().getFirst()))
        .isInstanceOf(StatusRuntimeException.class);
    assertThat(calls).hasValue(2);
  }

  @Test
  void rejectsNamespaceAmbientSqlMissingTlsAndSubstitutedResponse() throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    var plaintext = new CommonGrpcClientProperties();
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new GrpcGameLogicIntakeSourceReadClient(
                    endpoints, plaintext, new GrpcChannelFactory(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GrpcGameLogicIntakeSourceReadClient(
                    endpoints,
                    new CommonGrpcClientProperties(),
                    new GrpcChannelFactory(),
                    NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
    start(pki.gameLogicServer());
    client = newClient(pki.accountClient());
    client.init();
    var original = requests().getFirst();
    var otherNamespace =
        new GameLogicIntakeSourceReadEvidence.Request(
            1,
            "other",
            UUID.randomUUID(),
            "spiffe://firemud/ns/other/sa/game-logic-service",
            original.purpose(),
            requests().getLast().proof());
    assertThatThrownBy(() -> client.read(otherNamespace))
        .isInstanceOf(IllegalArgumentException.class);
    org.springframework.transaction.support.TransactionSynchronizationManager
        .setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> client.read(original)).isInstanceOf(IllegalStateException.class);
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .setActualTransactionActive(false);
    }
    assertThat(calls).hasValue(0);
    substitute = true;
    assertThatThrownBy(() -> client.read(original)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void boundsUnavailableReadByDeadline() throws Exception {
    stall = true;
    start(pki.gameLogicServer());
    client = newClient(pki.accountClient());
    client.init();
    assertThatThrownBy(() -> client.read(requests().getFirst()))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            error ->
                assertThat(Status.fromThrowable(error).getCode())
                    .isEqualTo(Status.Code.DEADLINE_EXCEEDED));
  }

  @Test
  void rejectsOtherAuthenticatedClientWorkload() throws Exception {
    start(pki.gameLogicServer());
    var wrongClient =
        TestPki.issueIdentity(
            tempDirectory,
            tempDirectory.resolve("terminal-test-ca.p12"),
            tempDirectory.resolve("terminal-test-ca.crt"),
            "wrong-source-client",
            ACCOUNT_URI,
            false);
    client = newClient(wrongClient);
    client.init();
    assertThatThrownBy(() -> client.read(requests().getFirst()))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            error ->
                assertThat(Status.fromThrowable(error).getCode())
                    .isEqualTo(Status.Code.PERMISSION_DENIED));
    assertThat(calls).hasValue(0);
  }

  @Test
  void transportsResponsesOverFourMiBWithinBothExplicitWireBudgets() throws Exception {
    // Deliberately synthetic owner evidence exercises envelope overhead, not creator authority.
    var original =
        ((GameplayRuleSourceReadEvidence.Finalized) requests().getLast().proof()).authorization();
    int padding = 4 * 1024 * 1024 - 16 - original.canonicalBytes().length + 1;
    var large =
        new GameLogicIntakeAuthorizationBinding(
            original.operationId(),
            original.fenceId(),
            original.intakeRequestId(),
            original.actorAccountId(),
            original.source(),
            List.of(
                new DraftAuthorizationFenceBinding.SourceEvidence(
                    DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                    original.actorAccountId().toString(),
                    "1",
                    "1",
                    null,
                    null,
                    new byte[padding])));
    assertThat(large.canonicalBytes().length).isEqualTo(4 * 1024 * 1024 - 16);
    var permission =
        GameLogicIntakeSourceReadEvidence.Request.create(
            NAMESPACE,
            "spiffe://firemud/ns/test/sa/game-logic-service",
            GameLogicIntakeSourceReadScope.PURPOSE,
            new GameplayRuleSourceReadEvidence.Finalized(large));
    assertThat(GameLogicIntakeSourceReadProtoCodec.toResponse(permission).getSerializedSize())
        .isGreaterThan(4 * 1024 * 1024)
        .isLessThanOrEqualTo(GameLogicIntakeSourceReadProtoCodec.MAX_WIRE_BYTES);
    start(pki.gameLogicServer());
    client = newClient(pki.accountClient());
    client.init();
    assertThat(client.read(permission).request().proof().canonicalBytes())
        .containsExactly(large.canonicalBytes());
    close();

    var sourceRequest = GameplayRuleSourceReadEvidence.Request.forFinalizedIntake(NAMESPACE, large);
    var sourceEvidence = new GameplayRuleSourceReadEvidence(sourceRequest, large.source());
    assertThat(GameplayRuleSourceReadGrpcCodec.toResponse(sourceEvidence).getSerializedSize())
        .isGreaterThan(4 * 1024 * 1024)
        .isLessThanOrEqualTo(GameplayRuleSourceReadGrpcCodec.MAX_WIRE_BYTES);
    var gdServer =
        TestPki.issueIdentity(
            tempDirectory,
            tempDirectory.resolve("terminal-test-ca.p12"),
            tempDirectory.resolve("terminal-test-ca.crt"),
            "source-gd-server",
            GD_URI,
            true);
    var glClient =
        TestPki.issueIdentity(
            tempDirectory,
            tempDirectory.resolve("terminal-test-ca.p12"),
            tempDirectory.resolve("terminal-test-ca.crt"),
            "source-gl-client",
            "spiffe://firemud/ns/test/sa/game-logic-service",
            false);
    var sourceService =
        new net.firedevops.firemud.gamedesign.v1.GameDesignGameplayRuleSourceReadServiceGrpc
            .GameDesignGameplayRuleSourceReadServiceImplBase() {
          @Override
          public void readSelectedGameplayRuleSource(
              net.firedevops.firemud.gamedesign.v1.ReadSelectedGameplayRuleSourceRequest wire,
              StreamObserver<
                      net.firedevops.firemud.gamedesign.v1.ReadSelectedGameplayRuleSourceResponse>
                  observer) {
            assertThat(GrpcPeerIdentity.current().uri())
                .isEqualTo("spiffe://firemud/ns/test/sa/game-logic-service");
            var request = GameplayRuleSourceReadGrpcCodec.fromRequest(wire);
            observer.onNext(
                GameplayRuleSourceReadGrpcCodec.toResponse(
                    new GameplayRuleSourceReadEvidence(request, large.source())));
            observer.onCompleted();
          }
        };
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .maxInboundMessageSize(GameplayRuleSourceReadGrpcCodec.MAX_WIRE_BYTES)
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(gdServer.privateKey(), gdServer.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(sourceService, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("localhost:" + server.getPort());
    try (var sourceClient =
        new GameplayRuleSourceReadClient(
            endpoints,
            pki.clientProperties(tempDirectory, glClient),
            new GrpcChannelFactory(),
            NAMESPACE)) {
      sourceClient.init();
      var result = sourceClient.read(sourceRequest);
      assertThat(result.request().proof().canonicalBytes()).containsExactly(large.canonicalBytes());
      assertThat(result.source().canonicalBytes()).containsExactly(large.source().canonicalBytes());
    }
  }

  @Test
  void rejectsEnvelopesOverExplicitWireBudgetBeforeCanonicalDecode() {
    var request = requests().getFirst();
    var oversizedAccount =
        GameLogicIntakeSourceReadProtoCodec.toRequest(request).toBuilder()
            .setPurpose("x".repeat(GameLogicIntakeSourceReadProtoCodec.MAX_WIRE_BYTES))
            .build();
    assertThatThrownBy(() -> GameLogicIntakeSourceReadProtoCodec.fromRequest(oversizedAccount))
        .isInstanceOf(IllegalArgumentException.class);
    var sourceRequest =
        GameplayRuleSourceReadEvidence.Request.forAccountSourceScope(
            NAMESPACE, ((GameplayRuleSourceReadEvidence.Preliminary) request.proof()).scope());
    var oversizedGd =
        GameplayRuleSourceReadGrpcCodec.toRequest(sourceRequest).toBuilder()
            .setPurpose("x".repeat(GameplayRuleSourceReadGrpcCodec.MAX_WIRE_BYTES))
            .build();
    assertThatThrownBy(() -> GameplayRuleSourceReadGrpcCodec.fromRequest(oversizedGd))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private void start(TestIdentity identity) throws Exception {
    var service =
        new AccountGameLogicIntakeSourceReadServiceGrpc
            .AccountGameLogicIntakeSourceReadServiceImplBase() {
          @Override
          public void readSourceScope(
              GameLogicIntakeSourcePermissionRequest wire,
              StreamObserver<GameLogicIntakeSourcePermissionResponse> observer) {
            respond(wire, observer, true);
          }

          @Override
          public void readFinalizedIntake(
              GameLogicIntakeSourcePermissionRequest wire,
              StreamObserver<GameLogicIntakeSourcePermissionResponse> observer) {
            respond(wire, observer, false);
          }

          private void respond(
              GameLogicIntakeSourcePermissionRequest wire,
              StreamObserver<GameLogicIntakeSourcePermissionResponse> observer,
              boolean preliminary) {
            if (!GD_URI.equals(GrpcPeerIdentity.current().uri())) {
              observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
              return;
            }
            calls.incrementAndGet();
            assertThat(io.grpc.Context.current().getDeadline()).isNotNull();
            var request = GameLogicIntakeSourceReadProtoCodec.fromRequest(wire);
            assertThat(request.proof() instanceof GameplayRuleSourceReadEvidence.Preliminary)
                .isEqualTo(preliminary);
            if (stall) return;
            var response = GameLogicIntakeSourceReadProtoCodec.toResponse(request);
            if (substitute)
              response =
                  response.toBuilder()
                      .setRequest(wire.toBuilder().setReadRequestId(UUID.randomUUID().toString()))
                      .build();
            observer.onNext(response);
            observer.onCompleted();
          }
        };
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .maxInboundMessageSize(GameLogicIntakeSourceReadProtoCodec.MAX_WIRE_BYTES)
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

  private GrpcGameLogicIntakeSourceReadClient newClient(TestIdentity identity) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + server.getPort());
    return new GrpcGameLogicIntakeSourceReadClient(
        endpoints,
        pki.clientProperties(tempDirectory, identity),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static List<GameLogicIntakeSourceReadEvidence.Request> requests() {
    var target =
        new DraftCommitBinding.TargetProof(
            UUID.randomUUID(), UUID.randomUUID(), 1, "private", 2, "private", "NEW_GAME_ROW");
    var binding =
        DraftCommitBinding.create(
            target,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "genesis",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "{}")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "GAMEPLAY_RULE_SET",
                    target.canonicalVersionId().toString(),
                    "GAMEPLAY_RULE_SET",
                    "effective",
                    "0")));
    var source =
        new GameplayRuleSelectedSource(
            GameplayRuleManifest.canonical(
                Map.of(
                    "schema",
                    "game-design-gameplay-rule-source-snapshot/v1",
                    "bindingJson",
                    binding.canonicalJson(),
                    "bindingDigest",
                    binding.digest(),
                    "sourceEpoch",
                    "1",
                    "inheritedCommitId",
                    "",
                    "genesisReceiptId",
                    UUID.randomUUID().toString(),
                    "manifestJson",
                    GameplayRuleManifest.explicitEmpty().canonicalJson(),
                    "entries",
                    List.of())));
    var actor = UUID.randomUUID();
    var scope =
        new GameLogicIntakeSourceReadScope(
            NAMESPACE,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            actor,
            binding,
            ACCOUNT_URI,
            GameLogicIntakeSourceReadScope.PURPOSE);
    var auth =
        new GameLogicIntakeAuthorizationBinding(
            scope.operationId(),
            scope.fenceId(),
            scope.intakeRequestId(),
            actor,
            source,
            List.of(
                new DraftAuthorizationFenceBinding.SourceEvidence(
                    DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                    actor.toString(),
                    "1",
                    "1",
                    null,
                    null,
                    new byte[] {1})));
    return List.of(
        GameLogicIntakeSourceReadEvidence.Request.create(
            NAMESPACE,
            ACCOUNT_URI,
            scope.purpose(),
            new GameplayRuleSourceReadEvidence.Preliminary(scope)),
        GameLogicIntakeSourceReadEvidence.Request.create(
            NAMESPACE,
            "spiffe://firemud/ns/test/sa/game-logic-service",
            scope.purpose(),
            new GameplayRuleSourceReadEvidence.Finalized(auth)));
  }

  record TestIdentity(PrivateKey privateKey, X509Certificate certificate) {}

  record TestPki(
      X509Certificate caCertificate,
      TestIdentity gameLogicServer,
      TestIdentity wrongServer,
      TestIdentity accountClient) {
    static TestPki create(Path directory) throws Exception {
      Path caStore = directory.resolve("terminal-test-ca.p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=FireMUD terminal-read test CA",
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
      Path caFile = directory.resolve("terminal-test-ca.crt");
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
          issueIdentity(directory, caStore, caFile, "game-logic-server", ACCOUNT_URI, true),
          issueIdentity(directory, caStore, caFile, "wrong-server", WRONG_SERVER_URI, true),
          issueIdentity(directory, caStore, caFile, "account-client", GD_URI, false));
    }

    CommonGrpcClientProperties clientProperties(Path directory, TestIdentity identity)
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
              directory.resolve("terminal-client-ca.crt"),
              "CERTIFICATE",
              caCertificate.getEncoded());
      var properties = new CommonGrpcClientProperties();
      properties.setCertChain(certificate.toString());
      properties.setPrivateKey(privateKey.toString());
      properties.setCaCert(caFile.toString());
      return properties;
    }

    static TestIdentity issueIdentity(
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
