package net.firedevops.firemud.common.account.startsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.grpc.ForwardingServerCall;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import net.firedevops.firemud.account.v1.AccountStartSessionWorldParticipationAcquisitionServiceGrpc;
import net.firedevops.firemud.account.v1.AcquireStartSessionWorldParticipationRequest;
import net.firedevops.firemud.account.v1.AcquireStartSessionWorldParticipationResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Request;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Result;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Socket mTLS client proof with a synthetic mocked Account acquisition backend, not PostgreSQL. */
class AccountStartSessionWorldParticipationAcquisitionClientMtlsTest {
  private static final String NAMESPACE = "world-runtime";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/world-runtime/sa/account-service";
  private static final String WORLD_URI =
      "spiffe://firemud/ns/world-runtime/sa/world-management-service";
  private static final String STORE_PASSWORD = "synthetic-world-participation-test-password";
  private static final UUID ACCOUNT_ID = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TENANT_ID = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID MUTATION_ID = uuid("b9d2f9d9-0438-42c8-829e-fd3392cd9d21");
  private static final UUID ATTEMPT_ID = uuid("4a3fc69f-a18a-4b94-b1d1-2e8b4c3f0e4b");
  private static final UUID GAME_INSTANCE_ID = uuid("c0e30aea-1549-43d9-93af-0e44a3deae32");
  private static final UUID PARTICIPATION_ID = uuid("a8c1e8c8-f237-41b7-918d-ec2281bcac10");
  private static final String PREPARATION_JSON =
      "{\"selected\":\"immutable source\",\"revision\":3}";
  private static final Instant ORIGINAL_LEASE_EXPIRY = Instant.parse("2026-10-12T00:00:00Z");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir static Path temporaryDirectory;
  private static TestPki pki;
  private Server server;
  private AccountStartSessionWorldParticipationAcquisitionClient client;

  @BeforeAll
  static void createSyntheticWorkloadCertificates() throws Exception {
    pki = TestPki.create(temporaryDirectory);
  }

  @AfterEach
  void stopTransportAndClearThreadState() throws Exception {
    if (client != null) {
      client.close();
      client = null;
    }
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      server = null;
    }
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void loopbackMtlsReturnsExactBoundParticipationAndCompleteObservation() throws Exception {
    var wireRequest = request(NAMESPACE);
    var expected = response(wireRequest);
    var backend = mock(WorldParticipationAcquisitionBackend.class);
    when(backend.acquire(any())).thenReturn(expected);
    AtomicInteger handlers = startServer(backend, UnaryOperator.identity());
    client = newClient(server.getPort(), pki.worldClient());
    client.init();

    var actual = client.acquire(wireRequest);

    assertThat(actual.getRequest()).isEqualTo(wireRequest);
    assertThat(actual.getAccountWorldParticipationId()).isEqualTo(PARTICIPATION_ID.toString());
    assertThat(actual.getAccountWorldParticipationFence()).isEqualTo(31L);
    assertThat(actual.getOriginalAttemptEvidence())
        .isEqualTo(expected.getOriginalAttemptEvidence());
    assertThat(handlers).hasValue(1);
    ArgumentCaptor<AcquireStartSessionWorldParticipationRequest> captured =
        ArgumentCaptor.forClass(AcquireStartSessionWorldParticipationRequest.class);
    verify(backend).acquire(captured.capture());
    assertThat(captured.getValue()).isEqualTo(wireRequest);
  }

  @Test
  void rejectsEchoAndOriginalEvidenceSubstitutionFromTheRemotePeer() throws Exception {
    var wireRequest = request(NAMESPACE);
    var backend = mock(WorldParticipationAcquisitionBackend.class);
    when(backend.acquire(any())).thenReturn(response(wireRequest));
    AtomicInteger handlers =
        startServer(
            backend,
            response ->
                response.toBuilder()
                    .setRequest(
                        response.getRequest().toBuilder()
                            .setAcquisitionRequestId("11111111-1111-4111-8111-111111111111"))
                    .build());
    client = newClient(server.getPort(), pki.worldClient());
    client.init();
    assertThatThrownBy(() -> client.acquire(wireRequest))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid original StartSession World participation evidence");
    assertThat(handlers).hasValue(1);

    stopCurrentTransport();
    when(backend.acquire(any())).thenReturn(response(wireRequest));
    handlers =
        startServer(
            backend,
            response -> {
              var observation = response.getOriginalAttemptEvidence();
              var changed =
                  observation.toBuilder()
                      .setRequest(
                          observation.getRequest().toBuilder()
                              .setExpectedOwnerFence(
                                  observation.getRequest().getExpectedOwnerFence() + 1))
                      .build();
              return response.toBuilder().setOriginalAttemptEvidence(changed).build();
            });
    client = newClient(server.getPort(), pki.worldClient());
    client.init();
    assertThatThrownBy(() -> client.acquire(wireRequest))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid original StartSession World participation evidence");
    assertThat(handlers).hasValue(1);
  }

  @Test
  void wrongAuthenticatedWorkloadsAndLocalNamespaceMismatchAreRejected() throws Exception {
    var wireRequest = request(NAMESPACE);
    var backend = mock(WorldParticipationAcquisitionBackend.class);
    AtomicInteger handlers = startServer(backend, UnaryOperator.identity());
    for (TestIdentity identity : List.of(pki.wrongServiceClient(), pki.otherNamespaceClient())) {
      client = newClient(server.getPort(), identity);
      client.init();
      assertThatThrownBy(() -> client.acquire(wireRequest))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));
      client.close();
      client = null;
    }
    assertThat(handlers).hasValue(2);
    verifyNoInteractions(backend);

    var validPeerClient = newClient(server.getPort(), pki.worldClient());
    client = validPeerClient;
    client.init();
    assertThatThrownBy(() -> client.acquire(request("default")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported or oversized World-participation request");
    assertThat(handlers).hasValue(2);
    verifyNoInteractions(backend);
  }

  @Test
  void wrongAccountServerWorkloadIsRejectedBeforeSendingTheRequest() throws Exception {
    var backend = mock(WorldParticipationAcquisitionBackend.class);
    AtomicInteger handlers = startServer(backend, UnaryOperator.identity(), pki.wrongServer());
    client = newClient(server.getPort(), pki.worldClient());
    client.init();

    assertThatThrownBy(() -> client.acquire(request(NAMESPACE)))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.UNAUTHENTICATED));
    assertThat(handlers).hasValue(0);
    verifyNoInteractions(backend);
  }

  @Test
  void rejectsAmbientSqlAndEndUserContextBeforeSending() throws Exception {
    var backend = mock(WorldParticipationAcquisitionBackend.class);
    AtomicInteger handlers = startServer(backend, UnaryOperator.identity());
    client = newClient(server.getPort(), pki.worldClient());
    client.init();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> client.acquire(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ambient SQL");
    TransactionSynchronizationManager.clear();
    SessionContext.setContext("123", List.of(), Map.of());
    assertThatThrownBy(() -> client.acquire(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("workload-only");
    assertThat(handlers).hasValue(0);
    verifyNoInteractions(backend);
  }

  private AtomicInteger startServer(
      WorldParticipationAcquisitionBackend backend,
      UnaryOperator<AcquireStartSessionWorldParticipationResponse> responseMutation)
      throws Exception {
    return startServer(backend, responseMutation, pki.accountServer());
  }

  private AtomicInteger startServer(
      WorldParticipationAcquisitionBackend backend,
      UnaryOperator<AcquireStartSessionWorldParticipationResponse> responseMutation,
      TestIdentity serverIdentity)
      throws Exception {
    AtomicInteger handlers = new AtomicInteger();
    var service =
        new AccountStartSessionWorldParticipationAcquisitionServiceGrpc
            .AccountStartSessionWorldParticipationAcquisitionServiceImplBase() {
          @Override
          public void acquireStartSessionWorldParticipation(
              AcquireStartSessionWorldParticipationRequest request,
              StreamObserver<AcquireStartSessionWorldParticipationResponse> observer) {
            var peer = GrpcPeerIdentity.current();
            if (peer == null) {
              observer.onError(Status.UNAUTHENTICATED.asRuntimeException());
              return;
            }
            if (!WORLD_URI.equals(peer.uri())
                || !NAMESPACE.equals(peer.namespace())
                || SessionContext.hasAuthenticatedCallerContext()) {
              observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
              return;
            }
            try {
              observer.onNext(backend.acquire(request));
              observer.onCompleted();
            } catch (RuntimeException failure) {
              observer.onError(Status.UNAVAILABLE.asRuntimeException());
            }
          }
        };
    ServerInterceptor countHandlers =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call,
              Metadata metadata,
              ServerCallHandler<ReqT, RespT> next) {
            handlers.incrementAndGet();
            return next.startCall(call, metadata);
          }
        };
    ServerInterceptor mutateResponse =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call,
              Metadata metadata,
              ServerCallHandler<ReqT, RespT> next) {
            var transformedCall =
                new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                  @Override
                  @SuppressWarnings("unchecked")
                  public void sendMessage(RespT message) {
                    if (message instanceof AcquireStartSessionWorldParticipationResponse response) {
                      super.sendMessage((RespT) responseMutation.apply(response));
                    } else {
                      super.sendMessage(message);
                    }
                  }
                };
            return next.startCall(transformedCall, metadata);
          }
        };
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .maxInboundMessageSize(32 * 1024 * 1024)
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            serverIdentity.privateKey(), serverIdentity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    service, countHandlers, new GrpcPeerIdentityInterceptor(), mutateResponse))
            .build()
            .start();
    return handlers;
  }

  private AccountStartSessionWorldParticipationAcquisitionClient newClient(
      int port, TestIdentity identity) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + port);
    return new AccountStartSessionWorldParticipationAcquisitionClient(
        endpoints,
        pki.clientProperties(temporaryDirectory, identity),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static AcquireStartSessionWorldParticipationRequest request(String namespace) {
    return AcquireStartSessionWorldParticipationRequest.newBuilder()
        .setSchemaVersion(1)
        .setAcquisitionRequestId("263a218e-1752-47f2-97c3-6273b2ea9f15")
        .setTargetNamespace(namespace)
        .setOriginalPostAuthorizationTuple(ByteString.copyFrom(originalTuple().canonicalBytes()))
        .setGameSessionOwnerMutationId(MUTATION_ID.toString())
        .setGameSessionOwnerAttemptId(ATTEMPT_ID.toString())
        .setGameSessionOwnerFence(37L)
        .setCanonicalGameInstanceId(GAME_INSTANCE_ID.toString())
        .setPreparationInputJson(PREPARATION_JSON)
        .setPreparationInputDigest(digest(PREPARATION_JSON))
        .build();
  }

  private static AcquireStartSessionWorldParticipationResponse response(
      AcquireStartSessionWorldParticipationRequest wire) {
    var tuple =
        StartSessionPostAuthorizationExecutionTuple.decode(
            wire.getOriginalPostAuthorizationTuple().toByteArray());
    var observationRequest =
        new Request(
            uuid("4d66578e-a7cd-4a37-8cfb-30aa4c0c23b3"),
            NAMESPACE,
            tuple.canonicalBytes(),
            ATTEMPT_ID,
            MUTATION_ID,
            37L);
    var observation =
        new Result(
            observationRequest,
            ORIGINAL_LEASE_EXPIRY,
            StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple));
    return AcquireStartSessionWorldParticipationResponse.newBuilder()
        .setRequest(wire)
        .setAccountWorldParticipationId(PARTICIPATION_ID.toString())
        .setAccountWorldParticipationFence(31L)
        .setOriginalAttemptEvidence(
            OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(observation))
        .build();
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple() {
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "world-participation-mtls-test",
            ACCOUNT_ID,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT_ID, NAMESPACE),
                new StartSessionOperatorAction.Target(
                    91L, uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a")),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "synthetic World participation mTLS proof"));
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
    String actorId = tuple.actor().accountId().toString();
    String tenantId = tuple.action().scope().tenantId().toString();
    Map<String, Object> value =
        Map.of(
            "bundleVersion", StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
                Map.of(
                    "scope",
                    Map.of("tenantId", tenantId, "targetNamespace", NAMESPACE),
                    "actionFamily",
                    tuple.actionFamily(),
                    "applicableAccountId",
                    actorId,
                    "applicableTenantId",
                    tenantId),
            "accountProjectionEvidence",
                Map.of(
                    "sourceType", "ACCOUNT",
                    "sourceEvidenceId", "sha256:" + "a".repeat(64),
                    "sourceEvidenceVersion", "17",
                    "projectionStatus", "CURRENT",
                    "evaluatedAt", "2026-10-09T00:00:00Z",
                    "expiresAt", "2026-10-10T00:00:00Z"),
            "issuanceOperationIdentity",
                Map.of(
                    "issuanceOperationId", "f5d044bd-7e5f-4e2d-9859-9025cbdcc60f",
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
            "issuanceFence", "23",
            "issuanceEvidence",
                Map.of(
                    "evidenceType", StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
                    "actorAccountId", actorId,
                    "controlUiTokenJti", "a681bba7-c215-4cf1-a35b-14348912cbdc",
                    "role", "tenantAdmin",
                    "accountGeneration", "2",
                    "tenantGeneration", "3"));
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (java.io.IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String digest(String value) {
    return digest(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String digest(byte[] value) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
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

  private record TestIdentity(PrivateKey privateKey, X509Certificate certificate) {}

  private interface WorldParticipationAcquisitionBackend {
    AcquireStartSessionWorldParticipationResponse acquire(
        AcquireStartSessionWorldParticipationRequest request);
  }

  private record TestPki(
      X509Certificate caCertificate,
      TestIdentity accountServer,
      TestIdentity wrongServer,
      TestIdentity worldClient,
      TestIdentity wrongServiceClient,
      TestIdentity otherNamespaceClient) {
    private static TestPki create(Path directory) throws Exception {
      Path caStore = directory.resolve("world-participation-test-ca.p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=FireMUD World participation test CA",
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
      Path caFile = directory.resolve("world-participation-test-ca.crt");
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
      X509Certificate ca = readCertificate(caFile);
      return new TestPki(
          ca,
          issueIdentity(directory, caStore, caFile, "account-server", ACCOUNT_URI, true),
          issueIdentity(
              directory,
              caStore,
              caFile,
              "wrong-account-server",
              "spiffe://firemud/ns/world-runtime/sa/account-other",
              true),
          issueIdentity(directory, caStore, caFile, "world-client", WORLD_URI, false),
          issueIdentity(
              directory,
              caStore,
              caFile,
              "wrong-service-client",
              "spiffe://firemud/ns/world-runtime/sa/game-session-service",
              false),
          issueIdentity(
              directory,
              caStore,
              caFile,
              "other-namespace-client",
              "spiffe://firemud/ns/other-runtime/sa/world-management-service",
              false));
    }

    private CommonGrpcClientProperties clientProperties(Path directory, TestIdentity identity)
        throws Exception {
      var properties = new CommonGrpcClientProperties();
      properties.setPlaintext(false);
      properties.setCertChain(
          writePem(
                  directory.resolve("client-" + identity.certificate().getSerialNumber() + ".crt"),
                  "CERTIFICATE",
                  identity.certificate().getEncoded())
              .toString());
      properties.setPrivateKey(
          writePem(
                  directory.resolve("client-" + identity.certificate().getSerialNumber() + ".key"),
                  "PRIVATE KEY",
                  identity.privateKey().getEncoded())
              .toString());
      properties.setCaCert(
          writePem(
                  directory.resolve("trusted-test-ca.crt"),
                  "CERTIFICATE",
                  caCertificate.getEncoded())
              .toString());
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
