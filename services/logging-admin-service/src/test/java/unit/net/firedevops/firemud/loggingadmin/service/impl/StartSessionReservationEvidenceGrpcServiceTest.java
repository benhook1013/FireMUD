package unit.net.firedevops.firemud.loggingadmin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.ClaimPurpose;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.ClaimState;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.CurrentClaimEvidence;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.Phase;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.ReadPurpose;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.Snapshot;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.State;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.loggingadmin.service.impl.StartSessionReservationEvidenceGrpcService;
import net.firedevops.firemud.loggingadmin.v1.LoggingAdminServiceGrpc;
import net.firedevops.firemud.loggingadmin.v1.PingRequest;
import net.firedevops.firemud.loggingadmin.v1.PingResponse;
import net.firedevops.firemud.loggingadmin.v1.ReadCurrentClaimEvidenceRequest;
import net.firedevops.firemud.loggingadmin.v1.ReadCurrentClaimEvidenceResponse;
import net.firedevops.firemud.loggingadmin.v1.StartSessionReservationEvidencePurpose;
import net.firedevops.firemud.loggingadmin.v1.StartSessionReservationEvidenceServiceGrpc;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;

class StartSessionReservationEvidenceGrpcServiceTest {
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String REQUEST_ID = "start-session/evidence/βeta";
  private static final UUID RESERVATION_OWNER_ID =
      UUID.fromString("c207c44a-92b8-4460-b8f1-53b52d5339f0");
  private static final UUID RECOVERY_OWNER_ID =
      UUID.fromString("160c30d2-4912-4c8d-8957-0538d9462d08");
  private static final long OBSERVED_AT = 1_800_000_000_000L;
  private final StartSessionPreAuthorizationReservationService reservationService =
      mock(StartSessionPreAuthorizationReservationService.class);
  private final StartSessionReservationEvidenceGrpcService service =
      new StartSessionReservationEvidenceGrpcService(reservationService, "test");

  @AfterEach
  void clearAuthenticatedContext() {
    SessionContext.clear();
  }

  @Test
  void returnsExactIssueAndRecoveryEvidenceToSameNamespaceAccountPeer() {
    StartSessionPreAuthorizationReservationTuple tuple = tuple(REQUEST_ID);
    for (StartSessionReservationEvidencePurpose purpose :
        List.of(
            StartSessionReservationEvidencePurpose.START_SESSION_RESERVATION_EVIDENCE_PURPOSE_ISSUE,
            StartSessionReservationEvidencePurpose
                .START_SESSION_RESERVATION_EVIDENCE_PURPOSE_RECOVER)) {
      UUID currentOwner =
          purpose
                  == StartSessionReservationEvidencePurpose
                      .START_SESSION_RESERVATION_EVIDENCE_PURPOSE_ISSUE
              ? RESERVATION_OWNER_ID
              : RECOVERY_OWNER_ID;
      long currentFence = currentOwner.equals(RESERVATION_OWNER_ID) ? 1L : 3L;
      when(reservationService.readCurrentClaimEvidence(
              eq(REQUEST_ID),
              eq(tuple),
              eq(RESERVATION_OWNER_ID),
              eq(1L),
              eq(currentOwner),
              eq(currentFence),
              eq(toPurpose(purpose))))
          .thenReturn(Optional.of(evidence(tuple, currentOwner, currentFence, toPurpose(purpose))));

      Observer observer = call(request(tuple, currentOwner, currentFence, purpose), ACCOUNT_URI);

      assertThat(observer.failure).isNull();
      assertThat(observer.completed).isTrue();
      assertThat(observer.response.getControlPlaneRequestId()).isEqualTo(REQUEST_ID);
      assertThat(observer.response.getPreAuthorizationTupleJson().toByteArray())
          .containsExactly(tuple.canonicalJson().getBytes(StandardCharsets.UTF_8));
      assertThat(observer.response.getMutationDigest()).isEqualTo(tuple.mutationDigest());
      assertThat(observer.response.getReservationOwnerId())
          .isEqualTo(RESERVATION_OWNER_ID.toString());
      assertThat(observer.response.getReservationClaimFence()).isEqualTo(1L);
      assertThat(observer.response.getClaimOwnerId()).isEqualTo(currentOwner.toString());
      assertThat(observer.response.getClaimFence()).isEqualTo(currentFence);
      assertThat(observer.response.getClaimExpiresAtEpochMillis()).isEqualTo(OBSERVED_AT + 30_000L);
      assertThat(observer.response.getObservedAtEpochMillis()).isEqualTo(OBSERVED_AT);
      assertThat(observer.response.getPurpose()).isEqualTo(purpose);
      verify(reservationService)
          .readCurrentClaimEvidence(
              REQUEST_ID,
              tuple,
              RESERVATION_OWNER_ID,
              1L,
              currentOwner,
              currentFence,
              toPurpose(purpose));
    }
  }

  @Test
  void deniesMissingUnknownWrongServiceCrossNamespaceAndUnconfiguredPeersBeforeRead() {
    StartSessionPreAuthorizationReservationTuple tuple = tuple(REQUEST_ID);
    for (String peer :
        new String[] {
          null,
          "spiffe://firemud/ns/other/sa/account-service",
          "spiffe://firemud/ns/test/sa/game-session-service",
          "spiffe://firemud/ns/test/sa/unknown-service"
        }) {
      assertThat(status(call(request(tuple, RESERVATION_OWNER_ID, 1L, issuePurpose()), peer)))
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    StartSessionReservationEvidenceGrpcService unconfigured =
        new StartSessionReservationEvidenceGrpcService(reservationService, "");
    Observer observer = new Observer();
    GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(ACCOUNT_URI).orElseThrow();
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
        .run(
            () ->
                unconfigured.readCurrentClaimEvidence(
                    request(tuple, RESERVATION_OWNER_ID, 1L, issuePurpose()), observer));
    assertThat(status(observer)).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(reservationService);
  }

  @Test
  void deniesHumanCallerContextBeforeOwnerRead() {
    StartSessionPreAuthorizationReservationTuple tuple = tuple(REQUEST_ID);
    SessionContext.setContext("account-uuid", List.of("player"), Map.of());

    assertThat(status(call(request(tuple, RESERVATION_OWNER_ID, 1L, issuePurpose()), ACCOUNT_URI)))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(reservationService);
  }

  @Test
  void acceptsCanonicalNfcUnicodeRequestIdAtExactUtf8ByteBound() {
    String requestId = "é".repeat(64);
    StartSessionPreAuthorizationReservationTuple tuple = tuple(requestId);
    when(reservationService.readCurrentClaimEvidence(
            eq(requestId),
            eq(tuple),
            eq(RESERVATION_OWNER_ID),
            eq(1L),
            eq(RESERVATION_OWNER_ID),
            eq(1L),
            eq(ReadPurpose.ISSUE)))
        .thenReturn(Optional.of(evidence(tuple, RESERVATION_OWNER_ID, 1L, ReadPurpose.ISSUE)));

    Observer observer = call(request(tuple), ACCOUNT_URI);

    assertThat(observer.failure).isNull();
    assertThat(observer.response.getControlPlaneRequestId()).isEqualTo(requestId);
    verify(reservationService)
        .readCurrentClaimEvidence(
            requestId,
            tuple,
            RESERVATION_OWNER_ID,
            1L,
            RESERVATION_OWNER_ID,
            1L,
            ReadPurpose.ISSUE);
  }

  @Test
  void rejectsNonCanonicalOrTamperedRequestsBeforeOwnerRead() {
    StartSessionPreAuthorizationReservationTuple tuple = tuple(REQUEST_ID);
    List<ReadCurrentClaimEvidenceRequest> malformed =
        List.of(
            request(tuple).toBuilder()
                .setControlPlaneRequestId("start-session/e\u0301vidence")
                .build(),
            request(tuple).toBuilder().setControlPlaneRequestId("").build(),
            request(tuple).toBuilder().setReservationOwnerId("not-a-uuid").build(),
            request(tuple).toBuilder().setReservationClaimFence(0L).build(),
            request(tuple).toBuilder().setClaimFence(-1L).build(),
            request(tuple).toBuilder()
                .setPreAuthorizationTupleJson(ByteString.copyFrom(new byte[] {(byte) 0xc3, 0x28}))
                .build(),
            request(tuple).toBuilder()
                .setPreAuthorizationTupleJson(ByteString.copyFromUtf8(tuple.canonicalJson() + " "))
                .build(),
            request(tuple).toBuilder()
                .setPurpose(
                    StartSessionReservationEvidencePurpose
                        .START_SESSION_RESERVATION_EVIDENCE_PURPOSE_UNSPECIFIED)
                .build(),
            request(tuple).toBuilder()
                .setUnknownFields(
                    UnknownFieldSet.newBuilder()
                        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                        .build())
                .build());
    malformed = new ArrayList<>(malformed);
    String overLimitRequestId = "é".repeat(65);
    String tupleLimitRequestId = "start-session/overlimit";
    StartSessionPreAuthorizationReservationTuple tupleForLimit = tuple(tupleLimitRequestId);
    String overLimitTupleJson =
        tupleForLimit
            .canonicalJson()
            .replace(
                "\"requestId\":\"" + tupleLimitRequestId + "\"",
                "\"requestId\":\"" + overLimitRequestId + "\"");
    assertThat(overLimitTupleJson).isNotEqualTo(tupleForLimit.canonicalJson());
    malformed.add(
        request(tupleForLimit).toBuilder()
            .setPreAuthorizationTupleJson(
                ByteString.copyFrom(overLimitTupleJson.getBytes(StandardCharsets.UTF_8)))
            .build());
    malformed.add(request(tuple).toBuilder().setControlPlaneRequestId(overLimitRequestId).build());
    ReadCurrentClaimEvidenceRequest tupleKeyMismatch =
        request(tuple).toBuilder().setControlPlaneRequestId("start-session/evidence/other").build();
    malformed.add(tupleKeyMismatch);

    for (ReadCurrentClaimEvidenceRequest request : malformed) {
      assertThat(status(call(request, ACCOUNT_URI))).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
    verifyNoInteractions(reservationService);
  }

  @Test
  void failsClosedForAbsentStaleMismatchedOrUnavailableOwnerEvidence() {
    StartSessionPreAuthorizationReservationTuple tuple = tuple(REQUEST_ID);
    ReadCurrentClaimEvidenceRequest request =
        request(tuple, RESERVATION_OWNER_ID, 1L, issuePurpose());
    when(reservationService.readCurrentClaimEvidence(
            any(), any(), any(), anyLong(), any(), anyLong(), any()))
        .thenReturn(Optional.empty())
        .thenThrow(
            new StartSessionPreAuthorizationReservationService.StaleReservationClaimException(
                REQUEST_ID))
        .thenThrow(
            new StartSessionPreAuthorizationReservationService.IdempotencyConflictException(
                REQUEST_ID))
        .thenThrow(new DataAccessResourceFailureException("offline"))
        .thenThrow(new TransientDataAccessResourceException("retry the read"));

    assertThat(status(call(request, ACCOUNT_URI))).isEqualTo(Status.Code.NOT_FOUND);
    assertThat(status(call(request, ACCOUNT_URI))).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(status(call(request, ACCOUNT_URI))).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(status(call(request, ACCOUNT_URI))).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(status(call(request, ACCOUNT_URI))).isEqualTo(Status.Code.UNAVAILABLE);
  }

  @Test
  void mapsOnlyConnectionAndTransientSqlFailuresToUnavailable() {
    StartSessionPreAuthorizationReservationTuple tuple = tuple(REQUEST_ID);
    ReadCurrentClaimEvidenceRequest request = request(tuple);
    when(reservationService.readCurrentClaimEvidence(
            any(), any(), any(), anyLong(), any(), anyLong(), any()))
        .thenThrow(
            new DataAccessException("connection failure", new SQLException("offline", "08006")))
        .thenThrow(
            new DataAccessException(
                "transient failure", new SQLTransientException("retry the transaction")))
        .thenThrow(
            new DataAccessException("syntax failure", new SQLException("syntax error", "42601")))
        .thenThrow(
            new DataAccessException(
                "constraint failure", new SQLException("unique constraint", "23505")))
        .thenThrow(
            new DataAccessException("type failure", new SQLException("type mismatch", "42804")))
        .thenThrow(
            new DataAccessException(
                "nested connection failure",
                new IllegalStateException(
                    "driver wrapper", new SQLException("connection reset", "08001"))));

    assertThat(status(call(request, ACCOUNT_URI))).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(status(call(request, ACCOUNT_URI))).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(status(call(request, ACCOUNT_URI))).isEqualTo(Status.Code.INTERNAL);
    assertThat(status(call(request, ACCOUNT_URI))).isEqualTo(Status.Code.INTERNAL);
    assertThat(status(call(request, ACCOUNT_URI))).isEqualTo(Status.Code.INTERNAL);
    assertThat(status(call(request, ACCOUNT_URI))).isEqualTo(Status.Code.UNAVAILABLE);
  }

  @Test
  void canonicalTupleTamperingReachesOwnerComparisonAndConflicts() {
    StartSessionPreAuthorizationReservationTuple tamperedTuple =
        tuple(REQUEST_ID, "changed audit reason");
    when(reservationService.readCurrentClaimEvidence(
            eq(REQUEST_ID),
            eq(tamperedTuple),
            eq(RESERVATION_OWNER_ID),
            eq(1L),
            eq(RESERVATION_OWNER_ID),
            eq(1L),
            eq(ReadPurpose.ISSUE)))
        .thenThrow(
            new StartSessionPreAuthorizationReservationService.IdempotencyConflictException(
                REQUEST_ID));

    Observer observer = call(request(tamperedTuple), ACCOUNT_URI);

    assertThat(status(observer)).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(observer.response).isNull();
    verify(reservationService)
        .readCurrentClaimEvidence(
            REQUEST_ID,
            tamperedTuple,
            RESERVATION_OWNER_ID,
            1L,
            RESERVATION_OWNER_ID,
            1L,
            ReadPurpose.ISSUE);
  }

  @Test
  void exactPublicMethodTraversesJwtAndPeerInterceptorsOverGrpcTransport() throws Exception {
    String serverName = InProcessServerBuilder.generateName();
    Server server =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .intercept(
                new AuthTokenInterceptor(
                    new JwtUtil("a".repeat(64), 3_600_000L),
                    Set.of(
                        "logging_admin.v1.StartSessionReservationEvidenceService/"
                            + "ReadCurrentClaimEvidence")))
            .intercept(new GrpcPeerIdentityInterceptor())
            .addService(service)
            .addService(
                new LoggingAdminServiceGrpc.LoggingAdminServiceImplBase() {
                  @Override
                  public void ping(PingRequest request, StreamObserver<PingResponse> observer) {
                    observer.onNext(PingResponse.newBuilder().setMessage("pong").build());
                    observer.onCompleted();
                  }
                })
            .build()
            .start();
    ManagedChannel channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    try {
      ReadCurrentClaimEvidenceRequest evidenceRequest = request(tuple(REQUEST_ID));
      assertThat(
              transportStatus(
                  () ->
                      StartSessionReservationEvidenceServiceGrpc.newBlockingStub(channel)
                          .readCurrentClaimEvidence(evidenceRequest)))
          .isEqualTo(Status.Code.PERMISSION_DENIED);

      Metadata invalidJwtHeader = new Metadata();
      invalidJwtHeader.put(
          Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER),
          "Bearer deliberately-invalid");
      assertThat(
              transportStatus(
                  () ->
                      StartSessionReservationEvidenceServiceGrpc.newBlockingStub(channel)
                          .withInterceptors(
                              MetadataUtils.newAttachHeadersInterceptor(invalidJwtHeader))
                          .readCurrentClaimEvidence(evidenceRequest)))
          .isEqualTo(Status.Code.PERMISSION_DENIED);

      assertThat(
              transportStatus(
                  () ->
                      LoggingAdminServiceGrpc.newBlockingStub(channel)
                          .ping(PingRequest.getDefaultInstance())))
          .isEqualTo(Status.Code.UNAUTHENTICATED);
      verifyNoInteractions(reservationService);
    } finally {
      channel.shutdownNow();
      server.shutdownNow();
    }
  }

  private Observer call(ReadCurrentClaimEvidenceRequest request, String peerUri) {
    Observer observer = new Observer();
    Runnable invocation = () -> service.readCurrentClaimEvidence(request, observer);
    Optional<GrpcPeerIdentity> peer =
        peerUri == null ? Optional.empty() : GrpcPeerIdentity.parseUri(peerUri);
    if (peer.isEmpty()) {
      invocation.run();
    } else {
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer.orElseThrow()).run(invocation);
    }
    return observer;
  }

  private static ReadCurrentClaimEvidenceRequest request(
      StartSessionPreAuthorizationReservationTuple tuple,
      UUID currentOwner,
      long currentFence,
      StartSessionReservationEvidencePurpose purpose) {
    return ReadCurrentClaimEvidenceRequest.newBuilder()
        .setControlPlaneRequestId(tuple.controlPlaneRequestId())
        .setPreAuthorizationTupleJson(
            ByteString.copyFrom(tuple.canonicalJson().getBytes(StandardCharsets.UTF_8)))
        .setReservationOwnerId(RESERVATION_OWNER_ID.toString())
        .setReservationClaimFence(1L)
        .setClaimOwnerId(currentOwner.toString())
        .setClaimFence(currentFence)
        .setPurpose(purpose)
        .build();
  }

  private static ReadCurrentClaimEvidenceRequest request(
      StartSessionPreAuthorizationReservationTuple tuple) {
    return request(tuple, RESERVATION_OWNER_ID, 1L, issuePurpose());
  }

  private static StartSessionReservationEvidencePurpose issuePurpose() {
    return StartSessionReservationEvidencePurpose.START_SESSION_RESERVATION_EVIDENCE_PURPOSE_ISSUE;
  }

  private static ReadPurpose toPurpose(StartSessionReservationEvidencePurpose purpose) {
    return switch (purpose) {
      case START_SESSION_RESERVATION_EVIDENCE_PURPOSE_ISSUE -> ReadPurpose.ISSUE;
      case START_SESSION_RESERVATION_EVIDENCE_PURPOSE_RECOVER -> ReadPurpose.RECOVER;
      default -> throw new IllegalArgumentException("unsupported purpose");
    };
  }

  private static CurrentClaimEvidence evidence(
      StartSessionPreAuthorizationReservationTuple tuple,
      UUID currentOwner,
      long currentFence,
      ReadPurpose purpose) {
    Snapshot snapshot =
        new Snapshot(
            tuple,
            tuple.mutationDigest(),
            Phase.ACCOUNT_AUTHORIZATION,
            State.AUTHORIZATION_PENDING,
            1L,
            currentFence,
            OBSERVED_AT + 30_000L,
            ClaimState.ACTIVE,
            currentFence == 1L ? ClaimPurpose.ORIGINAL : ClaimPurpose.AUTHORIZATION_RECOVERY);
    return new CurrentClaimEvidence(
        snapshot, RESERVATION_OWNER_ID, currentOwner, purpose, OBSERVED_AT);
  }

  private static StartSessionPreAuthorizationReservationTuple tuple(String requestId) {
    return tuple(requestId, "evidence read test");
  }

  private static StartSessionPreAuthorizationReservationTuple tuple(
      String requestId, String auditReason) {
    SessionContext.setContext(
        "863843ee-f00a-4905-a9ad-11f706f1b693",
        List.of(),
        Map.of("3d3c5ca5-6d43-4db6-821d-8a0de883a467", List.of("tenantAdmin")),
        false,
        null,
        null);
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(
                UUID.fromString("3d3c5ca5-6d43-4db6-821d-8a0de883a467"), "world-runtime"),
            new StartSessionOperatorAction.Target(
                29L, UUID.fromString("be78a8de-a113-48c4-a247-39e3505aed8c")),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            auditReason);
    StartSessionPreAuthorizationReservationTuple tuple =
        StartSessionPreAuthorizationReservationTuple.fromCurrentTenantAdmin(requestId, action);
    SessionContext.clear();
    return tuple;
  }

  private static Status.Code status(Observer observer) {
    return observer.failure;
  }

  private static Status.Code transportStatus(Runnable call) {
    try {
      call.run();
      return Status.Code.OK;
    } catch (StatusRuntimeException ex) {
      return ex.getStatus().getCode();
    }
  }

  private static final class Observer implements StreamObserver<ReadCurrentClaimEvidenceResponse> {
    private ReadCurrentClaimEvidenceResponse response;
    private Status.Code failure;
    private boolean completed;

    @Override
    public void onNext(ReadCurrentClaimEvidenceResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable throwable) {
      failure = Status.fromThrowable(throwable).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
