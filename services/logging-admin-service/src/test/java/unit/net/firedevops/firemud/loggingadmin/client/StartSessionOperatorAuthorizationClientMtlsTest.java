package net.firedevops.firemud.loggingadmin.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
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
import io.grpc.netty.shaded.io.netty.handler.ssl.SslProvider;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import net.firedevops.firemud.account.v1.AuthorityEvidenceBundleReference;
import net.firedevops.firemud.account.v1.IssueHumanOperatorAuthorizationReferenceRequest;
import net.firedevops.firemud.account.v1.IssueHumanOperatorAuthorizationReferenceResponse;
import net.firedevops.firemud.account.v1.RecoverOperatorAuthorizationReferenceRequest;
import net.firedevops.firemud.account.v1.RecoverOperatorAuthorizationReferenceResponse;
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
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.test.TlsTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Socket-mTLS proof against a synthetic Account handler, not Account issuance or Logging claim
 * proof.
 */
class StartSessionOperatorAuthorizationClientMtlsTest {
  private static final String NAMESPACE = "logging-test";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/logging-test/sa/account-service";
  private static final String LOGGING_URI =
      "spiffe://firemud/ns/logging-test/sa/logging-admin-service";
  private static final String STORE_PASSWORD = "synthetic-start-session-authorization-test";
  private static final UUID TENANT_ID = UUID.fromString("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR_ID = UUID.fromString("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID OWNER_ACCOUNT_ID =
      UUID.fromString("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID RESERVATION_OWNER_ID =
      UUID.fromString("ef5b97ec-3cbe-43dc-a85f-f119060386ab");
  private static final UUID CLAIM_OWNER_ID =
      UUID.fromString("9bb650bd-38c2-4dc1-8132-1af107358e60");
  private static final String CONTROL_UI_JWT = "synthetic-original-control-ui-jwt-body-only";
  private static final String OPERATOR_REFERENCE = "a".repeat(43);
  private static final String FINGERPRINT = "arfp/v1/test-key/" + "b".repeat(64);
  private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
  private static final Instant EXPIRY = NOW.plusSeconds(240);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir static Path temporaryDirectory;
  private static TestPki pki;
  private Server server;
  private StartSessionOperatorAuthorizationClient client;
  private AtomicInteger rpcCalls;
  private IssueHumanOperatorAuthorizationReferenceRequest receivedIssue;
  private RecoverOperatorAuthorizationReferenceRequest receivedRecovery;

  @BeforeAll
  static void createSyntheticWorkloadCertificates() throws Exception {
    pki = TestPki.create(temporaryDirectory);
  }

  @BeforeEach
  void clearAmbientThreadState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
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
  void issueAndRecoverPreserveTheExactOriginalResponseAndCompleteEvidence() throws Exception {
    startServer(pki.accountServer(), UnaryOperator.identity(), UnaryOperator.identity());
    client = newClient(pki.loggingClient());
    client.initialize();
    var tuple = tuple("synthetic-issue-recover-01");

    var issued =
        client.issueHuman(
            tuple, CONTROL_UI_JWT, RESERVATION_OWNER_ID, Long.MAX_VALUE, CLAIM_OWNER_ID, 71L);
    var recovered =
        client.recover(tuple, RESERVATION_OWNER_ID, Long.MAX_VALUE, CLAIM_OWNER_ID, 71L);

    assertThat(issued.operatorAuthorizationReference()).isEqualTo(OPERATOR_REFERENCE);
    assertThat(recovered.operatorAuthorizationReference()).isEqualTo(OPERATOR_REFERENCE);
    assertThat(recovered.authorizationReferenceFingerprint())
        .isEqualTo(issued.authorizationReferenceFingerprint());
    assertThat(issued.expiresAt()).isEqualTo(EXPIRY);
    assertThat(recovered.expiresAt()).isEqualTo(issued.expiresAt());
    assertThat(recovered.authorityEvidenceBundle())
        .containsExactly(issued.authorityEvidenceBundle());
    assertThat(recovered.bundleReference()).isEqualTo(issued.bundleReference());
    assertThat(issued.authenticatedLoggingWorkloadIdentity()).isEqualTo(LOGGING_URI);
    assertThat(recovered.authenticatedLoggingWorkloadIdentity()).isEqualTo(LOGGING_URI);
    assertThat(issued.decodedBundle().controlPlaneRequestId())
        .isEqualTo(tuple.controlPlaneRequestId());
    assertThat(issued.toString()).doesNotContain(OPERATOR_REFERENCE, CONTROL_UI_JWT);
    assertThat(receivedIssue.getControlUiJwt()).isEqualTo(CONTROL_UI_JWT);
    assertThat(receivedIssue.getCanonicalPreAuthorizationTupleBytes().toByteArray())
        .containsExactly(tuple.canonicalJson().getBytes(StandardCharsets.UTF_8));
    assertThat(receivedIssue.getReservationOwnerId()).isEqualTo(RESERVATION_OWNER_ID.toString());
    assertThat(receivedIssue.getReservationClaimFence()).isEqualTo(Long.MAX_VALUE);
    assertThat(receivedIssue.getCurrentClaimOwnerId()).isEqualTo(CLAIM_OWNER_ID.toString());
    assertThat(receivedIssue.getCurrentClaimFence()).isEqualTo(71L);
    assertThat(receivedRecovery.getCanonicalPreAuthorizationTupleBytes().toByteArray())
        .containsExactly(receivedIssue.getCanonicalPreAuthorizationTupleBytes().toByteArray());
    assertThat(receivedRecovery.getReservationOwnerId())
        .isEqualTo(receivedIssue.getReservationOwnerId());
    assertThat(receivedRecovery.getReservationClaimFence())
        .isEqualTo(receivedIssue.getReservationClaimFence());
    assertThat(receivedRecovery.getCurrentClaimOwnerId())
        .isEqualTo(receivedIssue.getCurrentClaimOwnerId());
    assertThat(receivedRecovery.getCurrentClaimFence())
        .isEqualTo(receivedIssue.getCurrentClaimFence());
    assertThat(rpcCalls).hasValue(2);
  }

  @Test
  void rejectsWrongAccountServerServiceAndNamespaceBeforeSendingRequest() throws Exception {
    assertWrongServerIdentity(
        pki.identity(
            "wrong-service-server", "spiffe://firemud/ns/logging-test/sa/account-other", true));
    assertWrongServerIdentity(
        pki.identity(
            "wrong-namespace-server", "spiffe://firemud/ns/other-test/sa/account-service", true));
  }

  @Test
  void rejectsWrongLoggingCallerServiceAndNamespaceAtSyntheticAccountBoundary() throws Exception {
    for (TestIdentity caller :
        List.of(
            pki.identity(
                "wrong-service-client",
                "spiffe://firemud/ns/logging-test/sa/game-session-service",
                false),
            pki.identity(
                "wrong-namespace-client",
                "spiffe://firemud/ns/other-test/sa/logging-admin-service",
                false))) {
      startServer(pki.accountServer(), UnaryOperator.identity(), UnaryOperator.identity());
      client = newClient(caller);
      client.initialize();

      assertThatThrownBy(
              () ->
                  client.issueHuman(
                      tuple("wrong-caller-" + caller.alias()),
                      CONTROL_UI_JWT,
                      RESERVATION_OWNER_ID,
                      8L,
                      CLAIM_OWNER_ID,
                      9L))
          .isInstanceOf(StatusRuntimeException.class)
          .hasMessageContaining("PERMISSION_DENIED");
      assertThat(rpcCalls).hasValue(1);
      stopServer();
      client.close();
      client = null;
    }
  }

  @Test
  void rejectsUnknownFieldsChangedTupleBindingAndExpirySubstitution() throws Exception {
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    startServer(
        pki.accountServer(),
        response -> response.toBuilder().setUnknownFields(unknown).build(),
        UnaryOperator.identity());
    client = newClient(pki.loggingClient());
    client.initialize();
    assertInvalidResponse();
    stopTransportOnly();

    startServer(
        pki.accountServer(),
        response ->
            response.toBuilder()
                .setAuthorityEvidenceBundle(
                    ByteString.copyFrom(bundleFor(tuple("different-original-request"))))
                .build(),
        UnaryOperator.identity());
    client = newClient(pki.loggingClient());
    client.initialize();
    assertInvalidResponse();
    stopTransportOnly();

    startServer(
        pki.accountServer(),
        response ->
            response.toBuilder()
                .setExpiresAt(
                    Timestamp.newBuilder().setSeconds(EXPIRY.plusSeconds(1).getEpochSecond()))
                .build(),
        UnaryOperator.identity());
    client = newClient(pki.loggingClient());
    client.initialize();
    assertInvalidResponse();
  }

  @Test
  void rejectsAuthenticatedLoggingIdentityMismatchAndExpiredEvidence() throws Exception {
    startServer(
        pki.accountServer(),
        response ->
            response.toBuilder()
                .setAuthenticatedLoggingWorkloadIdentity(
                    "spiffe://firemud/ns/other-test/sa/logging-admin-service")
                .build(),
        UnaryOperator.identity());
    client = newClient(pki.loggingClient());
    client.initialize();
    assertInvalidResponse();
    stopTransportOnly();

    startServer(
        pki.accountServer(),
        response ->
            response.toBuilder()
                .setExpiresAt(
                    Timestamp.newBuilder().setSeconds(NOW.minusSeconds(1).getEpochSecond()))
                .setAuthorityEvidenceBundle(
                    ByteString.copyFrom(bundleFor(tuple("expired-original-request"))))
                .build(),
        UnaryOperator.identity());
    client = newClient(pki.loggingClient());
    client.initialize();
    assertInvalidResponse();
  }

  @Test
  void deniesIssueAndRecoveryBeforeNetworkUnderSqlOrSynchronization() throws Exception {
    startServer(pki.accountServer(), UnaryOperator.identity(), UnaryOperator.identity());
    client = newClient(pki.loggingClient());
    client.initialize();
    var tuple = tuple("pre-network-denial-01");

    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(
            () ->
                client.issueHuman(
                    tuple, CONTROL_UI_JWT, RESERVATION_OWNER_ID, 1L, CLAIM_OWNER_ID, 2L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ambient SQL");
    TransactionSynchronizationManager.clear();

    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThatThrownBy(() -> client.recover(tuple, RESERVATION_OWNER_ID, 1L, CLAIM_OWNER_ID, 2L))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no ambient SQL");
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
      TransactionSynchronizationManager.clear();
    }
    assertThat(rpcCalls).hasValue(0);
  }

  @Test
  void syntheticMutualTlsRejectsMissingClientCertificateWithSharedTlsAssertion() throws Exception {
    startServer(pki.accountServer(), UnaryOperator.identity(), UnaryOperator.identity());
    SSLContext trustOnlyContext = SSLContext.getInstance("TLS");
    var trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
    trustStore.load(null, null);
    trustStore.setCertificateEntry("trusted-test-ca", pki.caCertificate());
    var trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagers.init(trustStore);
    trustOnlyContext.init(null, trustManagers.getTrustManagers(), new SecureRandom());

    Throwable failure =
        catchThrowable(
            () -> {
              try (SSLSocket socket =
                  (SSLSocket)
                      trustOnlyContext
                          .getSocketFactory()
                          .createSocket("127.0.0.1", server.getPort())) {
                socket.setSoTimeout(2_000);
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setProtocols(new String[] {"TLSv1.3"});
                parameters.setApplicationProtocols(new String[] {"h2"});
                socket.setSSLParameters(parameters);
                socket.startHandshake();
                socket.getInputStream().read();
              }
            });

    assertThat(failure).isInstanceOf(SSLHandshakeException.class);
    assertThat(TlsTestSupport.isTlsHandshakeRejection(failure)).isTrue();
    assertThat(rpcCalls).hasValue(0);
  }

  private void assertWrongServerIdentity(TestIdentity serverIdentity) throws Exception {
    startServer(serverIdentity, UnaryOperator.identity(), UnaryOperator.identity());
    client = newClient(pki.loggingClient());
    client.initialize();
    assertThatThrownBy(
            () ->
                client.issueHuman(
                    tuple("wrong-server-" + serverIdentity.alias()),
                    CONTROL_UI_JWT,
                    RESERVATION_OWNER_ID,
                    10L,
                    CLAIM_OWNER_ID,
                    11L))
        .isInstanceOf(StatusRuntimeException.class)
        .hasMessageContaining("UNAUTHENTICATED");
    assertThat(rpcCalls).hasValue(0);
    stopTransportOnly();
  }

  private void assertInvalidResponse() {
    assertThatThrownBy(
            () ->
                client.issueHuman(
                    tuple("invalid-account-result-01"),
                    CONTROL_UI_JWT,
                    RESERVATION_OWNER_ID,
                    12L,
                    CLAIM_OWNER_ID,
                    13L))
        .isInstanceOf(IllegalStateException.class);
    assertThat(rpcCalls).hasValue(1);
  }

  private void startServer(
      TestIdentity serverIdentity,
      UnaryOperator<IssueHumanOperatorAuthorizationReferenceResponse> issueMutation,
      UnaryOperator<RecoverOperatorAuthorizationReferenceResponse> recoverMutation)
      throws Exception {
    rpcCalls = new AtomicInteger();
    receivedIssue = null;
    receivedRecovery = null;
    var service =
        new StartSessionOperatorAuthorizationServiceGrpc
            .StartSessionOperatorAuthorizationServiceImplBase() {
          @Override
          public void issueHumanOperatorAuthorizationReference(
              IssueHumanOperatorAuthorizationReferenceRequest request,
              StreamObserver<IssueHumanOperatorAuthorizationReferenceResponse> observer) {
            rpcCalls.incrementAndGet();
            GrpcPeerIdentity peer = GrpcPeerIdentity.current();
            if (peer == null
                || !LOGGING_URI.equals(peer.uri())
                || !NAMESPACE.equals(peer.namespace())
                || SessionContext.hasAuthenticatedCallerContext()) {
              observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
              return;
            }
            receivedIssue = request.toBuilder().build();
            try {
              observer.onNext(issueMutation.apply(issueResponse(request)));
              observer.onCompleted();
            } catch (RuntimeException failure) {
              observer.onError(Status.INTERNAL.asRuntimeException());
            }
          }

          @Override
          public void recoverOperatorAuthorizationReference(
              RecoverOperatorAuthorizationReferenceRequest request,
              StreamObserver<RecoverOperatorAuthorizationReferenceResponse> observer) {
            rpcCalls.incrementAndGet();
            GrpcPeerIdentity peer = GrpcPeerIdentity.current();
            if (peer == null
                || !LOGGING_URI.equals(peer.uri())
                || !NAMESPACE.equals(peer.namespace())
                || SessionContext.hasAuthenticatedCallerContext()) {
              observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
              return;
            }
            receivedRecovery = request.toBuilder().build();
            try {
              observer.onNext(recoverMutation.apply(recoveryResponse(request)));
              observer.onCompleted();
            } catch (RuntimeException failure) {
              observer.onError(Status.INTERNAL.asRuntimeException());
            }
          }
        };
    ServerInterceptor requestMetadataGuard =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            if (headers.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER))
                != null) {
              call.close(Status.PERMISSION_DENIED, new Metadata());
              return new ServerCall.Listener<>() {};
            }
            return next.startCall(call, headers);
          }
        };
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            serverIdentity.privateKey(), serverIdentity.certificate()),
                        SslProvider.JDK)
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .protocols("TLSv1.3")
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    service, requestMetadataGuard, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private void stopTransportOnly() throws Exception {
    if (client != null) {
      client.close();
      client = null;
    }
    stopServer();
  }

  private void stopServer() throws Exception {
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      server = null;
    }
  }

  private StartSessionOperatorAuthorizationClient newClient(TestIdentity clientIdentity)
      throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("127.0.0.1:" + server.getPort());
    return new StartSessionOperatorAuthorizationClient(
        endpoints,
        pki.clientProperties(clientIdentity),
        new GrpcChannelFactory(),
        BlockingGrpcStubCustomizer.noop(),
        Clock.fixed(NOW, java.time.ZoneOffset.UTC));
  }

  private static IssueHumanOperatorAuthorizationReferenceResponse issueResponse(
      IssueHumanOperatorAuthorizationReferenceRequest request) {
    return IssueHumanOperatorAuthorizationReferenceResponse.newBuilder()
        .setOperatorAuthorizationReference(OPERATOR_REFERENCE)
        .setAuthorizationReferenceFingerprint(FINGERPRINT)
        .setExpiresAt(timestamp(EXPIRY))
        .setAuthorityEvidenceBundle(ByteString.copyFrom(bundleFor(tupleFrom(request))))
        .setBundleReference(bundleReference())
        .setAuthenticatedLoggingWorkloadIdentity(LOGGING_URI)
        .build();
  }

  private static RecoverOperatorAuthorizationReferenceResponse recoveryResponse(
      RecoverOperatorAuthorizationReferenceRequest request) {
    return RecoverOperatorAuthorizationReferenceResponse.newBuilder()
        .setOperatorAuthorizationReference(OPERATOR_REFERENCE)
        .setAuthorizationReferenceFingerprint(FINGERPRINT)
        .setExpiresAt(timestamp(EXPIRY))
        .setAuthorityEvidenceBundle(ByteString.copyFrom(bundleFor(tupleFrom(request))))
        .setBundleReference(bundleReference())
        .setAuthenticatedLoggingWorkloadIdentity(LOGGING_URI)
        .build();
  }

  private static byte[] bundleFor(
      net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple tuple) {
    String tenantId = tuple.action().scope().tenantId().toString();
    Map<String, Object> root =
        new LinkedHashMap<>(
            Map.of(
                "bundleVersion", StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                "authorityScope",
                    Map.of(
                        "scope",
                            Map.of(
                                "tenantId",
                                tenantId,
                                "targetNamespace",
                                tuple.action().scope().targetNamespace()),
                        "actionFamily", tuple.actionFamily(),
                        "applicableAccountId", tuple.actor().accountId().toString(),
                        "applicableTenantId", tenantId),
                "accountProjectionEvidence",
                    Map.of(
                        "sourceType",
                        "ACCOUNT",
                        "sourceEvidenceId",
                        "sha256:" + "c".repeat(64),
                        "sourceEvidenceVersion",
                        "17",
                        "projectionStatus",
                        "CURRENT",
                        "evaluatedAt",
                        NOW.toString(),
                        "expiresAt",
                        EXPIRY.toString()),
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
                        "actorAccountId", tuple.actor().accountId().toString(),
                        "controlUiTokenJti", "a681bba7-c215-4cf1-a35b-14348912cbdc",
                        "role", "tenantAdmin",
                        "accountGeneration", "2",
                        "tenantGeneration", "3")));
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(root));
    } catch (IOException fixtureFailure) {
      throw new IllegalStateException(
          "Synthetic Account authority bundle fixture could not be encoded", fixtureFailure);
    }
  }

  private static AuthorityEvidenceBundleReference bundleReference() {
    return AuthorityEvidenceBundleReference.newBuilder()
        .setBundleVersion(StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION)
        .setSourceVersion("17")
        .setSourceFence("23")
        .setLinearization("18446744073709551615")
        .build();
  }

  private static Timestamp timestamp(Instant value) {
    return Timestamp.newBuilder()
        .setSeconds(value.getEpochSecond())
        .setNanos(value.getNano())
        .build();
  }

  private static StartSessionPreAuthorizationReservationTuple tuple(String requestId) {
    var action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT_ID, NAMESPACE),
            new StartSessionOperatorAction.Target(91L, OWNER_ACCOUNT_ID),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "synthetic Account authorization client transport proof");
    var shared =
        net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple
            .createHuman(requestId, ACTOR_ID, action);
    return StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(shared.canonicalJson());
  }

  private static net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple
      tupleFrom(IssueHumanOperatorAuthorizationReferenceRequest request) {
    return net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple
        .fromCanonicalJson(request.getCanonicalPreAuthorizationTupleBytes().toStringUtf8());
  }

  private static net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple
      tupleFrom(RecoverOperatorAuthorizationReferenceRequest request) {
    return net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple
        .fromCanonicalJson(request.getCanonicalPreAuthorizationTupleBytes().toStringUtf8());
  }

  private record TestIdentity(
      String alias,
      String workloadUri,
      boolean server,
      Path store,
      Path certificatePem,
      Path privateKeyPem,
      PrivateKey privateKey,
      X509Certificate certificate) {}

  private static final class TestPki {
    private final Path directory;
    private final Path caStore;
    private final Path caCertificatePem;
    private final X509Certificate caCertificate;
    private final Map<String, TestIdentity> identities;

    private TestPki(
        Path directory,
        Path caStore,
        Path caCertificatePem,
        X509Certificate caCertificate,
        Map<String, TestIdentity> identities) {
      this.directory = directory;
      this.caStore = caStore;
      this.caCertificatePem = caCertificatePem;
      this.caCertificate = caCertificate;
      this.identities = identities;
    }

    private static TestPki create(Path directory) throws Exception {
      Files.createDirectories(directory);
      Path caStore = directory.resolve("start-session-authorization-test-ca.p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=FireMUD StartSession authorization synthetic test CA",
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
      Path caCertificate = directory.resolve("start-session-authorization-test-ca.crt");
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
          caCertificate.toString(),
          "-rfc");
      var identities = new LinkedHashMap<String, TestIdentity>();
      addIdentity(
          identities,
          issueIdentity(directory, caStore, caCertificate, "account-server", ACCOUNT_URI, true));
      addIdentity(
          identities,
          issueIdentity(directory, caStore, caCertificate, "logging-client", LOGGING_URI, false));
      addIdentity(
          identities,
          issueIdentity(
              directory,
              caStore,
              caCertificate,
              "wrong-service-server",
              "spiffe://firemud/ns/logging-test/sa/account-other",
              true));
      addIdentity(
          identities,
          issueIdentity(
              directory,
              caStore,
              caCertificate,
              "wrong-namespace-server",
              "spiffe://firemud/ns/other-test/sa/account-service",
              true));
      addIdentity(
          identities,
          issueIdentity(
              directory,
              caStore,
              caCertificate,
              "wrong-service-client",
              "spiffe://firemud/ns/logging-test/sa/game-session-service",
              false));
      addIdentity(
          identities,
          issueIdentity(
              directory,
              caStore,
              caCertificate,
              "wrong-namespace-client",
              "spiffe://firemud/ns/other-test/sa/logging-admin-service",
              false));
      return new TestPki(
          directory, caStore, caCertificate, readCertificate(caCertificate), identities);
    }

    private static void addIdentity(Map<String, TestIdentity> identities, TestIdentity identity) {
      identities.put(identity.alias(), identity);
    }

    private TestIdentity accountServer() {
      return identities.get("account-server");
    }

    private TestIdentity loggingClient() {
      return identities.get("logging-client");
    }

    private TestIdentity identity(String alias, String workloadUri, boolean server) {
      TestIdentity identity = identities.get(alias);
      if (identity == null
          || !identity.workloadUri().equals(workloadUri)
          || identity.server() != server) {
        throw new IllegalArgumentException("Unexpected synthetic mTLS identity fixture");
      }
      return identity;
    }

    private X509Certificate caCertificate() {
      return caCertificate;
    }

    private CommonGrpcClientProperties clientProperties(TestIdentity identity) {
      var properties = new CommonGrpcClientProperties();
      properties.setPlaintext(false);
      properties.setCertChain(identity.certificatePem().toString());
      properties.setPrivateKey(identity.privateKeyPem().toString());
      properties.setCaCert(caCertificatePem.toString());
      return properties;
    }

    private static TestIdentity issueIdentity(
        Path directory,
        Path caStore,
        Path caCertificate,
        String alias,
        String workloadUri,
        boolean server)
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
          caCertificate.toString(),
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
      PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, STORE_PASSWORD.toCharArray());
      X509Certificate leaf = (X509Certificate) keyStore.getCertificate(alias);
      Path certificatePem =
          writePem(directory.resolve(alias + "-leaf.pem"), "CERTIFICATE", leaf.getEncoded());
      Path privateKeyPem =
          writePem(
              directory.resolve(alias + "-private.pem"), "PRIVATE KEY", privateKey.getEncoded());
      return new TestIdentity(
          alias, workloadUri, server, store, certificatePem, privateKeyPem, privateKey, leaf);
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
      Path outputFile = Files.createTempFile("firemud-start-session-auth-keytool-", ".log");
      try {
        Process process =
            new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          process.waitFor(5, TimeUnit.SECONDS);
          throw new IllegalStateException("synthetic workload keytool fixture timed out");
        }
        String output = Files.readString(outputFile, StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
          throw new IllegalStateException("synthetic workload keytool fixture failed: " + output);
        }
      } finally {
        Files.deleteIfExists(outputFile);
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
