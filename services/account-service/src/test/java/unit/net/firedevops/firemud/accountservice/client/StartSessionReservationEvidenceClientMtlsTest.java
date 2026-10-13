package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
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
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.loggingadmin.v1.ReadCurrentClaimEvidenceRequest;
import net.firedevops.firemud.loggingadmin.v1.ReadCurrentClaimEvidenceResponse;
import net.firedevops.firemud.loggingadmin.v1.StartSessionReservationEvidencePurpose;
import net.firedevops.firemud.loggingadmin.v1.StartSessionReservationEvidenceServiceGrpc;
import net.firedevops.firemud.test.TlsTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Synthetic loopback mutual-TLS proof; the handler does not read Logging/Admin owner state. */
class StartSessionReservationEvidenceClientMtlsTest {
  private static final String NAMESPACE = "world-runtime";
  private static final String LOGGING_ADMIN_URI =
      "spiffe://firemud/ns/world-runtime/sa/logging-admin-service";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/world-runtime/sa/account-service";
  private static final String REQUEST_ID = "start-session-βeta";
  private static final String STORE_PASSWORD = "synthetic-claim-evidence-test";
  private static final long NOW_EPOCH_MILLIS = 1_800_000_000_000L;
  private static final UUID ACTOR_ID = UUID.fromString("1ea6eb2c-e211-4f4f-a812-14909b9a72b8");
  private static final UUID TENANT_ID = UUID.fromString("5212125e-7d89-4c51-b3a0-bef10e04ccda");
  private static final UUID TARGET_ACCOUNT_ID =
      UUID.fromString("b2a1a560-5e62-4f61-98bf-dae49ba37a38");
  private static final UUID RESERVATION_OWNER_ID =
      UUID.fromString("44997fae-a4c4-45de-8d4f-5e75ad233f0f");
  private static final UUID RECOVERY_OWNER_ID =
      UUID.fromString("c8190808-e325-4d8a-9702-fbf5a53d436d");

  @TempDir static Path temporaryDirectory;

  private static TestPki pki;
  private final AtomicInteger handlerCalls = new AtomicInteger();
  private final AtomicInteger callerDenials = new AtomicInteger();
  private final List<ReadCurrentClaimEvidenceRequest> receivedRequests =
      new CopyOnWriteArrayList<>();
  private final AtomicReference<UnaryOperator<ReadCurrentClaimEvidenceResponse>> responseMutation =
      new AtomicReference<>(UnaryOperator.identity());
  private final AtomicReference<Status> responseStatus = new AtomicReference<>();
  private Server server;
  private StartSessionReservationEvidenceClient client;

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
    stopServer();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void repeatedReadsPreserveTupleClaimPurposeAndFiveSecondDeadline() throws Exception {
    startServer(pki.loggingAdminServer());
    client = newClient(pki.accountClient());
    client.init();
    var tuple = tuple();

    var issueFirst = read(tuple, StartSessionReservationEvidenceClient.Purpose.ISSUE);
    var issueRetry = read(tuple, StartSessionReservationEvidenceClient.Purpose.ISSUE);
    var recoveryFirst = read(tuple, StartSessionReservationEvidenceClient.Purpose.RECOVER);
    var recoveryRetry = read(tuple, StartSessionReservationEvidenceClient.Purpose.RECOVER);

    assertThat(issueFirst).isEqualTo(issueRetry);
    assertThat(recoveryFirst).isEqualTo(recoveryRetry);
    assertThat(receivedRequests).hasSize(4);
    assertThat(handlerCalls).hasValue(4);
    assertThat(receivedRequests)
        .allSatisfy(
            request -> {
              assertThat(request.getControlPlaneRequestId()).isEqualTo(REQUEST_ID);
              assertThat(request.getPreAuthorizationTupleJson().toByteArray())
                  .containsExactly(tuple.canonicalJson().getBytes(StandardCharsets.UTF_8));
              assertThat(request.getReservationOwnerId())
                  .isEqualTo(RESERVATION_OWNER_ID.toString());
              assertThat(request.getReservationClaimFence()).isEqualTo(1L);
            });
    assertThat(receivedRequests.get(0).getPurpose())
        .isEqualTo(
            StartSessionReservationEvidencePurpose
                .START_SESSION_RESERVATION_EVIDENCE_PURPOSE_ISSUE);
    assertThat(receivedRequests.get(1)).isEqualTo(receivedRequests.get(0));
    assertThat(receivedRequests.get(2).getClaimOwnerId()).isEqualTo(RECOVERY_OWNER_ID.toString());
    assertThat(receivedRequests.get(2).getClaimFence()).isEqualTo(3L);
    assertThat(receivedRequests.get(2).getPurpose())
        .isEqualTo(
            StartSessionReservationEvidencePurpose
                .START_SESSION_RESERVATION_EVIDENCE_PURPOSE_RECOVER);
    assertThat(receivedRequests.get(3)).isEqualTo(receivedRequests.get(2));
  }

  @Test
  void rejectsMismatchedEchoUnknownFieldsAndStaleLeaseTimes() throws Exception {
    startServer(pki.loggingAdminServer());
    client = newClient(pki.accountClient());
    client.init();
    var tuple = tuple();
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    List<UnaryOperator<ReadCurrentClaimEvidenceResponse>> invalidResponses =
        List.of(
            response -> response.toBuilder().setControlPlaneRequestId("different-request").build(),
            response ->
                response.toBuilder()
                    .setPreAuthorizationTupleJson(ByteString.copyFromUtf8("different tuple"))
                    .build(),
            response -> response.toBuilder().setMutationDigest("sha256:" + "0".repeat(64)).build(),
            response ->
                response.toBuilder().setReservationOwnerId(UUID.randomUUID().toString()).build(),
            response -> response.toBuilder().setReservationClaimFence(2L).build(),
            response -> response.toBuilder().setClaimOwnerId(UUID.randomUUID().toString()).build(),
            response -> response.toBuilder().setClaimFence(2L).build(),
            response ->
                response.toBuilder()
                    .setPurpose(
                        StartSessionReservationEvidencePurpose
                            .START_SESSION_RESERVATION_EVIDENCE_PURPOSE_RECOVER)
                    .build(),
            response -> response.toBuilder().setObservedAtEpochMillis(0L).build(),
            response ->
                response.toBuilder()
                    .setClaimExpiresAtEpochMillis(response.getObservedAtEpochMillis())
                    .build(),
            response -> response.toBuilder().setClaimExpiresAtEpochMillis(NOW_EPOCH_MILLIS).build(),
            response -> response.toBuilder().setUnknownFields(unknown).build());

    for (UnaryOperator<ReadCurrentClaimEvidenceResponse> invalidResponse : invalidResponses) {
      responseMutation.set(invalidResponse);
      assertThatThrownBy(() -> read(tuple, StartSessionReservationEvidenceClient.Purpose.ISSUE))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("stale or mismatched");
    }
    assertThat(handlerCalls).hasValue(invalidResponses.size());
  }

  @Test
  void acceptsFutureObservationWhenProducerIntervalAndAccountExpiryAreLive() throws Exception {
    startServer(pki.loggingAdminServer());
    client = newClient(pki.accountClient());
    client.init();
    responseMutation.set(
        response -> response.toBuilder().setObservedAtEpochMillis(NOW_EPOCH_MILLIS + 1L).build());

    ReadCurrentClaimEvidenceResponse evidence =
        read(tuple(), StartSessionReservationEvidenceClient.Purpose.ISSUE);

    assertThat(evidence.getObservedAtEpochMillis()).isEqualTo(NOW_EPOCH_MILLIS + 1L);
    assertThat(evidence.getClaimExpiresAtEpochMillis())
        .isGreaterThan(evidence.getObservedAtEpochMillis())
        .isGreaterThan(NOW_EPOCH_MILLIS);
    assertThat(handlerCalls).hasValue(1);
  }

  @Test
  void rejectsInvalidInputBeforeAnyRpc() throws Exception {
    startServer(pki.loggingAdminServer());
    client = newClient(pki.accountClient());
    client.init();
    var tuple = tuple();

    assertThatThrownBy(
            () ->
                client.readCurrentClaimEvidence(
                    null,
                    RESERVATION_OWNER_ID,
                    1L,
                    RESERVATION_OWNER_ID,
                    1L,
                    StartSessionReservationEvidenceClient.Purpose.ISSUE))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                client.readCurrentClaimEvidence(
                    tuple,
                    RESERVATION_OWNER_ID,
                    0L,
                    RESERVATION_OWNER_ID,
                    1L,
                    StartSessionReservationEvidenceClient.Purpose.ISSUE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                client.readCurrentClaimEvidence(
                    tuple,
                    RESERVATION_OWNER_ID,
                    1L,
                    RECOVERY_OWNER_ID,
                    3L,
                    StartSessionReservationEvidenceClient.Purpose.ISSUE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                client.readCurrentClaimEvidence(
                    tuple,
                    RESERVATION_OWNER_ID,
                    1L,
                    RESERVATION_OWNER_ID,
                    1L,
                    StartSessionReservationEvidenceClient.Purpose.RECOVER))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(handlerCalls).hasValue(0);
  }

  @Test
  void propagatesUnavailableAndStaleOwnerStatusesWithoutInterpretingThemAsEvidence()
      throws Exception {
    startServer(pki.loggingAdminServer());
    client = newClient(pki.accountClient());
    client.init();
    responseStatus.set(Status.UNAVAILABLE);

    assertThatThrownBy(() -> read(tuple(), StartSessionReservationEvidenceClient.Purpose.ISSUE))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            error ->
                assertThat(Status.fromThrowable(error).getCode())
                    .isEqualTo(Status.Code.UNAVAILABLE));

    responseStatus.set(Status.FAILED_PRECONDITION);
    assertThatThrownBy(() -> read(tuple(), StartSessionReservationEvidenceClient.Purpose.ISSUE))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            error ->
                assertThat(Status.fromThrowable(error).getCode())
                    .isEqualTo(Status.Code.FAILED_PRECONDITION));
    assertThat(handlerCalls).hasValue(2);
  }

  @Test
  void rejectsWrongLoggingServerIdentityEvenWhenCertificateChainsToSameCa() throws Exception {
    for (TestIdentity wrongServer : List.of(pki.wrongServiceServer(), pki.wrongNamespaceServer())) {
      startServer(wrongServer);
      client = newClient(pki.accountClient());
      client.init();

      assertThatThrownBy(() -> read(tuple(), StartSessionReservationEvidenceClient.Purpose.ISSUE))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              error ->
                  assertThat(Status.fromThrowable(error).getCode())
                      .isEqualTo(Status.Code.UNAUTHENTICATED));
      assertThat(handlerCalls).hasValue(0);
      stopClientAndServer();
    }
  }

  @Test
  void rejectsWrongAccountCallerServiceAndNamespaceAtSyntheticReceiverBoundary() throws Exception {
    startServer(pki.loggingAdminServer());
    for (TestIdentity wrongCaller : List.of(pki.wrongServiceClient(), pki.wrongNamespaceClient())) {
      client = newClient(wrongCaller);
      client.init();

      assertThatThrownBy(() -> read(tuple(), StartSessionReservationEvidenceClient.Purpose.ISSUE))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              error ->
                  assertThat(Status.fromThrowable(error).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));
      assertThat(handlerCalls).hasValue(0);
      assertThat(callerDenials).hasValue(1);
      stopClient();
      callerDenials.set(0);
    }
  }

  @Test
  void requiresClientCertificateOnTheSyntheticLoggingServer() throws Exception {
    startServer(pki.loggingAdminServer());
    SSLContext trustOnlyContext = SSLContext.getInstance("TLS");
    var trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
    trustStore.load(null, STORE_PASSWORD.toCharArray());
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
    assertThat(handlerCalls).hasValue(0);
  }

  @Test
  void rejectsPlaintextAndAmbientSqlOrSynchronizationBeforeNetwork() throws Exception {
    startServer(pki.loggingAdminServer());
    var plaintextProperties = new CommonGrpcClientProperties();
    plaintextProperties.setPlaintext(true);
    client = newClient(plaintextProperties);
    client.init();
    assertThatThrownBy(() -> read(tuple(), StartSessionReservationEvidenceClient.Purpose.ISSUE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("requires workload mTLS");
    assertThat(handlerCalls).hasValue(0);
    stopClient();

    var missingFiles = new CommonGrpcClientProperties();
    missingFiles.setPlaintext(false);
    client = newClient(missingFiles);
    assertThatThrownBy(() -> read(tuple(), StartSessionReservationEvidenceClient.Purpose.ISSUE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("readable file-backed workload mTLS");
    assertThat(handlerCalls).hasValue(0);
    stopClient();

    client = newClient(pki.accountClient());
    client.init();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> read(tuple(), StartSessionReservationEvidenceClient.Purpose.ISSUE))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no ambient SQL or synchronization");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThatThrownBy(() -> read(tuple(), StartSessionReservationEvidenceClient.Purpose.ISSUE))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no ambient SQL or synchronization");
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    assertThat(handlerCalls).hasValue(0);
  }

  private ReadCurrentClaimEvidenceResponse read(
      StartSessionPreAuthorizationReservationTuple tuple,
      StartSessionReservationEvidenceClient.Purpose purpose) {
    boolean issue = purpose == StartSessionReservationEvidenceClient.Purpose.ISSUE;
    return client.readCurrentClaimEvidence(
        tuple,
        RESERVATION_OWNER_ID,
        1L,
        issue ? RESERVATION_OWNER_ID : RECOVERY_OWNER_ID,
        issue ? 1L : 3L,
        purpose);
  }

  private void startServer(TestIdentity serverIdentity) throws Exception {
    handlerCalls.set(0);
    callerDenials.set(0);
    receivedRequests.clear();
    responseMutation.set(UnaryOperator.identity());
    responseStatus.set(null);
    var service =
        new StartSessionReservationEvidenceServiceGrpc
            .StartSessionReservationEvidenceServiceImplBase() {
          @Override
          public void readCurrentClaimEvidence(
              ReadCurrentClaimEvidenceRequest request,
              StreamObserver<ReadCurrentClaimEvidenceResponse> observer) {
            GrpcPeerIdentity peer = GrpcPeerIdentity.current();
            if (peer == null || !ACCOUNT_URI.equals(peer.uri())) {
              callerDenials.incrementAndGet();
              observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
              return;
            }
            handlerCalls.incrementAndGet();
            receivedRequests.add(request);
            var deadline = Context.current().getDeadline();
            if (deadline == null
                || deadline.timeRemaining(TimeUnit.MILLISECONDS) <= 0L
                || deadline.timeRemaining(TimeUnit.MILLISECONDS) > 5_000L) {
              observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
              return;
            }
            Status failureStatus = responseStatus.get();
            if (failureStatus != null) {
              observer.onError(failureStatus.asRuntimeException());
              return;
            }
            observer.onNext(responseMutation.get().apply(validResponse(request)));
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
                    .protocols("TLSv1.3")
                    .build())
            .addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
  }

  private StartSessionReservationEvidenceClient newClient(TestIdentity identity) throws Exception {
    return newClient(pki.clientProperties(temporaryDirectory, identity));
  }

  private StartSessionReservationEvidenceClient newClient(
      CommonGrpcClientProperties tlsProperties) {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setLoggingAdminService("localhost:" + server.getPort());
    return new StartSessionReservationEvidenceClient(
        endpoints,
        tlsProperties,
        new GrpcChannelFactory(),
        BlockingGrpcStubCustomizer.noop(),
        Clock.fixed(Instant.ofEpochMilli(NOW_EPOCH_MILLIS), ZoneOffset.UTC));
  }

  private void stopClientAndServer() throws Exception {
    stopClient();
    stopServer();
  }

  private void stopClient() throws Exception {
    if (client != null) {
      client.close();
      client = null;
    }
  }

  private void stopServer() throws Exception {
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      server = null;
    }
  }

  private static ReadCurrentClaimEvidenceResponse validResponse(
      ReadCurrentClaimEvidenceRequest request) {
    return ReadCurrentClaimEvidenceResponse.newBuilder()
        .setControlPlaneRequestId(request.getControlPlaneRequestId())
        .setPreAuthorizationTupleJson(request.getPreAuthorizationTupleJson())
        .setMutationDigest(tuple().mutationDigest())
        .setReservationOwnerId(request.getReservationOwnerId())
        .setReservationClaimFence(request.getReservationClaimFence())
        .setClaimOwnerId(request.getClaimOwnerId())
        .setClaimFence(request.getClaimFence())
        .setClaimExpiresAtEpochMillis(NOW_EPOCH_MILLIS + 30_000L)
        .setObservedAtEpochMillis(NOW_EPOCH_MILLIS - 1_000L)
        .setPurpose(request.getPurpose())
        .build();
  }

  private static StartSessionPreAuthorizationReservationTuple tuple() {
    var action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT_ID, NAMESPACE),
            new StartSessionOperatorAction.Target(17L, TARGET_ACCOUNT_ID),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "reservation evidence client mTLS test");
    return StartSessionPreAuthorizationReservationTuple.createHuman(REQUEST_ID, ACTOR_ID, action);
  }

  private record TestIdentity(String alias, PrivateKey privateKey, X509Certificate certificate) {}

  private record TestPki(
      X509Certificate caCertificate,
      TestIdentity loggingAdminServer,
      TestIdentity wrongServiceServer,
      TestIdentity wrongNamespaceServer,
      TestIdentity accountClient,
      TestIdentity wrongServiceClient,
      TestIdentity wrongNamespaceClient) {
    private static TestPki create(Path directory) throws Exception {
      Path caStore = directory.resolve("claim-evidence-test-ca.p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=FireMUD claim evidence test CA",
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
      Path caFile = directory.resolve("claim-evidence-test-ca.crt");
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
          issueIdentity(directory, caStore, caFile, "logging-server", LOGGING_ADMIN_URI, true),
          issueIdentity(
              directory,
              caStore,
              caFile,
              "wrong-service-server",
              "spiffe://firemud/ns/world-runtime/sa/account-service",
              true),
          issueIdentity(
              directory,
              caStore,
              caFile,
              "wrong-namespace-server",
              "spiffe://firemud/ns/other-runtime/sa/logging-admin-service",
              true),
          issueIdentity(directory, caStore, caFile, "account-client", ACCOUNT_URI, false),
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
              "wrong-namespace-client",
              "spiffe://firemud/ns/other-runtime/sa/account-service",
              false));
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
              directory.resolve("claim-evidence-client-ca.crt"),
              "CERTIFICATE",
              caCertificate.getEncoded());
      var properties = new CommonGrpcClientProperties();
      properties.setCertChain(certificate.toString());
      properties.setPrivateKey(privateKey.toString());
      properties.setCaCert(caFile.toString());
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
