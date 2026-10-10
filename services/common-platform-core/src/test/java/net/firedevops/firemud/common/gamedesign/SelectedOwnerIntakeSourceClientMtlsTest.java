package net.firedevops.firemud.common.gamedesign;

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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadEvidence;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.gamedesign.v1.GameDesignSelectedOwnerIntakeSourceServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.SelectedOwnerIntakeSourceRequest;
import net.firedevops.firemud.gamedesign.v1.SelectedOwnerIntakeSourceResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Synthetic loopback transport proof; it does not establish genuine Game Design source reads. */
class SelectedOwnerIntakeSourceClientMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String GAME_DESIGN_URI = "spiffe://firemud/ns/test/sa/game-design-service";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String OTHER_CLIENT_URI =
      "spiffe://firemud/ns/test/sa/entity-management-service";
  private static final String STORE_PASSWORD = "test-only-store-password";
  private static final UUID TENANT = id("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION = id("22222222-2222-4222-8222-222222222222");
  private static final UUID REQUEST_ID = id("33333333-3333-4333-8333-333333333333");
  private static final UUID COMMIT = id("44444444-4444-4444-8444-444444444444");
  private static final UUID REVISION = id("55555555-5555-4555-8555-555555555555");
  private static final UUID OPERATION = id("66666666-6666-4666-8666-666666666666");
  private static final UUID FENCE = id("77777777-7777-4777-8777-777777777777");
  private static final UUID INTAKE = id("88888888-8888-4888-8888-888888888888");
  private static final UUID ACTOR = id("99999999-9999-4999-8999-999999999999");
  private static final UUID GENESIS = id("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final List<String> FAMILIES =
      List.of("COMMAND", "REALM_POLICY", "ASSET", "GAMEPLAY_RULE", "BRANDING", "TEMPLATE_CONFIG");

  @TempDir static Path tempDirectory;

  private static TestPki pki;
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicInteger responseBytes = new AtomicInteger();
  private Server server;
  private boolean stall;
  private boolean substituteEcho;
  private boolean largeResponse;

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
  void pinsGameDesignServerAndRoundTripsBothOwnerScopesOverLoopbackMtls() throws Exception {
    start(pki.gameDesignServer());
    try (var client = newClient(pki.gameDesignClient())) {
      client.init();
      for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
        var request =
            request(owner, SelectedOwnerIntakeSourceTestFixtures.selectedRevisionPayload());
        assertThat(client.read(request).scope()).isEqualTo(request.scope());
      }
      assertThat(calls).hasValue(2);
    }

    server.shutdownNow();
    assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    server = null;
    start(pki.wrongServer());
    try (var client = newClient(pki.gameDesignClient())) {
      client.init();
      assertThatThrownBy(
              () ->
                  client.read(
                      request(
                          Owner.ENTITY_MANAGEMENT,
                          SelectedOwnerIntakeSourceTestFixtures.selectedRevisionPayload())))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              error ->
                  assertThat(Status.fromThrowable(error).getCode())
                      .isEqualTo(Status.Code.UNAUTHENTICATED));
      assertThat(calls).hasValue(2);
    }
  }

  @Test
  void transportsLargeCompleteContentWithinExplicitWireBudget() throws Exception {
    largeResponse = true;
    start(pki.gameDesignServer());
    var request =
        request(
            Owner.AUTOMATION_SCRIPTING,
            SelectedOwnerIntakeSourceTestFixtures.selectedRevisionPayload());
    try (var client = newClient(pki.gameDesignClient())) {
      client.init();
      var content = client.read(request);
      assertThat(content.scope()).isEqualTo(request.scope());
      assertThat(content.snapshotBytes("COMMAND").length).isGreaterThan(6_000_000);
      assertThat(responseBytes.get())
          .isGreaterThan(4 * 1024 * 1024)
          .isLessThanOrEqualTo(SelectedOwnerIntakeSourceProtoCodec.MAX_WIRE_BYTES);
      assertThat(calls).hasValue(1);
    }
  }

  @Test
  void requiresExactClientCertificateAndRejectsExpiredServerOrClientCertificates()
      throws Exception {
    start(pki.gameDesignServer());
    try (var client = newClient(pki.wrongClient())) {
      client.init();
      assertThatThrownBy(
              () ->
                  client.read(
                      request(
                          Owner.ENTITY_MANAGEMENT,
                          SelectedOwnerIntakeSourceTestFixtures.selectedRevisionPayload())))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              error ->
                  assertThat(Status.fromThrowable(error).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));
      assertThat(calls).hasValue(1);
    }

    server.shutdownNow();
    assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    server = null;
    start(pki.expiredGameDesignServer());
    try (var client = newClient(pki.gameDesignClient())) {
      client.init();
      assertThatThrownBy(
              () ->
                  client.read(
                      request(
                          Owner.ENTITY_MANAGEMENT,
                          SelectedOwnerIntakeSourceTestFixtures.selectedRevisionPayload())))
          .isInstanceOf(StatusRuntimeException.class);
      assertThat(calls).hasValue(1);
    }

    server.shutdownNow();
    assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    server = null;
    start(pki.gameDesignServer());
    try (var client = newClient(pki.expiredGameDesignClient())) {
      client.init();
      assertThatThrownBy(
              () ->
                  client.read(
                      request(
                          Owner.ENTITY_MANAGEMENT,
                          SelectedOwnerIntakeSourceTestFixtures.selectedRevisionPayload())))
          .isInstanceOf(StatusRuntimeException.class);
      assertThat(calls).hasValue(1);
    }
  }

  @Test
  void rejectsOtherNamespaceAmbientSqlSynchronizationAndSubstitutedEcho() throws Exception {
    start(pki.gameDesignServer());
    try (var client = newClient(pki.gameDesignClient())) {
      client.init();
      var otherNamespace =
          request(
              Owner.ENTITY_MANAGEMENT,
              SelectedOwnerIntakeSourceTestFixtures.selectedRevisionPayload(),
              "other");
      assertThatThrownBy(() -> client.read(otherNamespace))
          .isInstanceOf(IllegalArgumentException.class);

      var request =
          request(
              Owner.ENTITY_MANAGEMENT,
              SelectedOwnerIntakeSourceTestFixtures.selectedRevisionPayload());
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
      assertThat(calls).hasValue(0);

      substituteEcho = true;
      assertThatThrownBy(() -> client.read(request)).isInstanceOf(IllegalStateException.class);
      assertThat(calls).hasValue(1);
    }
  }

  @Test
  void boundsUnavailableReadByFiveSecondDeadline() throws Exception {
    stall = true;
    start(pki.gameDesignServer());
    try (var client = newClient(pki.gameDesignClient())) {
      client.init();
      assertThatThrownBy(
              () ->
                  client.read(
                      request(
                          Owner.AUTOMATION_SCRIPTING,
                          SelectedOwnerIntakeSourceTestFixtures.selectedRevisionPayload())))
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
                new GrpcSelectedOwnerIntakeSourceClient(
                    endpoints, plaintext, new GrpcChannelFactory(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);

    var classpath = new CommonGrpcClientProperties();
    classpath.setCertChain("classpath:client.crt");
    classpath.setPrivateKey("/tmp/client.key");
    classpath.setCaCert("/tmp/ca.crt");
    assertThatThrownBy(
            () ->
                new GrpcSelectedOwnerIntakeSourceClient(
                    endpoints, classpath, new GrpcChannelFactory(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private void start(TestIdentity identity) throws Exception {
    var service =
        new GameDesignSelectedOwnerIntakeSourceServiceGrpc
            .GameDesignSelectedOwnerIntakeSourceServiceImplBase() {
          @Override
          public void readSelectedSource(
              SelectedOwnerIntakeSourceRequest wire,
              StreamObserver<SelectedOwnerIntakeSourceResponse> observer) {
            calls.incrementAndGet();
            var peer = GrpcPeerIdentity.current();
            if (peer == null || !GAME_DESIGN_URI.equals(peer.uri())) {
              observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
              return;
            }
            if (stall) return;
            var request = SelectedOwnerIntakeSourceProtoCodec.fromRequest(wire);
            var content = content(request.scope(), largeResponse ? 6_500_000 : 0);
            var response = SelectedOwnerIntakeSourceProtoCodec.toResponse(request, content);
            responseBytes.set(response.getSerializedSize());
            if (substituteEcho) {
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
            .maxInboundMessageSize(SelectedOwnerIntakeSourceProtoCodec.MAX_WIRE_BYTES)
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

  private GrpcSelectedOwnerIntakeSourceClient newClient(TestIdentity identity) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("localhost:" + server.getPort());
    return new GrpcSelectedOwnerIntakeSourceClient(
        endpoints,
        pki.clientProperties(tempDirectory, identity),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static SelectedOwnerIntakeSourceReadEvidence.Request request(
      Owner owner, String payload) {
    return request(owner, payload, NAMESPACE);
  }

  private static SelectedOwnerIntakeSourceReadEvidence.Request request(
      Owner owner, String payload, String namespace) {
    return SelectedOwnerIntakeSourceReadEvidence.Request.create(
        namespace, scope(owner, payload, namespace));
  }

  private static SelectedOwnerIntakeSourceReadScope scope(
      Owner owner, String payload, String namespace) {
    DraftCommitBinding selected = binding(COMMIT, payload);
    return new SelectedOwnerIntakeSourceReadScope(
        owner, namespace, OPERATION, FENCE, INTAKE, ACTOR, selected);
  }

  private static SelectedOwnerIntakeSourceContent content(
      SelectedOwnerIntakeSourceReadScope scope, int largeCommandMarkerSize) {
    var selected = scope.selected();
    var gameplay =
        new GameplayRuleSelectedSource(
            GameplayRuleManifest.canonical(
                Map.of(
                    "schema", "game-design-gameplay-rule-source-snapshot/v1",
                    "bindingJson", selected.canonicalJson(),
                    "bindingDigest", selected.digest(),
                    "sourceEpoch", "0",
                    "inheritedCommitId", "",
                    "genesisReceiptId", GENESIS.toString(),
                    "manifestJson", GameplayRuleManifest.explicitEmpty().canonicalJson(),
                    "entries", List.of())));
    var template = new TemplateConfigSourceSnapshot(selected, "0", null, GENESIS, List.of());
    var out = new ByteArrayOutputStream();
    frame(out, SelectedOwnerIntakeSourceContent.DOMAIN);
    frame(out, scope.canonicalBytes());
    frame(out, scope.digest());
    for (String family : FAMILIES) {
      byte[] snapshot =
          switch (family) {
            case "GAMEPLAY_RULE" -> gameplay.canonicalBytes();
            case "TEMPLATE_CONFIG" -> template.canonicalBytes();
            default ->
                SelectedOwnerIntakeSourceTestFixtures.snapshot(
                    family, selected, family.equals("COMMAND") ? largeCommandMarkerSize : 0);
          };
      frame(out, family);
      frame(out, snapshot);
      frame(out, DraftAuthorizationFenceBinding.digest(snapshot));
    }
    byte[] encoded = out.toByteArray();
    return SelectedOwnerIntakeSourceContent.fromStored(
        encoded, scope, DraftAuthorizationFenceBinding.digest(encoded));
  }

  private static DraftCommitBinding binding(UUID commitId, String payload) {
    return DraftCommitBinding.create(
        new DraftCommitBinding.TargetProof(
            TENANT, VERSION, 23L, "tenant", 42L, "tenant", "NEW_GAME_ROW"),
        REQUEST_ID,
        commitId,
        "base-commit-0",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0", REVISION, Owner.GAME_DESIGN_CONTROL_PLANE, payload)),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                "TEMPLATE_CONFIG",
                VERSION.toString(),
                "TEMPLATE_CONFIG",
                "ALL",
                "0")));
  }

  private static void frame(ByteArrayOutputStream out, String value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static void frame(ByteArrayOutputStream out, byte[] value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static UUID id(String value) {
    return UUID.fromString(value);
  }

  private record TestIdentity(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestIdentity gameDesignServer,
      TestIdentity wrongServer,
      TestIdentity expiredGameDesignServer,
      TestIdentity gameDesignClient,
      TestIdentity wrongClient,
      TestIdentity expiredGameDesignClient) {
    private static TestPki create(Path directory) throws Exception {
      Path caStore = directory.resolve("selected-content-test-ca.p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=FireMUD selected-content test CA",
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
      Path caFile = directory.resolve("selected-content-test-ca.crt");
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
          issueIdentity(
              directory, caStore, caFile, "game-design-server", GAME_DESIGN_URI, true, false),
          issueIdentity(directory, caStore, caFile, "wrong-server", ACCOUNT_URI, true, false),
          issueIdentity(directory, caStore, caFile, "expired-server", GAME_DESIGN_URI, true, true),
          issueIdentity(
              directory, caStore, caFile, "game-design-client", GAME_DESIGN_URI, false, false),
          issueIdentity(directory, caStore, caFile, "wrong-client", OTHER_CLIENT_URI, false, false),
          issueIdentity(
              directory, caStore, caFile, "expired-client", GAME_DESIGN_URI, false, true));
    }

    private CommonGrpcClientProperties clientProperties(Path directory, TestIdentity identity)
        throws Exception {
      Path certificate =
          writePem(
              directory.resolve("selected-content-client.crt"),
              "CERTIFICATE",
              identity.certificate().getEncoded());
      Path privateKey =
          writePem(
              directory.resolve("selected-content-client.key"),
              "PRIVATE KEY",
              identity.privateKey().getEncoded());
      Path caFile =
          writePem(
              directory.resolve("selected-content-client-ca.crt"),
              "CERTIFICATE",
              caCertificate.getEncoded());
      var properties = new CommonGrpcClientProperties();
      properties.setCertChain(certificate.toString());
      properties.setPrivateKey(privateKey.toString());
      properties.setCaCert(caFile.toString());
      return properties;
    }

    private static TestIdentity issueIdentity(
        Path directory,
        Path caStore,
        Path caFile,
        String alias,
        String workloadUri,
        boolean server,
        boolean expired)
        throws Exception {
      Path store = directory.resolve(alias + ".p12");
      Path request = directory.resolve(alias + ".csr");
      Path certificate = directory.resolve(alias + ".crt");
      String san = "URI:" + workloadUri + ",DNS:localhost,IP:127.0.0.1";
      var startDate = expired ? List.of("-startdate", "2000/01/01 00:00:00") : List.<String>of();
      var validity = expired ? "1" : "30";
      var generate =
          new ArrayList<String>(
              List.of(
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
                  validity,
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
                  STORE_PASSWORD));
      runKeytool(generate.toArray(String[]::new));
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
      var sign =
          new ArrayList<String>(
              List.of(
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
                  validity,
                  "-rfc",
                  "-ext",
                  "BC=ca:false",
                  "-ext",
                  "KU=digitalSignature,keyEncipherment",
                  "-ext",
                  "EKU=" + (server ? "serverAuth" : "clientAuth"),
                  "-ext",
                  "SAN=" + san));
      sign.addAll(startDate);
      runKeytool(sign.toArray(String[]::new));
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
