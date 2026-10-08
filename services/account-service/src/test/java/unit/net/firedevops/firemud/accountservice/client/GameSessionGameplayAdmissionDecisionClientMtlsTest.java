package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionProvisionalDecision;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayAdmissionDecisionStatus;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayAdmissionDecisionRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayAdmissionDecisionResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Physical socket proof against a transport-only double, not Game Session owner/database proof. */
class GameSessionGameplayAdmissionDecisionClientMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String GAME_SESSION_URI = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String OTHER_NAMESPACE_URI =
      "spiffe://firemud/ns/other-test/sa/game-session-service";
  private static final String WRONG_WORKLOAD_URI =
      "spiffe://firemud/ns/test/sa/world-management-service";
  private static final String OTHER_ACCOUNT_URI =
      "spiffe://firemud/ns/other-test/sa/account-service";
  private static final String STORE_PASSWORD = "test-only-store-password";
  private static final UUID BINDING_DECISION_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");

  @TempDir static Path tempDirectory;
  private static TestPki pki;
  private Server server;
  private GameSessionGameplayAdmissionDecisionClient client;
  private AtomicInteger headers;
  private AtomicInteger ownerResults;
  private UnaryOperator<GetCanonicalGameplayAdmissionDecisionResponse> responseMutation =
      UnaryOperator.identity();
  private Status serviceFailure;

  @BeforeAll
  static void createTrustedCertificates() throws Exception {
    pki = TestPki.create(tempDirectory);
  }

  @AfterEach
  void stopTransport() throws Exception {
    closeTransport();
  }

  @Test
  void exactAuthenticatedReadReturnsOriginalEvidenceAndUnchangedRetry() throws Exception {
    startServer(pki.gameSessionServer());
    client = newClient(server, pki.accountClient());
    client.init();

    AccountGameplayAdmissionLeaseEvidence original = evidence();
    var first = client.read(BINDING_DECISION_ID, original);
    var retry = client.read(BINDING_DECISION_ID, original);

    assertThat(first)
        .isEqualTo(new AccountGameplayAdmissionProvisionalDecision(BINDING_DECISION_ID, original));
    assertThat(retry).isEqualTo(first);
    assertThat(ownerResults).hasValue(2);
    assertThat(headers).hasValue(2);
  }

  @Test
  void trustedWrongServerWorkloadOrNamespaceReleasesNoHeadersOrBody() throws Exception {
    AccountGameplayAdmissionLeaseEvidence original = evidence();
    for (TestIdentity identity : List.of(pki.wrongWorkloadServer(), pki.otherNamespaceServer())) {
      startServer(identity);
      client = newClient(server, pki.accountClient());
      client.init();

      assertThatThrownBy(() -> client.read(BINDING_DECISION_ID, original))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              exception ->
                  assertThat(Status.fromThrowable(exception).getCode())
                      .isEqualTo(Status.Code.UNAUTHENTICATED));
      assertThat(headers).hasValue(0);
      assertThat(ownerResults).hasValue(0);
      closeTransport();
    }
  }

  @Test
  void sameCaWrongAccountWorkloadOrNamespaceCannotReachOwnerResult() throws Exception {
    AccountGameplayAdmissionLeaseEvidence original = evidence();
    for (TestIdentity identity : List.of(pki.wrongAccountClient(), pki.otherNamespaceAccount())) {
      startServer(pki.gameSessionServer());
      client = newClient(server, identity);
      client.init();

      assertThatThrownBy(() -> client.read(BINDING_DECISION_ID, original))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              exception ->
                  assertThat(Status.fromThrowable(exception).getCode())
                      .isEqualTo(Status.Code.UNAUTHENTICATED));
      assertThat(headers).hasValue(1);
      assertThat(ownerResults).hasValue(0);
      closeTransport();
    }
  }

  @Test
  void alteredOrMalformedAuthenticatedResponseIsRejected() throws Exception {
    List<UnaryOperator<GetCanonicalGameplayAdmissionDecisionResponse>> mutations =
        List.of(
            response -> response.toBuilder().setSchemaVersion(2).build(),
            response -> response.toBuilder().setBindingDecisionUuid("not-a-uuid").build(),
            response ->
                response.toBuilder().setLeaseEvidence(ByteString.copyFromUtf8("{}")).build(),
            response -> response.toBuilder().setLeaseEvidenceSha256("0".repeat(64)).build(),
            response ->
                response.toBuilder()
                    .setStatus(
                        CanonicalGameplayAdmissionDecisionStatus
                            .CANONICAL_GAMEPLAY_ADMISSION_DECISION_STATUS_UNSPECIFIED)
                    .build(),
            response ->
                response.toBuilder()
                    .setUnknownFields(
                        UnknownFieldSet.newBuilder()
                            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                            .build())
                    .build());

    for (UnaryOperator<GetCanonicalGameplayAdmissionDecisionResponse> mutation : mutations) {
      startServer(pki.gameSessionServer());
      responseMutation = mutation;
      client = newClient(server, pki.accountClient());
      client.init();
      assertThatThrownBy(() -> client.read(BINDING_DECISION_ID, evidence()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("response is invalid");
      assertThat(ownerResults).hasValue(1);
      closeTransport();
    }
  }

  @Test
  void nonOkOwnerTransportStatusIsPropagated() throws Exception {
    startServer(pki.gameSessionServer());
    serviceFailure = Status.UNAVAILABLE.withDescription("test owner unavailable");
    client = newClient(server, pki.accountClient());
    client.init();

    assertThatThrownBy(() -> client.read(BINDING_DECISION_ID, evidence()))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            exception ->
                assertThat(Status.fromThrowable(exception).getCode())
                    .isEqualTo(Status.Code.UNAVAILABLE));
    assertThat(ownerResults).hasValue(1);
  }

  private void startServer(TestIdentity identity) throws Exception {
    closeTransport();
    headers = new AtomicInteger();
    ownerResults = new AtomicInteger();
    responseMutation = UnaryOperator.identity();
    serviceFailure = null;
    var service =
        new GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceImplBase() {
          @Override
          public void getCanonicalGameplayAdmissionDecision(
              GetCanonicalGameplayAdmissionDecisionRequest request,
              StreamObserver<GetCanonicalGameplayAdmissionDecisionResponse> observer) {
            ownerResults.incrementAndGet();
            assertThat(GrpcPeerIdentity.current().uri()).isEqualTo(ACCOUNT_URI);
            AccountGameplayAdmissionLeaseEvidence original = evidence();
            byte[] exactOriginalBytes = original.canonicalJson().getBytes(StandardCharsets.UTF_8);
            assertThat(request.getSchemaVersion()).isEqualTo(1);
            assertThat(request.getBindingDecisionUuid()).isEqualTo(BINDING_DECISION_ID.toString());
            assertThat(request.getLeaseEvidence().toByteArray()).isEqualTo(exactOriginalBytes);
            if (serviceFailure != null) {
              observer.onError(serviceFailure.asRuntimeException());
              return;
            }
            var response =
                GetCanonicalGameplayAdmissionDecisionResponse.newBuilder()
                    .setSchemaVersion(1)
                    .setBindingDecisionUuid(request.getBindingDecisionUuid())
                    .setLeaseEvidence(request.getLeaseEvidence())
                    .setLeaseEvidenceSha256(original.sha256())
                    .setStatus(
                        CanonicalGameplayAdmissionDecisionStatus
                            .CANONICAL_GAMEPLAY_ADMISSION_DECISION_STATUS_PROVISIONAL)
                    .build();
            observer.onNext(responseMutation.apply(response));
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
    ServerInterceptor exactAccountCallerGuard =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call,
              Metadata metadata,
              ServerCallHandler<ReqT, RespT> next) {
            GrpcPeerIdentity peer = GrpcPeerIdentity.current();
            if (peer == null || !ACCOUNT_URI.equals(peer.uri())) {
              call.close(
                  Status.UNAUTHENTICATED.withDescription("Exact Account workload required"),
                  new Metadata());
              return new ServerCall.Listener<>() {};
            }
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
                    service,
                    exactAccountCallerGuard,
                    countHeaders,
                    new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private GameSessionGameplayAdmissionDecisionClient newClient(Server target, TestIdentity caller)
      throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameSessionService("localhost:" + target.getPort());
    return new GameSessionGameplayAdmissionDecisionClient(
        endpoints,
        pki.clientProperties(tempDirectory, caller),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private void closeTransport() throws Exception {
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

  private static AccountGameplayAdmissionLeaseEvidence evidence() {
    String account = "11111111-1111-4111-8111-111111111111";
    String tenant = "22222222-2222-4222-8222-222222222222";
    String other = "33333333-3333-4333-8333-333333333333";
    String high = "9007199254740993";
    String prefix = "account:auth-authority:v1:";
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", AccountGameplayAdmissionLeaseEvidence.SCHEMA);
    value.put("schemaVersion", "1");
    value.put("mode", "PUBLIC_PRODUCTION");
    value.put("targetNamespace", NAMESPACE);
    value.put("callerWorkload", "spiffe://firemud/ns/test/sa/game-session-service");
    value.put("requestId", other);
    value.put("leaseId", tenant);
    value.put("leaseFence", high);
    value.put("leaseKind", "NEW_BINDING");
    Map<String, Object> scope = new LinkedHashMap<>();
    for (String key :
        List.of(
            "realmId",
            "playableStateNamespaceId",
            "gameInstanceId",
            "characterId",
            "sessionId",
            "regionId")) {
      scope.put(key, other);
    }
    scope.put("accountId", account);
    scope.put("tenantId", tenant);
    scope.put("worldSlug", "fabricated-test-world");
    scope.put("realmSlug", "fabricated-test-realm");
    scope.put("playableStateScope", "SHARED");
    for (String key :
        List.of("bindingGeneration", "catalogRevision", "pointerVersion", "regionEpoch")) {
      scope.put(key, high);
    }
    value.put("bindingScope", scope);
    value.put(
        "authorityTuple",
        new LinkedHashMap<>(
            Map.of(
                "issuerAuthGeneration", "1",
                "accountAuthorityGeneration", "1",
                "tenantAuthorityGeneration", Map.of(tenant, high),
                "membershipAuthorityGeneration", Map.of(tenant, high),
                "privateRealmGrantVersions", List.of())));
    value.put("issuanceFence", high);
    value.put(
        "membershipBaseline",
        new LinkedHashMap<>(
            Map.of(
                "membershipLifecycleState",
                "ACTIVE",
                "membershipVersion",
                new LinkedHashMap<>(Map.of(tenant, high)),
                "membershipAuthorityGeneration",
                high)));
    List<Map<String, Object>> checkpoints = new ArrayList<>();
    for (String suffix :
        List.of(
            "account/" + account,
            "issuer/firemud-account-service",
            "membership/" + account + "/" + tenant,
            "tenant/" + tenant)) {
      checkpoints.add(
          new LinkedHashMap<>(
              Map.of(
                  "outboxStreamKey",
                  prefix + suffix,
                  "outboxSequence",
                  suffix.startsWith("account/") || suffix.startsWith("issuer/") ? "0" : high)));
    }
    value.put("outboxCheckpoints", checkpoints);
    Map<String, Object> token = new LinkedHashMap<>();
    token.put("accountId", account);
    for (String key : List.of("operationId", "issuanceRequestId", "tokenJti")) {
      token.put(key, other);
    }
    token.put("tokenSHA256", "a".repeat(64));
    token.put("tokenProfile", "game-session-account-delegation");
    for (String key : List.of("tokenGeneration", "issuanceFence", "tokenIdentityFence")) {
      token.put(key, high);
    }
    token.put("issuedAt", "999");
    token.put("notBefore", "999");
    token.put("expiresAt", "1300");
    value.put("tokenIdentityEvidence", token);
    value.put("evaluatedAt", "1000000");
    value.put("expiresAt", "1015000");
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(value);
  }

  private record TestIdentity(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestIdentity gameSessionServer,
      TestIdentity wrongWorkloadServer,
      TestIdentity otherNamespaceServer,
      TestIdentity accountClient,
      TestIdentity wrongAccountClient,
      TestIdentity otherNamespaceAccount) {
    private static TestPki create(Path directory) throws Exception {
      Path caStore = directory.resolve("admission-decision-test-ca.p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=FireMUD admission-decision test CA",
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
      Path caFile = directory.resolve("admission-decision-test-ca.crt");
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
          issueIdentity(directory, caStore, caFile, "gs-server", GAME_SESSION_URI, true),
          issueIdentity(directory, caStore, caFile, "wrong-server", WRONG_WORKLOAD_URI, true),
          issueIdentity(
              directory, caStore, caFile, "other-namespace-server", OTHER_NAMESPACE_URI, true),
          issueIdentity(directory, caStore, caFile, "account-client", ACCOUNT_URI, false),
          issueIdentity(
              directory, caStore, caFile, "wrong-account-client", WRONG_WORKLOAD_URI, false),
          issueIdentity(
              directory, caStore, caFile, "other-namespace-account", OTHER_ACCOUNT_URI, false));
    }

    private CommonGrpcClientProperties clientProperties(Path directory, TestIdentity identity)
        throws Exception {
      Path cert =
          writePem(
              directory.resolve("caller.crt"), "CERTIFICATE", identity.certificate().getEncoded());
      Path key =
          writePem(
              directory.resolve("caller.key"), "PRIVATE KEY", identity.privateKey().getEncoded());
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
      String eku = server ? "serverAuth" : "clientAuth";
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
          "EKU=" + eku,
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
          "EKU=" + eku,
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
