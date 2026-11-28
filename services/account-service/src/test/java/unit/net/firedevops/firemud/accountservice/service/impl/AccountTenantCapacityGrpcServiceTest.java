package net.firedevops.firemud.accountservice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import java.sql.Connection;
import java.time.Clock;
import java.util.UUID;
import java.util.stream.Stream;
import net.firedevops.firemud.account.v1.CommitTenantCapacityAdmissionRequest;
import net.firedevops.firemud.account.v1.CommitTenantCapacityAdmissionResponse;
import net.firedevops.firemud.accountservice.repository.AccountCanonicalRuntimeMembershipSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository.CapacityException;
import net.firedevops.firemud.accountservice.security.AccountTenantCapacityWorkloadGuard;
import net.firedevops.firemud.accountservice.service.AccountTenantCapacityOwner;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionDigest;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionTestFixture;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec.Failure;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec.Refusal;
import org.jooq.ConnectionRunnable;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled handler and owner SQL-path tests, not physical database or mTLS proof. */
class AccountTenantCapacityGrpcServiceTest {
  private enum RetainedEvidence {
    EXACT_QUOTA_DENIAL,
    EXACT_EXPIRY_DENIAL,
    ABSENT,
    ACCEPTED,
    MATCHING_RESERVATION,
    GENERIC_NO_ACCEPT,
    WRONG_FINGERPRINT,
    WRITABLE_CONNECTION,
    AUTOCOMMIT_CONNECTION
  }

  @Test
  void malformedWireAndStructurallyInvalidRequestsRefuseBeforeOwner() {
    var valid =
        TenantCapacityAdmissionGrpcCodec.toRequest(CapacityAdmissionTestFixture.playerRequest());
    for (var wire :
        new CommitTenantCapacityAdmissionRequest[] {
          null,
          CommitTenantCapacityAdmissionRequest.getDefaultInstance(),
          valid.toBuilder().setCapacityDelta(0).build()
        }) {
      try (var guards = authenticatedCaller()) {
        AccountTenantCapacityOwner owner = mock();
        StreamObserver<CommitTenantCapacityAdmissionResponse> observer = mock();

        new AccountTenantCapacityGrpcService(owner, "prod")
            .commitTenantCapacityAdmission(wire, observer);

        assertFailure(observer, Failure.INVALID_ARGUMENT);
        verifyNoInteractions(owner);
      }
    }
  }

  @ParameterizedTest
  @MethodSource("ownerEvidenceFailures")
  void unexpectedOwnerFailuresAreSanitizedInternal(RuntimeException cause) {
    var request = CapacityAdmissionTestFixture.playerRequest();
    try (var guards = authenticatedCaller()) {
      AccountTenantCapacityOwner owner = mock();
      when(owner.commit(request, CapacityAdmissionTestFixture.PEER)).thenThrow(cause);
      StreamObserver<CommitTenantCapacityAdmissionResponse> observer = mock();

      new AccountTenantCapacityGrpcService(owner, "prod")
          .commitTenantCapacityAdmission(
              TenantCapacityAdmissionGrpcCodec.toRequest(request), observer);

      var error = ArgumentCaptor.forClass(Throwable.class);
      verify(observer).onError(error.capture());
      Status status = Status.fromThrowable(error.getValue());
      assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
      assertThat(status.getDescription()).isNull();
      assertThat(status.getCause()).isNull();
      assertThat(error.getValue().getCause()).isNull();
      verify(observer, never()).onNext(any());
      verify(observer, never()).onCompleted();
      verify(owner).commit(request, CapacityAdmissionTestFixture.PEER);
    }
  }

  @Test
  void quotaExceptionRequiresDurableDecisionIdentity() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> {
              throw new CapacityException(Failure.CAPACITY_QUOTA_EXCEEDED);
            })
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void invalidDomainRefusalIsSanitizedInternal() {
    // Noncreating evidence cannot authorize a quota refusal, even with a decision UUID.
    var request = CapacityAdmissionTestFixture.playerRequest(false);
    try (var guards = authenticatedCaller()) {
      AccountTenantCapacityOwner owner = mock();
      when(owner.commit(request, CapacityAdmissionTestFixture.PEER))
          .thenThrow(
              new CapacityException(Failure.CAPACITY_QUOTA_EXCEEDED, java.util.UUID.randomUUID()));
      StreamObserver<CommitTenantCapacityAdmissionResponse> observer = mock();
      new AccountTenantCapacityGrpcService(owner, "prod")
          .commitTenantCapacityAdmission(
              TenantCapacityAdmissionGrpcCodec.toRequest(request), observer);
      var error = ArgumentCaptor.forClass(Throwable.class);
      verify(observer).onError(error.capture());
      Throwable thrown = error.getValue();
      assertThat(thrown).isInstanceOf(StatusRuntimeException.class);
      assertThat(thrown.getCause()).isNull();
      Status status = ((StatusRuntimeException) thrown).getStatus();
      assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
      assertThat(status.getDescription()).isNull();
      assertThat(status.getCause()).isNull();
      verify(observer, never()).onNext(any());
      verify(observer, never()).onCompleted();
      verify(owner).commit(request, CapacityAdmissionTestFixture.PEER);
    }
  }

  @ParameterizedTest
  @MethodSource("ownerUnavailableFailures")
  void unavailableOwnerFailureKeepsItsPublicNonOkStatus(RuntimeException failure) {
    var request = CapacityAdmissionTestFixture.playerRequest();
    try (var guards = authenticatedCaller()) {
      AccountTenantCapacityOwner owner = mock();
      when(owner.commit(request, CapacityAdmissionTestFixture.PEER)).thenThrow(failure);
      StreamObserver<CommitTenantCapacityAdmissionResponse> observer = mock();
      new AccountTenantCapacityGrpcService(owner, "prod")
          .commitTenantCapacityAdmission(
              TenantCapacityAdmissionGrpcCodec.toRequest(request), observer);
      assertFailure(observer, Failure.ENTITLEMENT_UNAVAILABLE);
    }
  }

  @Test
  void producedBillingDenialReturnsAnExactTypedResponse() {
    Failure code = Failure.TENANT_BILLING_BLOCKED;
    var request = CapacityAdmissionTestFixture.playerRequest(false);
    try (var guards = authenticatedCaller()) {
      AccountTenantCapacityOwner owner = mock();
      when(owner.commit(request, CapacityAdmissionTestFixture.PEER))
          .thenThrow(new CapacityException(code));
      StreamObserver<CommitTenantCapacityAdmissionResponse> observer = mock();
      new AccountTenantCapacityGrpcService(owner, "prod")
          .commitTenantCapacityAdmission(
              TenantCapacityAdmissionGrpcCodec.toRequest(request), observer);
      var response = ArgumentCaptor.forClass(CommitTenantCapacityAdmissionResponse.class);
      var ordered = inOrder(observer);
      ordered.verify(observer).onNext(response.capture());
      assertThat(TenantCapacityAdmissionGrpcCodec.readRefusalDetails(request, response.getValue()))
          .contains(new Refusal(code, null));
      ordered.verify(observer).onCompleted();
      verify(observer, never()).onError(any());
      verifyNoMoreInteractions(observer);
    }
  }

  @Test
  void retainedQuotaDenialCarriesTheExactAccountDecision() {
    var request = CapacityAdmissionTestFixture.playerRequest();
    var decisionId = UUID.randomUUID();
    try (var guards = authenticatedCaller()) {
      AccountTenantCapacityOwner owner = mock();
      when(owner.commit(request, CapacityAdmissionTestFixture.PEER))
          .thenThrow(new CapacityException(Failure.CAPACITY_QUOTA_EXCEEDED, decisionId));
      StreamObserver<CommitTenantCapacityAdmissionResponse> observer = mock();
      new AccountTenantCapacityGrpcService(owner, "prod")
          .commitTenantCapacityAdmission(
              TenantCapacityAdmissionGrpcCodec.toRequest(request), observer);
      var response = ArgumentCaptor.forClass(CommitTenantCapacityAdmissionResponse.class);
      var ordered = inOrder(observer);
      ordered.verify(observer).onNext(response.capture());
      assertThat(TenantCapacityAdmissionGrpcCodec.readRefusalDetails(request, response.getValue()))
          .contains(new Refusal(Failure.CAPACITY_QUOTA_EXCEEDED, decisionId));
      ordered.verify(observer).onCompleted();
      verify(observer, never()).onError(any());
      verifyNoMoreInteractions(observer);
    }
  }

  @ParameterizedTest
  @EnumSource(RetainedEvidence.class)
  void retainedDenialReadUsesOnlyTheImmutableSelect(RetainedEvidence evidence) throws Exception {
    var request = CapacityAdmissionTestFixture.playerRequest();
    var decisionId = UUID.randomUUID();
    var expectedCode =
        evidence == RetainedEvidence.EXACT_EXPIRY_DENIAL
            ? Failure.CAPACITY_EVALUATION_EXPIRED
            : Failure.CAPACITY_QUOTA_EXCEEDED;
    Object[] key = {
      request.identity().tenantId(),
      request.identity().requestId(),
      request.identity().admissionId(),
      request.capacityRearmFence()
    };
    DSLContext dsl = mock();
    Connection connection = mock();
    when(connection.isReadOnly()).thenReturn(evidence != RetainedEvidence.WRITABLE_CONNECTION);
    when(connection.getAutoCommit()).thenReturn(evidence == RetainedEvidence.AUTOCOMMIT_CONNECTION);
    doAnswer(
            invocation -> {
              invocation.getArgument(0, ConnectionRunnable.class).run(connection);
              return null;
            })
        .when(dsl)
        .connection(any(ConnectionRunnable.class));
    Record row = evidence == RetainedEvidence.ABSENT ? null : mock(Record.class);
    if (row != null) {
      when(row.get("admission_attempt_id", UUID.class)).thenReturn(request.admissionAttemptId());
      when(row.get("mutation_digest", String.class)).thenReturn(request.mutationDigest());
      when(row.get("canonical_payload", byte[].class))
          .thenReturn(
              evidence == RetainedEvidence.WRONG_FINGERPRINT
                  ? new byte[] {0}
                  : CapacityAdmissionDigest.preimage(request));
      when(row.get("decision_id", UUID.class)).thenReturn(decisionId);
      when(row.get("decision", String.class))
          .thenReturn(
              evidence == RetainedEvidence.ACCEPTED
                      || evidence == RetainedEvidence.MATCHING_RESERVATION
                  ? "ACCEPTED"
                  : "NO_ACCEPT");
      when(row.get("refusal_code", String.class))
          .thenReturn(
              evidence == RetainedEvidence.ACCEPTED
                      || evidence == RetainedEvidence.MATCHING_RESERVATION
                      || evidence == RetainedEvidence.GENERIC_NO_ACCEPT
                  ? null
                  : expectedCode.name());
    }
    UUID reservationId = UUID.randomUUID();
    String evaluationIdentity = request.evidence().evaluationIdentity();
    String snapshotIdentity = request.evidence().snapshotIdentity();
    if (evidence == RetainedEvidence.MATCHING_RESERVATION) {
      Record reservation = mock(Record.class);
      when(reservation.get("mutation_digest", String.class)).thenReturn(request.mutationDigest());
      when(reservation.get("admission_attempt_id", UUID.class))
          .thenReturn(request.admissionAttemptId());
      when(reservation.get("capacity_rearm_fence", Long.class)).thenReturn(1L);
      when(reservation.get("canonical_payload", byte[].class))
          .thenReturn(CapacityAdmissionDigest.preimage(request));
      when(reservation.get("caller_workload", String.class))
          .thenReturn(request.evidence().callerWorkload());
      when(reservation.get("tenant_uuid", UUID.class)).thenReturn(request.identity().tenantId());
      when(reservation.get("request_id", UUID.class)).thenReturn(request.identity().requestId());
      when(reservation.get("admission_id", UUID.class))
          .thenReturn(request.identity().admissionId());
      when(reservation.get("reservation_id", UUID.class)).thenReturn(reservationId);
      when(reservation.get("reservation_fence", Long.class)).thenReturn(3L);
      when(reservation.get("expires_at", java.time.OffsetDateTime.class))
          .thenReturn(
              java.time.OffsetDateTime.ofInstant(
                  request.evidence().expiresAt(), java.time.ZoneOffset.UTC));
      when(reservation.get("state", String.class)).thenReturn("RESERVED");
      when(reservation.get("evaluation_identity", String.class)).thenReturn(evaluationIdentity);
      Record evaluation = mock(Record.class);
      when(evaluation.get("snapshot_identity", String.class)).thenReturn(snapshotIdentity);
      when(dsl.fetchOne(
              "SELECT * FROM account_tenant_capacity_reservations WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ?",
              request.identity().tenantId(),
              request.identity().requestId(),
              request.identity().admissionId()))
          .thenReturn(reservation);
      when(dsl.fetchOne(
              "SELECT snapshot_identity FROM account_tenant_capacity_evaluations WHERE evaluation_identity = ?",
              evaluationIdentity))
          .thenReturn(evaluation);
    }
    String sql =
        "SELECT * FROM account_capacity_origin_decisions WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ? AND capacity_rearm_fence = ?";
    when(dsl.fetchOne(sql, key))
        .thenAnswer(invocation -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
          assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
          return row;
        });
    PlatformTransactionManager transactions = mock();
    when(transactions.getTransaction(any(TransactionDefinition.class)))
        .thenAnswer(invocation -> {
          var definition = invocation.getArgument(0, TransactionDefinition.class);
          assertThat(definition.getPropagationBehavior())
              .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
          assertThat(definition.getTimeout()).isEqualTo(5);
          assertThat(definition.isReadOnly()).isTrue();
          TransactionSynchronizationManager.setActualTransactionActive(true);
          TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
          return new SimpleTransactionStatus();
        });
    AccountRepository accounts = mock();
    AccountCanonicalRuntimeMembershipSnapshotRepository memberships = mock();
    AccountGameplayDelegationIssuanceRepository issuances = mock();
    AccountConnectScopeRepository scopes = mock();
    Clock clock = mock();
    var owner =
        new AccountTenantCapacityOwner(
            dsl, accounts, memberships, issuances, scopes, transactions, clock, "prod");
    StreamObserver<CommitTenantCapacityAdmissionResponse> observer = mock();
    try (var guards = quotaDenialReader(CapacityAdmissionTestFixture.PEER)) {
      new AccountTenantCapacityGrpcService(owner, "prod")
          .readTenantCapacityAdmissionOutcome(
              TenantCapacityAdmissionGrpcCodec.toRequest(request), observer);
    } finally {
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    if (evidence == RetainedEvidence.MATCHING_RESERVATION) {
      var response = ArgumentCaptor.forClass(CommitTenantCapacityAdmissionResponse.class);
      var ordered = inOrder(observer);
      ordered.verify(observer).onNext(response.capture());
      var result = TenantCapacityAdmissionGrpcCodec.requireResponse(request, response.getValue());
      assertThat(result.identity()).isEqualTo(request.identity());
      assertThat(result.admissionAttemptId()).isEqualTo(request.admissionAttemptId());
      assertThat(result.mutationDigest()).isEqualTo(request.mutationDigest());
      assertThat(result.reservationId()).isEqualTo(reservationId);
      assertThat(result.reservationFence()).isEqualTo(3L);
      assertThat(result.state())
          .isEqualTo(
              net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.State
                  .RESERVED);
      assertThat(result.snapshotIdentity()).isEqualTo(snapshotIdentity);
      assertThat(result.evaluationIdentity()).isEqualTo(evaluationIdentity);
      ordered.verify(observer).onCompleted();
    } else if (evidence == RetainedEvidence.EXACT_QUOTA_DENIAL
        || evidence == RetainedEvidence.EXACT_EXPIRY_DENIAL) {
      var response = ArgumentCaptor.forClass(CommitTenantCapacityAdmissionResponse.class);
      var ordered = inOrder(observer);
      ordered.verify(observer).onNext(response.capture());
      assertThat(TenantCapacityAdmissionGrpcCodec.readRefusalDetails(request, response.getValue()))
          .contains(new Refusal(expectedCode, decisionId));
      ordered.verify(observer).onCompleted();
    } else {
      assertFailure(observer, Failure.ENTITLEMENT_UNAVAILABLE);
    }
    verifyNoMoreInteractions(observer);
    verify(dsl).connection(any(ConnectionRunnable.class));
    if (evidence == RetainedEvidence.WRITABLE_CONNECTION
        || evidence == RetainedEvidence.AUTOCOMMIT_CONNECTION) {
      verify(dsl, never()).fetchOne(sql, key);
    } else {
      verify(dsl).fetchOne(sql, key);
      if (evidence == RetainedEvidence.EXACT_QUOTA_DENIAL
          || evidence == RetainedEvidence.EXACT_EXPIRY_DENIAL
          || evidence == RetainedEvidence.GENERIC_NO_ACCEPT
          || evidence == RetainedEvidence.ACCEPTED
          || evidence == RetainedEvidence.MATCHING_RESERVATION) {
        verify(dsl)
            .fetchOne(
                "SELECT * FROM account_tenant_capacity_reservations WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ?",
                request.identity().tenantId(),
                request.identity().requestId(),
                request.identity().admissionId());
      }
    }
    if (evidence == RetainedEvidence.MATCHING_RESERVATION) {
      verify(dsl)
          .fetchOne(
              "SELECT snapshot_identity FROM account_tenant_capacity_evaluations WHERE evaluation_identity = ?",
              evaluationIdentity);
    }
    verifyNoMoreInteractions(dsl);
    verifyNoInteractions(accounts, memberships, issuances, scopes, clock);
  }

  @Test
  void quotaDenialReadRejectsWrongCallerBindingAndMalformedOriginalBeforeOwner() {
    var request = CapacityAdmissionTestFixture.playerRequest();
    var wire = TenantCapacityAdmissionGrpcCodec.toRequest(request);
    AccountTenantCapacityOwner owner = mock();
    try (var guards = quotaDenialReader("spiffe://firemud/ns/prod/sa/world-management-service")) {
      StreamObserver<CommitTenantCapacityAdmissionResponse> observer = mock();
      new AccountTenantCapacityGrpcService(owner, "prod")
          .readTenantCapacityAdmissionOutcome(wire, observer);
      assertFailure(observer, Failure.PERMISSION_DENIED);
    }
    try (var guards = quotaDenialReader(CapacityAdmissionTestFixture.PEER)) {
      StreamObserver<CommitTenantCapacityAdmissionResponse> observer = mock();
      new AccountTenantCapacityGrpcService(owner, "prod")
          .readTenantCapacityAdmissionOutcome(
              wire.toBuilder().setCapacityDelta(0).build(), observer);
      assertFailure(observer, Failure.INVALID_ARGUMENT);
    }
    verifyNoInteractions(owner);
  }

  private static Stream<RuntimeException> ownerUnavailableFailures() {
    return Stream.of(
        new CapacityException(Failure.ENTITLEMENT_UNAVAILABLE),
        new org.jooq.exception.DataAccessException(
            "Controlled retained refusal failure",
            new java.sql.SQLException("constraint", "23514")),
        new org.springframework.dao.DataAccessResourceFailureException(
            "Controlled storage failure"),
        new org.springframework.transaction.TransactionSystemException(
            "Controlled transaction failure"));
  }

  private static Stream<RuntimeException> ownerEvidenceFailures() {
    return Stream.of(
        new NumberFormatException("Controlled retained billing parse failure"),
        new IllegalArgumentException("Controlled retained enum or evidence parse failure"),
        new ArithmeticException("Controlled retained arithmetic failure"));
  }

  private static MockedConstruction<AccountTenantCapacityWorkloadGuard> authenticatedCaller() {
    return mockConstruction(
        AccountTenantCapacityWorkloadGuard.class,
        (guard, context) ->
            when(guard.requireAuthenticatedCaller()).thenReturn(CapacityAdmissionTestFixture.PEER));
  }

  private static MockedConstruction<AccountTenantCapacityWorkloadGuard> quotaDenialReader(
      String peer) {
    return mockConstruction(
        AccountTenantCapacityWorkloadGuard.class,
        (guard, context) -> when(guard.requireAdmissionOutcomeReader()).thenReturn(peer));
  }

  private static void assertFailure(
      StreamObserver<CommitTenantCapacityAdmissionResponse> observer, Failure expected) {
    var error = ArgumentCaptor.forClass(Throwable.class);
    verify(observer).onError(error.capture());
    var expectedFailure = expected.toStatusException();
    assertThat(Status.fromThrowable(error.getValue()).getCode())
        .isEqualTo(expectedFailure.getStatus().getCode());
    assertThat(StatusProto.fromThrowable(error.getValue()))
        .isEqualTo(StatusProto.fromThrowable(expectedFailure));
    assertThat(error.getValue().getCause()).isNull();
    verify(observer, never()).onNext(any());
    verify(observer, never()).onCompleted();
  }
}
