package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.google.protobuf.ByteString;
import io.grpc.CallCredentials;
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
import io.grpc.stub.AbstractStub;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.RedeemOperatorAuthorizationRequest;
import net.firedevops.firemud.account.v1.RedeemOperatorAuthorizationResponse;
import net.firedevops.firemud.account.v1.StartSessionOperatorAuthorizationServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptClaim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Physical socket proof that redemption reaches only the exact same-namespace Account workload. */
class StartSessionOperatorRedemptionClientMtlsTest {
  private static final String NAMESPACE = "test";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String WRONG_WORKLOAD_URI =
      "spiffe://firemud/ns/test/sa/world-management-service";
  private static final String WRONG_NAMESPACE_URI = "spiffe://firemud/ns/other/sa/account-service";
  private static final String GAME_SESSION_URI = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String REFERENCE = "A".repeat(43);
  private static final String STORE_PASSWORD = "test-only-store-password";
  private static final UUID TENANT_ID = UUID.fromString("12345678-1234-4234-8234-123456789abc");
  private static final UUID ACTOR_ID = UUID.fromString("22345678-1234-4234-8234-123456789abc");
  private static final UUID TARGET_OWNER_ID =
      UUID.fromString("32345678-1234-4234-8234-123456789abc");
  private static final UUID RESERVATION_OWNER_ID =
      UUID.fromString("42345678-1234-4234-8234-123456789abc");
  private static final UUID OWNER_ATTEMPT_ID =
      UUID.fromString("52345678-1234-4234-8234-123456789abc");
  private static final UUID OWNER_MUTATION_ID =
      UUID.fromString("62345678-1234-4234-8234-123456789abc");
  private static final UUID CLAIM_OWNER_ID =
      UUID.fromString("72345678-1234-4234-8234-123456789abc");
  private static final UUID ISSUANCE_OPERATION_ID =
      UUID.fromString("82345678-1234-4234-8234-123456789abc");
  private static final UUID CONTROL_UI_JTI =
      UUID.fromString("92345678-1234-4234-8234-123456789abc");
  private static final String FINGERPRINT = "arfp/v1/test-key/" + "b".repeat(64);
  private static final long RESERVATION_FENCE = 4L;
  private static final long OWNER_FENCE = 9L;
  private static final long ISSUANCE_FENCE = 23L;
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"), ZoneOffset.UTC);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir static Path temporaryDirectory;

  private static TestPki pki;
  private Server server;
  private StartSessionOperatorRedemptionClient client;
  private AtomicInteger rpcStarts;
  private AtomicInteger applicationCalls;
  private AtomicReference<GrpcPeerIdentity> receivedPeer;
  private AtomicReference<RedeemOperatorAuthorizationRequest> receivedRequest;

  @BeforeAll
  static void createLocalTestCertificates() throws Exception {
    pki = TestPki.create(temporaryDirectory);
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
  void redeemsOverSocketMtlsWithExactSameNamespaceAccountPeer() throws Exception {
    StartSessionPostAuthorizationExecutionTuple tuple = tuple();
    startServer(pki.accountServer(), tuple);
    client = newClient(server);
    client.initialize();

    var result = client.redeem(tuple, REFERENCE, claim());

    assertThat(result.gameSessionClaim()).isEqualTo(claim());
    assertThat(result.projection().authorizationReferenceFingerprint()).isEqualTo(FINGERPRINT);
    assertThat(result.projection().issuanceOperationId()).isEqualTo(ISSUANCE_OPERATION_ID);
    assertThat(result.projection().issuanceFence()).isEqualTo(ISSUANCE_FENCE);
    assertThat(result.toString()).doesNotContain(REFERENCE);
    assertThat(rpcStarts).hasValue(1);
    assertThat(applicationCalls).hasValue(1);
    assertThat(receivedPeer.get())
        .isNotNull()
        .extracting(GrpcPeerIdentity::uri)
        .isEqualTo(GAME_SESSION_URI);
    assertThat(receivedRequest.get().getOperatorAuthorizationReference()).isEqualTo(REFERENCE);
  }

  @Test
  void sameCaWrongWorkloadAndWrongNamespaceAccountServersReceiveNoRedemptionRpc() throws Exception {
    StartSessionPostAuthorizationExecutionTuple tuple = tuple();
    for (TestIdentity wrongServer :
        List.of(pki.wrongWorkloadServer(), pki.wrongNamespaceServer())) {
      startServer(wrongServer, tuple);
      client = newClient(server);
      client.initialize();

      Throwable failure = catchThrowable(() -> client.redeem(tuple, REFERENCE, claim()));

      assertThat(failure)
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              thrown ->
                  assertThat(((StatusRuntimeException) thrown).getStatus().getCode())
                      .isEqualTo(Status.Code.UNAUTHENTICATED))
          .hasMessageNotContaining(REFERENCE);
      assertThat(rpcStarts).hasValue(0);
      assertThat(applicationCalls).hasValue(0);
      assertThat(receivedRequest.get()).isNull();
      stopTransport();
    }
  }

  private void startServer(
      TestIdentity serverIdentity, StartSessionPostAuthorizationExecutionTuple tuple)
      throws Exception {
    rpcStarts = new AtomicInteger();
    applicationCalls = new AtomicInteger();
    receivedPeer = new AtomicReference<>();
    receivedRequest = new AtomicReference<>();
    var service =
        new StartSessionOperatorAuthorizationServiceGrpc
            .StartSessionOperatorAuthorizationServiceImplBase() {
          @Override
          public void redeemOperatorAuthorization(
              RedeemOperatorAuthorizationRequest request,
              StreamObserver<RedeemOperatorAuthorizationResponse> observer) {
            applicationCalls.incrementAndGet();
            receivedPeer.set(GrpcPeerIdentity.current());
            receivedRequest.set(request);
            observer.onNext(response(tuple));
            observer.onCompleted();
          }
        };
    ServerInterceptor countRpcStarts =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            rpcStarts.incrementAndGet();
            return next.startCall(call, headers);
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
            .addService(
                ServerInterceptors.intercept(
                    service, countRpcStarts, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private StartSessionOperatorRedemptionClient newClient(Server target) throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + target.getPort());
    return new StartSessionOperatorRedemptionClient(
        endpoints,
        pki.clientProperties(temporaryDirectory),
        new GrpcChannelFactory(),
        customizerThatReplacesCallCredentials(),
        NAMESPACE,
        FIXED_CLOCK);
  }

  private static BlockingGrpcStubCustomizer customizerThatReplacesCallCredentials() {
    return new BlockingGrpcStubCustomizer() {
      @Override
      public <T extends AbstractStub<T>> T customize(T stub) {
        return stub.withCallCredentials(
            new CallCredentials() {
              @Override
              public void applyRequestMetadata(
                  RequestInfo requestInfo, Executor appExecutor, MetadataApplier applier) {
                applier.apply(new Metadata());
              }
            });
      }
    };
  }

  private static RedeemOperatorAuthorizationResponse response(
      StartSessionPostAuthorizationExecutionTuple tuple) {
    return RedeemOperatorAuthorizationResponse.newBuilder()
        .setAuthorizationReferenceFingerprint(FINGERPRINT)
        .setAuthorityEvidenceBundle(ByteString.copyFrom(tuple.authorityEvidenceBundleBytes()))
        .setIssuanceOperationId(ISSUANCE_OPERATION_ID.toString())
        .setIssuanceFence(ISSUANCE_FENCE)
        .setReplay(false)
        .build();
  }

  private static AttemptClaim claim() {
    return new AttemptClaim(
        NAMESPACE,
        "socket-redemption-request",
        OWNER_ATTEMPT_ID,
        OWNER_MUTATION_ID,
        CLAIM_OWNER_ID,
        OWNER_FENCE);
  }

  private static StartSessionPostAuthorizationExecutionTuple tuple() {
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT_ID, NAMESPACE),
            new StartSessionOperatorAction.Target(91L, TARGET_OWNER_ID),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "socket mTLS redemption peer identity proof");
    StartSessionPreAuthorizationReservationTuple preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "socket-redemption-request", ACTOR_ID, action);
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        preTuple,
        "spiffe://firemud/ns/test/sa/logging-admin-service",
        FINGERPRINT,
        RESERVATION_OWNER_ID,
        RESERVATION_FENCE,
        authorityEvidenceBundle(preTuple),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            Long.toString(ISSUANCE_FENCE),
            "18446744073709551615"));
  }

  private static byte[] authorityEvidenceBundle(
      StartSessionPreAuthorizationReservationTuple tuple) {
    String tenantId = TENANT_ID.toString();
    Map<String, Object> value =
        Map.of(
            "bundleVersion", StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
                Map.of(
                    "scope", Map.of("tenantId", tenantId, "targetNamespace", NAMESPACE),
                    "actionFamily", tuple.actionFamily(),
                    "applicableAccountId", ACTOR_ID.toString(),
                    "applicableTenantId", tenantId),
            "accountProjectionEvidence",
                Map.of(
                    "sourceType",
                    "ACCOUNT",
                    "sourceEvidenceId",
                    "sha256:" + "a".repeat(64),
                    "sourceEvidenceVersion",
                    "17",
                    "projectionStatus",
                    "CURRENT",
                    "evaluatedAt",
                    "2026-10-09T00:00:00Z",
                    "expiresAt",
                    "2026-10-10T00:00:00Z"),
            "issuanceOperationIdentity",
                Map.of(
                    "issuanceOperationId", ISSUANCE_OPERATION_ID.toString(),
                    "controlPlaneRequestId", tuple.controlPlaneRequestId(),
                    "actionFamilyRequestIdentity",
                        Map.of(
                            "requestIdentityKind",
                            "controlPlaneRequestId",
                            "requestId",
                            tuple.controlPlaneRequestId()),
                    "mutationDigest", tuple.mutationDigest()),
            "issuanceKind", "human_operator",
            "authorityTuple",
                Map.of(
                    "issuerAuthGeneration", 1L,
                    "accountAuthorityGeneration", 2L,
                    "tenantAuthorityGeneration", Map.of(tenantId, 3L),
                    "membershipAuthorityGeneration", Map.of(tenantId, 4L),
                    "privateRealmGrantVersions", List.of()),
            "membershipVersion", Map.of(tenantId, 5L),
            "issuanceFence", Long.toString(ISSUANCE_FENCE),
            "issuanceEvidence",
                Map.of(
                    "evidenceType",
                    StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
                    "actorAccountId",
                    ACTOR_ID.toString(),
                    "controlUiTokenJti",
                    CONTROL_UI_JTI.toString(),
                    "role",
                    "tenantAdmin",
                    "accountGeneration",
                    "2",
                    "tenantGeneration",
                    "3"));
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException malformed) {
      throw new IllegalStateException("could not encode test Account authority bundle", malformed);
    }
  }

  private record TestIdentity(PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestIdentity accountServer,
      TestIdentity wrongWorkloadServer,
      TestIdentity wrongNamespaceServer,
      TestIdentity gameSessionClient) {
    private static TestPki create(Path directory) throws Exception {
      Path caStore = directory.resolve("redemption-test-ca.p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=FireMUD redemption client test CA",
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
      Path caFile = directory.resolve("redemption-test-ca.crt");
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
          issueIdentity(directory, caStore, caFile, "account-server", ACCOUNT_URI, true),
          issueIdentity(
              directory,
              caStore,
              caFile,
              "wrong-workload-account-server",
              WRONG_WORKLOAD_URI,
              true),
          issueIdentity(
              directory,
              caStore,
              caFile,
              "wrong-namespace-account-server",
              WRONG_NAMESPACE_URI,
              true),
          issueIdentity(
              directory, caStore, caFile, "game-session-client", GAME_SESSION_URI, false));
    }

    private CommonGrpcClientProperties clientProperties(Path directory) throws Exception {
      Path clientCertificate =
          writePem(
              directory.resolve("game-session-client.crt"),
              "CERTIFICATE",
              gameSessionClient.certificate().getEncoded());
      Path clientPrivateKey =
          writePem(
              directory.resolve("game-session-client.key"),
              "PRIVATE KEY",
              gameSessionClient.privateKey().getEncoded());
      Path trustCa =
          writePem(
              directory.resolve("redemption-client-trust-ca.crt"),
              "CERTIFICATE",
              caCertificate.getEncoded());
      CommonGrpcClientProperties properties = new CommonGrpcClientProperties();
      properties.setCertChain(clientCertificate.toString());
      properties.setPrivateKey(clientPrivateKey.toString());
      properties.setCaCert(trustCa.toString());
      properties.setPlaintext(false);
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
      List<String> command = new ArrayList<>();
      command.add(keytool.toString());
      command.addAll(List.of(arguments));
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("keytool timed out");
      }
      String output;
      try (var stream = process.getInputStream()) {
        output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      }
      if (process.exitValue() != 0) {
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
