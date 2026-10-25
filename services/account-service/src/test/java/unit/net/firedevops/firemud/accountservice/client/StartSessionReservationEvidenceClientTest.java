package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.testsupport.AccountStartSessionReservationProofPki;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.v1.ReadCurrentClaimEvidenceRequest;
import net.firedevops.firemud.loggingadmin.v1.ReadCurrentClaimEvidenceResponse;
import net.firedevops.firemud.loggingadmin.v1.StartSessionReservationEvidencePurpose;
import net.firedevops.firemud.loggingadmin.v1.StartSessionReservationEvidenceServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class StartSessionReservationEvidenceClientTest {
  private static final long NOW_EPOCH_MILLIS = 1_800_000_000_000L;
  private static final String REQUEST_ID = "start-session-βeta";
  private static final UUID ACTOR_ID = UUID.fromString("1ea6eb2c-e211-4f4f-a812-14909b9a72b8");
  private static final UUID TENANT_ID = UUID.fromString("5212125e-7d89-4c51-b3a0-bef10e04ccda");
  private static final UUID TARGET_ACCOUNT_ID =
      UUID.fromString("b2a1a560-5e62-4f61-98bf-dae49ba37a38");
  private static final UUID RESERVATION_OWNER_ID =
      UUID.fromString("44997fae-a4c4-45de-8d4f-5e75ad233f0f");
  private static final UUID RECOVERY_OWNER_ID =
      UUID.fromString("c8190808-e325-4d8a-9702-fbf5a53d436d");
  private static final Clock CLOCK =
      Clock.fixed(Instant.ofEpochMilli(NOW_EPOCH_MILLIS), ZoneOffset.UTC);
  private static final String LOGGING_ADMIN_TARGET = "logging-admin.internal:6565";

  @TempDir static Path pkiDirectory;
  private static AccountStartSessionReservationProofPki pki;
  private static CommonGrpcClientProperties accountTls;

  @BeforeAll
  static void createControlledPki() throws Exception {
    pki = AccountStartSessionReservationProofPki.create(pkiDirectory, "world-runtime");
    accountTls = pki.clientProperties();
  }

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
    SessionContext.clear();
  }

  @Test
  void repeatedMtlsReadsBindTheExactTupleClaimPurposeAndDeadline() throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple();
    List<ReadCurrentClaimEvidenceRequest> requests = new ArrayList<>();
    try (MtlsFixture fixture =
        mtlsFixture(
            request -> {
              requests.add(request);
              assertThat(io.grpc.Context.current().getDeadline()).isNotNull();
              return validResponse(request, tuple.mutationDigest());
            })) {
      StartSessionReservationEvidenceClient client = fixture.client();

      ReadCurrentClaimEvidenceResponse issueFirst =
          client.readCurrentClaimEvidence(
              tuple,
              RESERVATION_OWNER_ID,
              1L,
              RESERVATION_OWNER_ID,
              1L,
              StartSessionReservationEvidenceClient.Purpose.ISSUE);
      ReadCurrentClaimEvidenceResponse issueRetry =
          client.readCurrentClaimEvidence(
              tuple,
              RESERVATION_OWNER_ID,
              1L,
              RESERVATION_OWNER_ID,
              1L,
              StartSessionReservationEvidenceClient.Purpose.ISSUE);
      ReadCurrentClaimEvidenceResponse recoveryFirst =
          client.readCurrentClaimEvidence(
              tuple,
              RESERVATION_OWNER_ID,
              1L,
              RECOVERY_OWNER_ID,
              3L,
              StartSessionReservationEvidenceClient.Purpose.RECOVER);
      ReadCurrentClaimEvidenceResponse recoveryRetry =
          client.readCurrentClaimEvidence(
              tuple,
              RESERVATION_OWNER_ID,
              1L,
              RECOVERY_OWNER_ID,
              3L,
              StartSessionReservationEvidenceClient.Purpose.RECOVER);

      assertThat(issueFirst).isEqualTo(issueRetry);
      assertThat(recoveryFirst).isEqualTo(recoveryRetry);
      assertThat(requests).hasSize(4);
      assertThat(requests)
          .allSatisfy(
              request -> {
                assertThat(request.getControlPlaneRequestId()).isEqualTo(REQUEST_ID);
                assertThat(request.getPreAuthorizationTupleJson().toByteArray())
                    .containsExactly(tuple.canonicalJson().getBytes(StandardCharsets.UTF_8));
                assertThat(request.getReservationOwnerId())
                    .isEqualTo(RESERVATION_OWNER_ID.toString());
                assertThat(request.getReservationClaimFence()).isEqualTo(1L);
              });
      assertThat(requests.get(0).getPurpose())
          .isEqualTo(
              StartSessionReservationEvidencePurpose
                  .START_SESSION_RESERVATION_EVIDENCE_PURPOSE_ISSUE);
      assertThat(requests.get(1)).isEqualTo(requests.get(0));
      assertThat(requests.get(2).getClaimOwnerId()).isEqualTo(RECOVERY_OWNER_ID.toString());
      assertThat(requests.get(2).getClaimFence()).isEqualTo(3L);
      assertThat(requests.get(2).getPurpose())
          .isEqualTo(
              StartSessionReservationEvidencePurpose
                  .START_SESSION_RESERVATION_EVIDENCE_PURPOSE_RECOVER);
      assertThat(requests.get(3)).isEqualTo(requests.get(2));
    }
  }

  @Test
  void rejectsEveryResponseIdentityFieldUnknownFieldsAndStaleTimes() throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple();
    AtomicReference<ReadCurrentClaimEvidenceResponse> response = new AtomicReference<>();
    try (MtlsFixture fixture = mtlsFixture(request -> response.get())) {
      StartSessionReservationEvidenceClient client = fixture.client();
      ReadCurrentClaimEvidenceResponse valid =
          validResponse(
              request(tuple, StartSessionReservationEvidenceClient.Purpose.ISSUE),
              tuple.mutationDigest());
      List<ReadCurrentClaimEvidenceResponse> invalid =
          List.of(
              valid.toBuilder().setControlPlaneRequestId(UUID.randomUUID().toString()).build(),
              valid.toBuilder()
                  .setPreAuthorizationTupleJson(ByteString.copyFromUtf8("different tuple"))
                  .build(),
              valid.toBuilder().setMutationDigest("sha256:" + "0".repeat(64)).build(),
              valid.toBuilder().setReservationOwnerId(UUID.randomUUID().toString()).build(),
              valid.toBuilder().setReservationClaimFence(2L).build(),
              valid.toBuilder().setClaimOwnerId(UUID.randomUUID().toString()).build(),
              valid.toBuilder().setClaimFence(2L).build(),
              valid.toBuilder()
                  .setPurpose(
                      StartSessionReservationEvidencePurpose
                          .START_SESSION_RESERVATION_EVIDENCE_PURPOSE_RECOVER)
                  .build(),
              valid.toBuilder().setObservedAtEpochMillis(0L).build(),
              valid.toBuilder()
                  .setClaimExpiresAtEpochMillis(valid.getObservedAtEpochMillis())
                  .build(),
              valid.toBuilder().setClaimExpiresAtEpochMillis(NOW_EPOCH_MILLIS).build(),
              valid.toBuilder()
                  .setUnknownFields(
                      UnknownFieldSet.newBuilder()
                          .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                          .build())
                  .build());

      for (ReadCurrentClaimEvidenceResponse invalidResponse : invalid) {
        response.set(invalidResponse);
        assertThatThrownBy(
                () ->
                    client.readCurrentClaimEvidence(
                        tuple,
                        RESERVATION_OWNER_ID,
                        1L,
                        RESERVATION_OWNER_ID,
                        1L,
                        StartSessionReservationEvidenceClient.Purpose.ISSUE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("stale or mismatched");
      }
    }
  }

  @Test
  void acceptsProducerObservationAfterLocalNowWhenClaimRemainsOrderedAndUnexpired()
      throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple();
    AtomicReference<ReadCurrentClaimEvidenceResponse> response = new AtomicReference<>();
    try (MtlsFixture fixture = mtlsFixture(request -> response.get())) {
      StartSessionReservationEvidenceClient client = fixture.client();
      ReadCurrentClaimEvidenceResponse futureObservation =
          validResponse(
                  request(tuple, StartSessionReservationEvidenceClient.Purpose.ISSUE),
                  tuple.mutationDigest())
              .toBuilder()
              .setObservedAtEpochMillis(NOW_EPOCH_MILLIS + 1L)
              .setClaimExpiresAtEpochMillis(NOW_EPOCH_MILLIS + 30_000L)
              .build();
      response.set(futureObservation);

      assertThat(
              client.readCurrentClaimEvidence(
                  tuple,
                  RESERVATION_OWNER_ID,
                  1L,
                  RESERVATION_OWNER_ID,
                  1L,
                  StartSessionReservationEvidenceClient.Purpose.ISSUE))
          .isEqualTo(futureObservation);
    }
  }

  @Test
  void invalidInputIsRejectedBeforeAnyRpc() throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple();
    AtomicInteger calls = new AtomicInteger();
    try (MtlsFixture fixture =
        mtlsFixture(
            request -> {
              calls.incrementAndGet();
              return validResponse(request, tuple.mutationDigest());
            })) {
      StartSessionReservationEvidenceClient client = fixture.client();

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
      assertThat(calls).hasValue(0);
    }
  }

  @Test
  void propagatesUnavailableAndStaleOwnerStatusesWithoutInterpretingThemAsEvidence()
      throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple();
    AtomicReference<Status> status = new AtomicReference<>(Status.UNAVAILABLE);
    try (MtlsFixture fixture =
        mtlsFixture(
            request -> {
              throw status.get().asRuntimeException();
            })) {
      StartSessionReservationEvidenceClient client = fixture.client();

      assertThatThrownBy(
              () ->
                  client.readCurrentClaimEvidence(
                      tuple,
                      RESERVATION_OWNER_ID,
                      1L,
                      RESERVATION_OWNER_ID,
                      1L,
                      StartSessionReservationEvidenceClient.Purpose.ISSUE))
          .isInstanceOf(StatusRuntimeException.class)
          .extracting(error -> ((StatusRuntimeException) error).getStatus().getCode())
          .isEqualTo(Status.Code.UNAVAILABLE);

      status.set(Status.FAILED_PRECONDITION);
      assertThatThrownBy(
              () ->
                  client.readCurrentClaimEvidence(
                      tuple,
                      RESERVATION_OWNER_ID,
                      1L,
                      RESERVATION_OWNER_ID,
                      1L,
                      StartSessionReservationEvidenceClient.Purpose.ISSUE))
          .isInstanceOf(StatusRuntimeException.class)
          .extracting(error -> ((StatusRuntimeException) error).getStatus().getCode())
          .isEqualTo(Status.Code.FAILED_PRECONDITION);
    }
  }

  @Test
  void usesConfiguredLoggingEndpointAndTlsChannel(@TempDir Path directory) throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setLoggingAdminService(LOGGING_ADMIN_TARGET);
    CommonGrpcClientProperties tls = tlsProperties(directory);
    GrpcChannelFactory channelFactory = spy(new GrpcChannelFactory());
    StartSessionReservationEvidenceClient client =
        new StartSessionReservationEvidenceClient(
            endpoints, tls, channelFactory, "world-runtime", CLOCK);

    try {
      client.init();

      verify(channelFactory)
          .buildChannel(
              eq(LOGGING_ADMIN_TARGET), eq(6565), any(CommonGrpcClientProperties.class), eq(true));
    } finally {
      client.close();
    }
  }

  @Test
  void pinsConfiguredLoggingServerIndependentlyOfMutationTargetNamespace() throws Exception {
    var targetTuple = tuple("other-target-runtime");
    try (var fixture =
        mtlsFixture(request -> validResponse(request, targetTuple.mutationDigest()))) {
      var evidence =
          fixture
              .client()
              .readCurrentClaimEvidence(
                  targetTuple,
                  RESERVATION_OWNER_ID,
                  1L,
                  RESERVATION_OWNER_ID,
                  1L,
                  StartSessionReservationEvidenceClient.Purpose.ISSUE);
      assertThat(evidence.getPreAuthorizationTupleJson().toByteArray())
          .containsExactly(targetTuple.canonicalJson().getBytes(StandardCharsets.UTF_8));
    }
  }

  @Test
  void rejectsWrongAndMissingAuthenticatedServerIdentityBeforeRequestDelivery() throws Exception {
    var calls = new AtomicInteger();
    for (String identity : List.of("wrong-workload", "wrong-namespace", "missing-identity")) {
      try (var fixture =
          mtlsFixture(
              identity,
              request -> {
                calls.incrementAndGet();
                return validResponse(request, tuple().mutationDigest());
              })) {
        assertThatThrownBy(() -> readIssue(fixture.client()))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED))
            .hasMessageContaining("exact authenticated server workload identity");
      }
    }
    assertThat(calls).hasValue(0);
  }

  @Test
  void rejectsAmbientTransactionAndSynchronizationBeforeAnyRpc() throws Exception {
    var calls = new AtomicInteger();
    try (var fixture =
        mtlsFixture(
            request -> {
              calls.incrementAndGet();
              return validResponse(request, tuple().mutationDigest());
            })) {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> readIssue(fixture.client()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no ambient SQL");
      TransactionSynchronizationManager.setActualTransactionActive(false);
      TransactionSynchronizationManager.initSynchronization();
      assertThatThrownBy(() -> readIssue(fixture.client()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no ambient SQL");
      TransactionSynchronizationManager.clearSynchronization();
      SessionContext.setContext("caller", List.of(), Map.of());
      assertThatThrownBy(() -> readIssue(fixture.client()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("workload-only caller");
      SessionContext.clear();
      assertThat(calls).hasValue(0);
      assertThat(readIssue(fixture.client()).getControlPlaneRequestId()).isEqualTo(REQUEST_ID);
      assertThat(calls).hasValue(1);
    }
  }

  @Test
  void requiresUsableMutualTlsBeforeAnyTransportUse() throws Exception {
    var plaintext = accountTls.copy();
    plaintext.setPlaintext(true);
    var missing = accountTls.copy();
    missing.setCertChain(pkiDirectory.resolve("missing-certificate.pem").toString());
    var absentClientKey = accountTls.copy();
    absentClientKey.setPrivateKey(null);
    var absentClientCertificate = accountTls.copy();
    absentClientCertificate.setCertChain(null);
    for (var tls : List.of(plaintext, missing, absentClientKey, absentClientCertificate)) {
      var factory = mock(GrpcChannelFactory.class);
      try (var client =
          new StartSessionReservationEvidenceClient(
              new ServiceEndpointsProperties(), tls, factory, "world-runtime", CLOCK)) {
        assertThatThrownBy(() -> readIssue(client))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("mTLS");
        org.mockito.Mockito.verifyNoInteractions(factory);
      }
    }
  }

  private static ReadCurrentClaimEvidenceResponse readIssue(
      StartSessionReservationEvidenceClient client) {
    return client.readCurrentClaimEvidence(
        tuple(),
        RESERVATION_OWNER_ID,
        1L,
        RESERVATION_OWNER_ID,
        1L,
        StartSessionReservationEvidenceClient.Purpose.ISSUE);
  }

  private static MtlsFixture mtlsFixture(EvidenceHandler handler) throws Exception {
    return mtlsFixture("logging", handler);
  }

  private static MtlsFixture mtlsFixture(String identity, EvidenceHandler handler)
      throws Exception {
    var serverIdentity = pki.server(identity);
    Server server =
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
                    new StartSessionReservationEvidenceServiceGrpc
                        .StartSessionReservationEvidenceServiceImplBase() {
                      @Override
                      public void readCurrentClaimEvidence(
                          ReadCurrentClaimEvidenceRequest request,
                          StreamObserver<ReadCurrentClaimEvidenceResponse> responseObserver) {
                        assertThat(GrpcPeerIdentity.current().uri())
                            .isEqualTo("spiffe://firemud/ns/world-runtime/sa/account-service");
                        try {
                          responseObserver.onNext(handler.read(request));
                          responseObserver.onCompleted();
                        } catch (StatusRuntimeException exception) {
                          responseObserver.onError(exception);
                        }
                      }
                    },
                    new GrpcPeerIdentityInterceptor(),
                    new io.grpc.ServerInterceptor() {
                      @Override
                      public <ReqT, RespT> io.grpc.ServerCall.Listener<ReqT> interceptCall(
                          io.grpc.ServerCall<ReqT, RespT> call,
                          io.grpc.Metadata headers,
                          io.grpc.ServerCallHandler<ReqT, RespT> next) {
                        assertThat(
                                headers.get(
                                    io.grpc.Metadata.Key.of(
                                        "authorization", io.grpc.Metadata.ASCII_STRING_MARSHALLER)))
                            .isNull();
                        return next.startCall(call, headers);
                      }
                    }))
            .build()
            .start();
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setLoggingAdminService("localhost:" + server.getPort());
    var client =
        new StartSessionReservationEvidenceClient(
            endpoints, accountTls, new GrpcChannelFactory(), "world-runtime", CLOCK);
    try {
      client.init();
      return new MtlsFixture(server, client);
    } catch (Exception failure) {
      client.close();
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      throw failure;
    }
  }

  private static ReadCurrentClaimEvidenceRequest request(
      StartSessionPreAuthorizationReservationTuple tuple,
      StartSessionReservationEvidenceClient.Purpose purpose) {
    return ReadCurrentClaimEvidenceRequest.newBuilder()
        .setControlPlaneRequestId(tuple.controlPlaneRequestId())
        .setPreAuthorizationTupleJson(
            ByteString.copyFrom(tuple.canonicalJson().getBytes(StandardCharsets.UTF_8)))
        .setReservationOwnerId(RESERVATION_OWNER_ID.toString())
        .setReservationClaimFence(1L)
        .setClaimOwnerId(
            purpose == StartSessionReservationEvidenceClient.Purpose.ISSUE
                ? RESERVATION_OWNER_ID.toString()
                : RECOVERY_OWNER_ID.toString())
        .setClaimFence(purpose == StartSessionReservationEvidenceClient.Purpose.ISSUE ? 1L : 3L)
        .setPurpose(toWirePurpose(purpose))
        .build();
  }

  private static ReadCurrentClaimEvidenceResponse validResponse(
      ReadCurrentClaimEvidenceRequest request, String mutationDigest) {
    return ReadCurrentClaimEvidenceResponse.newBuilder()
        .setControlPlaneRequestId(request.getControlPlaneRequestId())
        .setPreAuthorizationTupleJson(request.getPreAuthorizationTupleJson())
        .setMutationDigest(mutationDigest)
        .setReservationOwnerId(request.getReservationOwnerId())
        .setReservationClaimFence(request.getReservationClaimFence())
        .setClaimOwnerId(request.getClaimOwnerId())
        .setClaimFence(request.getClaimFence())
        .setClaimExpiresAtEpochMillis(NOW_EPOCH_MILLIS + 30_000L)
        .setObservedAtEpochMillis(NOW_EPOCH_MILLIS - 1_000L)
        .setPurpose(request.getPurpose())
        .build();
  }

  private static StartSessionReservationEvidencePurpose toWirePurpose(
      StartSessionReservationEvidenceClient.Purpose purpose) {
    return switch (purpose) {
      case ISSUE ->
          StartSessionReservationEvidencePurpose.START_SESSION_RESERVATION_EVIDENCE_PURPOSE_ISSUE;
      case RECOVER ->
          StartSessionReservationEvidencePurpose.START_SESSION_RESERVATION_EVIDENCE_PURPOSE_RECOVER;
    };
  }

  private static StartSessionPreAuthorizationReservationTuple tuple() {
    return tuple("world-runtime");
  }

  private static StartSessionPreAuthorizationReservationTuple tuple(String targetNamespace) {
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT_ID, targetNamespace),
            new StartSessionOperatorAction.Target(17L, TARGET_ACCOUNT_ID),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "reservation evidence client test");
    return StartSessionPreAuthorizationReservationTuple.createHuman(REQUEST_ID, ACTOR_ID, action);
  }

  private static CommonGrpcClientProperties tlsProperties(Path directory) throws Exception {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(copyCertificateResource("dev-cert.pem", directory).toString());
    tls.setPrivateKey(copyCertificateResource("dev-key.pem", directory).toString());
    tls.setCaCert(copyCertificateResource("dev-ca.pem", directory).toString());
    return tls;
  }

  private static Path copyCertificateResource(String fileName, Path directory) throws Exception {
    Path destination = directory.resolve(fileName);
    try (InputStream source =
        StartSessionReservationEvidenceClientTest.class.getResourceAsStream("/certs/" + fileName)) {
      if (source == null) {
        throw new IllegalStateException("Missing shared TLS test resource: " + fileName);
      }
      Files.copy(source, destination);
    }
    return destination;
  }

  @FunctionalInterface
  private interface EvidenceHandler {
    ReadCurrentClaimEvidenceResponse read(ReadCurrentClaimEvidenceRequest request);
  }

  private record MtlsFixture(Server server, StartSessionReservationEvidenceClient client)
      implements AutoCloseable {
    @Override
    public void close() throws Exception {
      client.close();
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
