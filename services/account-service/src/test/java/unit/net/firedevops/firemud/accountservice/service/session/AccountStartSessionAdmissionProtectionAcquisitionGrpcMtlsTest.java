package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
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
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
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
import net.firedevops.firemud.account.v1.AccountStartSessionAdmissionProtectionAcquisitionServiceGrpc;
import net.firedevops.firemud.account.v1.AcquireOriginalStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.account.v1.AcquireOriginalStartSessionAdmissionProtectionResponse;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionAdmissionProtectionAcquisitionGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionAdmissionProtectionAcquisitionService;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionAcquisitionGrpcClient;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionAcquisitionInput;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.test.TlsTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Physical loopback mTLS proof for the unregistered Account admission-protection receiver. */
class AccountStartSessionAdmissionProtectionAcquisitionGrpcMtlsTest {
  private static final String NAMESPACE = "world-runtime";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/world-runtime/sa/account-service";
  private static final String GAME_SESSION_URI =
      "spiffe://firemud/ns/world-runtime/sa/game-session-service";
  private static final String WRONG_SERVICE_URI =
      "spiffe://firemud/ns/world-runtime/sa/world-management-service";
  private static final String OTHER_NAMESPACE_URI =
      "spiffe://firemud/ns/other-runtime/sa/game-session-service";
  private static final String STORE_PASSWORD = "test-only-store-password";
  private static final UUID TENANT = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID MUTATION = uuid("f1a3ab1e-9147-4667-b6c4-6eb5119e8a31");
  private static final UUID ATTEMPT = uuid("ec13cc04-ec15-4eb8-a018-c2c5e8da65f8");
  private static final UUID PARTICIPATION = uuid("47b3be7f-a32f-4e19-8916-8c3b8da07a82");
  private static final UUID HOLD_ID = uuid("0db7344a-1e67-4b95-905a-83dc9c472f0c");
  private static final UUID HOLD_FENCE = uuid("52a14272-f9e4-4f67-97c9-62247b5fbcc1");
  private static final UUID PROTECTION_ID = uuid("6b763f1d-c5bc-4080-b499-d29debc0a7b8");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir static Path temporaryDirectory;

  private static TestPki pki;

  @BeforeAll
  static void generateTrustedWorkloadCertificates() throws Exception {
    pki = TestPki.create(temporaryDirectory);
  }

  @AfterEach
  void clearThreadState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactSameNamespaceGameSessionPeerAcquiresCompleteEvidenceWithTheCommonClient()
      throws Exception {
    var input = input();
    var expectedEvidence = evidence(input);
    var owner = mock(AccountStartSessionAdmissionProtectionAcquisitionService.class);
    when(owner.acquire(any())).thenReturn(expectedEvidence);
    try (TransportServer server = startServer(owner, false);
        var client = newClient(server.port(), pki.gameSessionClient())) {
      client.init();

      var actualEvidence = client.acquire(input);

      assertThat(actualEvidence.canonicalBytes())
          .containsExactly(expectedEvidence.canonicalBytes());
      assertThat(server.handlerCalls()).hasValue(1);
      ArgumentCaptor<AccountStartSessionAdmissionProtectionAcquisitionService.AcquisitionRequest>
          captured =
              ArgumentCaptor.forClass(
                  AccountStartSessionAdmissionProtectionAcquisitionService.AcquisitionRequest
                      .class);
      verify(owner).acquire(captured.capture());
      assertThat(captured.getValue().originalPostAuthorizationTuple())
          .containsExactly(input.originalPostAuthorizationTuple());
      assertThat(captured.getValue().gameSessionOwnerMutationId())
          .isEqualTo(input.gameSessionOwnerMutationId());
      assertThat(captured.getValue().gameSessionOwnerAttemptId())
          .isEqualTo(input.gameSessionOwnerAttemptId());
      assertThat(captured.getValue().gameSessionOwnerFence())
          .isEqualTo(input.gameSessionOwnerFence());
      assertThat(captured.getValue().worldHoldIdentity().canonicalBytes())
          .containsExactly(input.worldHoldIdentity().canonicalBytes());
    }
  }

  @Test
  void trustedWrongServiceAndNamespacePeersAreDeniedBeforeOwnerInteraction() throws Exception {
    var input = input();
    for (TestIdentity identity : List.of(pki.wrongServiceClient(), pki.otherNamespaceClient())) {
      var owner = mock(AccountStartSessionAdmissionProtectionAcquisitionService.class);
      try (TransportServer server = startServer(owner, false);
          var client = newClient(server.port(), identity)) {
        client.init();

        assertThatThrownBy(() -> client.acquire(input))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));

        assertThat(server.handlerCalls()).as(identity.alias()).hasValue(1);
        verifyNoInteractions(owner);
      }
    }
  }

  @Test
  void missingClientCertificateIsRejectedByTlsBeforeHandlerOrOwner() throws Exception {
    var owner = mock(AccountStartSessionAdmissionProtectionAcquisitionService.class);
    try (TransportServer server = startServer(owner, false);
        ClientTransport client = newClientWithoutIdentity(server.port())) {
      assertThatThrownBy(
              () ->
                  client.acquire(
                      AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toRequest(
                          input(), NAMESPACE)))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(TlsTestSupport.isTlsHandshakeRejection(failure))
                      .as("TLS rejection: %s; immediate cause: %s", failure, failure.getCause())
                      .isTrue());

      assertThat(server.handlerCalls()).hasValue(0);
      verifyNoInteractions(owner);
    }
  }

  @Test
  void authenticatedEndUserContextIsDeniedAtHandlerBeforeOwnerInteraction() throws Exception {
    var owner = mock(AccountStartSessionAdmissionProtectionAcquisitionService.class);
    try (TransportServer server = startServer(owner, true);
        var client = newClient(server.port(), pki.gameSessionClient())) {
      client.init();

      assertThatThrownBy(() -> client.acquire(input()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));

      assertThat(server.handlerCalls()).hasValue(1);
      verifyNoInteractions(owner);
    }
  }

  private static TransportServer startServer(
      AccountStartSessionAdmissionProtectionAcquisitionService owner, boolean injectEndUserContext)
      throws Exception {
    AtomicInteger handlerCalls = new AtomicInteger();
    var service =
        new AccountStartSessionAdmissionProtectionAcquisitionGrpcService(owner, NAMESPACE);
    ServerInterceptor countHandlerCalls =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call,
              Metadata metadata,
              ServerCallHandler<ReqT, RespT> next) {
            handlerCalls.incrementAndGet();
            return next.startCall(call, metadata);
          }
        };
    ServerInterceptor endUserContext =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call,
              Metadata metadata,
              ServerCallHandler<ReqT, RespT> next) {
            var delegate = next.startCall(call, metadata);
            if (!injectEndUserContext) return delegate;
            return new io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener<>(
                delegate) {
              @Override
              public void onHalfClose() {
                SessionContext.setContext("123", List.of(), Map.of());
                try {
                  super.onHalfClose();
                } finally {
                  SessionContext.clear();
                }
              }
            };
          }
        };
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            pki.accountServer().privateKey(), pki.accountServer().certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    service, countHandlerCalls, new GrpcPeerIdentityInterceptor(), endUserContext))
            .build()
            .start();
    return new TransportServer(server, handlerCalls);
  }

  private static AccountStartSessionAdmissionProtectionAcquisitionGrpcClient newClient(
      int port, TestIdentity identity) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + port);
    return new AccountStartSessionAdmissionProtectionAcquisitionGrpcClient(
        endpoints,
        pki.clientProperties(temporaryDirectory, identity),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static ClientTransport newClientWithoutIdentity(int port) throws Exception {
    var tls = new CommonGrpcClientProperties();
    tls.setPlaintext(false);
    tls.setCaCert(pki.caCertificatePem().toString());
    ManagedChannel channel =
        new GrpcChannelFactory().buildChannel("localhost:" + port, 6565, tls, false);
    return new ClientTransport(channel);
  }

  private static AccountStartSessionAdmissionProtectionAcquisitionInput input() {
    var tuple = originalTuple();
    return new AccountStartSessionAdmissionProtectionAcquisitionInput(
        tuple.canonicalBytes(), MUTATION, ATTEMPT, 21L, hold(tuple));
  }

  private static AccountStartSessionAdmissionProtectionEvidence evidence(
      AccountStartSessionAdmissionProtectionAcquisitionInput input) {
    var tuple =
        StartSessionPostAuthorizationExecutionTuple.decode(input.originalPostAuthorizationTuple());
    var ownerRequest =
        AccountStartSessionAdmissionProtectionRequest.create(
            input.originalPostAuthorizationTuple(),
            StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple),
            input.gameSessionOwnerMutationId(),
            input.gameSessionOwnerAttemptId(),
            input.gameSessionOwnerFence(),
            java.time.Instant.parse("2026-10-09T10:20:30.456Z"),
            PARTICIPATION,
            22L,
            input.worldHoldIdentity());
    byte[] capture =
        canonical(
            Map.of(
                "schema",
                AccountStartSessionAdmissionProtectionEvidence.SOURCE_CAPTURE_REFERENCE_SCHEMA,
                "controlPlaneRequestId",
                tuple.controlPlaneRequestId(),
                "capturedAt",
                "2026-10-09T10:11:12.123Z",
                "bundleReference",
                Map.of(
                    "bundleVersion",
                    StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                    "sourceVersion",
                    "17",
                    "sourceFence",
                    "23",
                    "linearization",
                    "18446744073709551615"),
                "snapshotSha256",
                "d".repeat(64)));
    return AccountStartSessionAdmissionProtectionEvidence.create(
        ownerRequest,
        PROTECTION_ID,
        23L,
        capture,
        sha256(capture),
        List.of(
            new SourceEvidence(
                SourceKind.ACCOUNT,
                TENANT.toString(),
                "2",
                "17",
                null,
                null,
                "exact Account source".getBytes(StandardCharsets.UTF_8))));
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple() {
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "transport-test",
            ACTOR,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, NAMESPACE),
                new StartSessionOperatorAction.Target(91L, TARGET_OWNER),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "canonical original StartSession admission attempt"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        preTuple,
        "spiffe://firemud/ns/" + NAMESPACE + "/sa/logging-admin-service",
        "arfp/v1/test-key/" + "b".repeat(64),
        uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf"),
        19L,
        authorityBundle(preTuple),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(StartSessionPreAuthorizationReservationTuple tuple) {
    Map<String, Object> projection =
        Map.of(
            "sourceType", "ACCOUNT",
            "sourceEvidenceId", "sha256:" + "a".repeat(64),
            "sourceEvidenceVersion", "17",
            "projectionStatus", "CURRENT",
            "evaluatedAt", "2026-10-09T00:00:00Z",
            "expiresAt", "2026-10-09T00:05:00Z");
    Map<String, Object> operation =
        Map.of(
            "issuanceOperationId", "f5d044bd-7e5f-4e2d-9859-9025cbdcc60f",
            "controlPlaneRequestId", tuple.controlPlaneRequestId(),
            "actionFamilyRequestIdentity",
                Map.of(
                    "requestIdentityKind",
                    "controlPlaneRequestId",
                    "requestId",
                    tuple.controlPlaneRequestId()),
            "mutationDigest", tuple.mutationDigest());
    Map<String, Object> authority =
        Map.of(
            "issuerAuthGeneration", 1L,
            "accountAuthorityGeneration", 2L,
            "tenantAuthorityGeneration", Map.of(TENANT.toString(), 3L),
            "membershipAuthorityGeneration", Map.of(TENANT.toString(), 4L),
            "privateRealmGrantVersions", List.of());
    Map<String, Object> issuanceEvidence =
        Map.of(
            "evidenceType", StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
            "actorAccountId", ACTOR.toString(),
            "controlUiTokenJti", "a681bba7-c215-4cf1-a35b-14348912cbdc",
            "role", "tenantAdmin",
            "accountGeneration", "2",
            "tenantGeneration", "3");
    return canonical(
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", TENANT.toString(), "targetNamespace", NAMESPACE),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", ACTOR.toString(),
                "applicableTenantId", TENANT.toString()),
            "accountProjectionEvidence",
            projection,
            "issuanceOperationIdentity",
            operation,
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            authority,
            "membershipVersion",
            Map.of(TENANT.toString(), 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            issuanceEvidence));
  }

  private static WorldCanonicalInitialAdmissionHold.HoldIdentity hold(
      StartSessionPostAuthorizationExecutionTuple tuple) {
    return new WorldCanonicalInitialAdmissionHold.HoldIdentity(
        new WorldCanonicalInitialAdmissionHold.Request(
            NAMESPACE,
            TENANT,
            "earth",
            uuid("3916f423-2870-426a-a8aa-5e3f97412613"),
            uuid("54e6094e-11bb-4f4c-93ee-a52f715b530b"),
            "SHARED",
            uuid("a55b2e10-9a24-4adb-adb8-6fc66fe3b8e9"),
            uuid("d7280ec0-5979-4b62-8418-e9f139415184"),
            3L,
            tuple.controlPlaneRequestId(),
            "c".repeat(64),
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            12L,
            null),
        HOLD_ID,
        HOLD_FENCE);
  }

  private static byte[] canonical(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String sha256(byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record TransportServer(Server server, AtomicInteger handlerCalls)
      implements AutoCloseable {
    private int port() {
      return server.getPort();
    }

    @Override
    public void close() throws InterruptedException {
      server.shutdownNow();
      if (!server.awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Loopback Account gRPC server did not terminate");
      }
    }
  }

  private record ClientTransport(ManagedChannel channel) implements AutoCloseable {
    private AcquireOriginalStartSessionAdmissionProtectionResponse acquire(
        AcquireOriginalStartSessionAdmissionProtectionRequest request) {
      return AccountStartSessionAdmissionProtectionAcquisitionServiceGrpc.newBlockingStub(channel)
          .withDeadlineAfter(3, TimeUnit.SECONDS)
          .acquireOriginalStartSessionAdmissionProtection(request);
    }

    @Override
    public void close() throws InterruptedException {
      if (!channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Loopback Account gRPC client did not terminate");
      }
    }
  }

  private record TestIdentity(String alias, PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      Path caCertificatePem,
      TestIdentity accountServer,
      TestIdentity gameSessionClient,
      TestIdentity wrongServiceClient,
      TestIdentity otherNamespaceClient) {
    private static TestPki create(Path directory) throws Exception {
      Path caStore = directory.resolve("admission-test-ca.p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=FireMUD Account admission test CA",
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
      Path caFile = directory.resolve("admission-test-ca.crt");
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
          caFile,
          issueIdentity(directory, caStore, caFile, "account-server", ACCOUNT_URI, true),
          issueIdentity(directory, caStore, caFile, "game-session-client", GAME_SESSION_URI, false),
          issueIdentity(
              directory, caStore, caFile, "wrong-service-client", WRONG_SERVICE_URI, false),
          issueIdentity(
              directory, caStore, caFile, "other-namespace-client", OTHER_NAMESPACE_URI, false));
    }

    private CommonGrpcClientProperties clientProperties(Path directory, TestIdentity identity)
        throws Exception {
      Path certificate =
          writePem(
              directory.resolve(identity.alias() + ".crt"),
              "CERTIFICATE",
              identity.certificate().getEncoded());
      Path privateKey =
          writePem(
              directory.resolve(identity.alias() + ".key"),
              "PRIVATE KEY",
              identity.privateKey().getEncoded());
      CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
      tls.setCertChain(certificate.toString());
      tls.setPrivateKey(privateKey.toString());
      tls.setCaCert(caCertificatePem.toString());
      tls.setPlaintext(false);
      return tls;
    }

    private static TestIdentity issueIdentity(
        Path directory, Path caStore, Path caFile, String alias, String workloadUri, boolean server)
        throws Exception {
      Path store = directory.resolve(alias + ".p12");
      Path request = directory.resolve(alias + ".csr");
      Path certificate = directory.resolve(alias + ".crt");
      String san = "URI:" + workloadUri + ",DNS:localhost,IP:127.0.0.1";
      String extendedKeyUsage = server ? "serverAuth" : "clientAuth";
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
          "EKU=" + extendedKeyUsage,
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
          "EKU=" + extendedKeyUsage,
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
      ArrayList<String> command = new ArrayList<>();
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
