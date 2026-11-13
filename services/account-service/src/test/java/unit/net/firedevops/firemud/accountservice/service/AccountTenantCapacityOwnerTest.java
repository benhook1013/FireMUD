package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountCanonicalRuntimeMembershipSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository;
import net.firedevops.firemud.accountservice.service.AccountTenantCapacityOwner;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.TenantBillingCutoff;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Snapshot;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionDigest;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionTestFixture;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec.Failure;
import org.jooq.ConnectionRunnable;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Controlled ordering/denial only. Physical transaction and positive current-source proof are
 * separate.
 */
class AccountTenantCapacityOwnerTest {
  @AfterEach
  void clear() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @Test
  void sharedRejectedUsageVectorsReachAccountReservationGuardWithoutWrites() throws Exception {
    var resource = getClass().getResourceAsStream("/capacity/capacity-admission-v1-vectors.json");
    assertThat(resource).isNotNull();
    try (resource) {
      var corpus = JsonMapper.builder().build().readTree(resource);
      for (var vector : corpus.path("rejectedUsage")) {
        DSLContext dsl = mock(DSLContext.class);
        Record usage = mock(Record.class);
        when(usage.get("held_units", Long.class))
            .thenReturn(TenantCapacityAdmission.decimal(vector.path("held").asText()));
        when(usage.get("used_units", Long.class))
            .thenReturn(TenantCapacityAdmission.decimal(vector.path("usage").asText()));
        var request =
            CapacityAdmissionTestFixture.worldRequest(
                TenantCapacityAdmission.decimal(vector.path("capacityDelta").asText()));
        request.requireCommittable();
        Record databaseTime = mock(Record.class);
        when(databaseTime.get("observed_at", java.time.OffsetDateTime.class))
            .thenReturn(request.evidence().evaluatedAt().atOffset(java.time.ZoneOffset.UTC));
        when(dsl.fetchSingle("SELECT clock_timestamp() AS observed_at")).thenReturn(databaseTime);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
          assertThat(
                  new AccountTenantCapacityRepository(dsl)
                      .createIfQuotaAvailable(request, usage, request.evidence().expiresAt()))
              .as(vector.path("name").asText())
              .isEmpty();
          verify(dsl, never()).execute(anyString(), any(Object[].class));
        } finally {
          TransactionSynchronizationManager.setActualTransactionActive(false);
        }
      }
    }
  }

  @Test
  void expiryWhileWaitingForUsageDoesNotReserveCapacity() {
    var request = CapacityAdmissionTestFixture.playerRequest();
    DSLContext dsl = mock(DSLContext.class);
    Record usage = mock(Record.class);
    Record databaseTime = mock(Record.class);
    when(databaseTime.get("observed_at", java.time.OffsetDateTime.class))
        .thenReturn(AccountTenantCapacityRepository.at(request.evidence().expiresAt()));
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(usage);
    when(dsl.fetchSingle("SELECT clock_timestamp() AS observed_at")).thenReturn(databaseTime);
    var repository = new AccountTenantCapacityRepository(dsl);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(
            () ->
                repository.createIfQuotaAvailable(
                    request, repository.usage(request), request.evidence().expiresAt()))
        .isInstanceOfSatisfying(
            AccountTenantCapacityRepository.CapacityException.class,
            failure -> assertThat(failure.code()).isEqualTo(Failure.CAPACITY_ADMISSION_STALE));
    var order = inOrder(dsl);
    order.verify(dsl).fetchOne(anyString(), any(Object[].class));
    order.verify(dsl).fetchSingle("SELECT clock_timestamp() AS observed_at");
    verify(dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void exactReplayPrecedesFreshnessCurrentSourcesAndUsageEvenAfterOriginalExpiry()
      throws Exception {
    var f = new Fixture(true);
    var result = f.owner.commit(f.request, f.request.evidence().callerWorkload());
    assertThat(result.reservationId()).isEqualTo(f.reservation);
    assertThat(f.owner.commit(f.request, f.request.evidence().callerWorkload())).isEqualTo(result);
    verifyNoInteractions(f.accounts, f.memberships, f.issuances, f.scopes);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void onlyTheNamedDeferredFreshnessViolationMapsToStaleAdmission() throws Exception {
    for (String constraint :
        new String[] {"account_tenant_capacity_attempt_freshness", "unrelated_constraint"}) {
      var f = new Fixture(true);
      var violation = mock(PSQLException.class);
      var diagnostic = mock(ServerErrorMessage.class);
      when(violation.getSQLState()).thenReturn("23514");
      when(violation.getServerErrorMessage()).thenReturn(diagnostic);
      when(diagnostic.getConstraint()).thenReturn(constraint);
      var commitFailure =
          new org.springframework.transaction.TransactionSystemException(
              "Commit refused", violation);
      f.transactions.commitFailure = commitFailure;

      var result =
          assertThatThrownBy(
              () -> f.owner.commit(f.request, f.request.evidence().callerWorkload()));
      if ("account_tenant_capacity_attempt_freshness".equals(constraint)) {
        result.isInstanceOfSatisfying(
            AccountTenantCapacityRepository.CapacityException.class,
            failure -> assertThat(failure.code()).isEqualTo(Failure.CAPACITY_ADMISSION_STALE));
      } else {
        result.isSameAs(commitFailure);
      }
    }
  }

  @Test
  void genericNoAcceptCannotReplayAsQuotaDenial() throws Exception {
    var f = new Fixture(false);
    f.existing = false;
    Record genericRefusal = mock(Record.class);
    UUID decisionId = UUID.randomUUID();
    when(genericRefusal.get("tenant_uuid", UUID.class)).thenReturn(f.request.identity().tenantId());
    when(genericRefusal.get("request_id", UUID.class)).thenReturn(f.request.identity().requestId());
    when(genericRefusal.get("admission_id", UUID.class))
        .thenReturn(f.request.identity().admissionId());
    when(genericRefusal.get("admission_attempt_id", UUID.class))
        .thenReturn(f.request.admissionAttemptId());
    when(genericRefusal.get("mutation_digest", String.class))
        .thenReturn(f.request.mutationDigest());
    when(genericRefusal.get("canonical_payload", byte[].class))
        .thenReturn(CapacityAdmissionDigest.preimage(f.request));
    when(genericRefusal.get("decision", String.class)).thenReturn("NO_ACCEPT");
    when(genericRefusal.get("decision_id", UUID.class)).thenReturn(decisionId);
    when(genericRefusal.get("refusal_code", String.class)).thenReturn(null);
    f.originDecision = genericRefusal;

    assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
        .isInstanceOfSatisfying(
            AccountTenantCapacityRepository.CapacityException.class,
            failure -> assertThat(failure.code()).isEqualTo(Failure.IDEMPOTENCY_CONFLICT));
    verifyNoInteractions(f.accounts, f.memberships, f.issuances, f.scopes);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void repeatedSerializationFailuresRetryAndReplayInAFreshTransaction() throws Exception {
    var f = new Fixture(true);
    f.existing = false;
    var aborted = serializationFailure();
    var abortedAttempts = new AtomicInteger();
    when(f.accounts.findByAccountUuidForUpdate(CapacityAdmissionTestFixture.ACCOUNT))
        .thenAnswer(
            invocation -> {
              if (abortedAttempts.incrementAndGet() == 2) f.existing = true;
              throw aborted;
            });

    assertThat(f.owner.commit(f.request, f.request.evidence().callerWorkload()).reservationId())
        .isEqualTo(f.reservation);
    verify(f.accounts, times(2)).findByAccountUuidForUpdate(CapacityAdmissionTestFixture.ACCOUNT);
    verify(f.dsl, times(3))
        .fetchOne(
            startsWith("SELECT * FROM account_tenant_capacity_reservations "), any(Object[].class));
    verifyNoInteractions(f.memberships, f.issuances, f.scopes);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void repeatedSerializationFailuresStopAtTheAttemptLimit() throws Exception {
    var f = new Fixture(true);
    f.existing = false;
    var aborted = serializationFailure();
    when(f.accounts.findByAccountUuidForUpdate(CapacityAdmissionTestFixture.ACCOUNT))
        .thenThrow(aborted);

    assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
        .isSameAs(aborted);
    verify(f.accounts, times(4)).findByAccountUuidForUpdate(CapacityAdmissionTestFixture.ACCOUNT);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void cancelledCallDoesNotRetryAnAbortedTransaction() throws Exception {
    var f = new Fixture(true);
    f.existing = false;
    var aborted = serializationFailure();
    try (var caller = Context.current().withCancellation()) {
      when(f.accounts.findByAccountUuidForUpdate(CapacityAdmissionTestFixture.ACCOUNT))
          .thenAnswer(
              invocation -> {
                caller.cancel(null);
                throw aborted;
              });

      caller.run(
          () ->
              assertThatThrownBy(
                      () -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
                  .isSameAs(aborted));
    }
    verify(f.accounts).findByAccountUuidForUpdate(CapacityAdmissionTestFixture.ACCOUNT);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  private static org.jooq.exception.DataAccessException serializationFailure() {
    return new org.jooq.exception.DataAccessException(
        "concurrent reservation", new SQLException("serialization failure", "40001"));
  }

  @Test
  void changedAttemptOrPayloadOrFenceConflictsBeforeAnyCurrentSourceOrUsageMutation()
      throws Exception {
    var f = new Fixture(false);
    for (Request changed :
        java.util.List.of(
            CapacityAdmissionTestFixture.playerRequest(false),
            new Request(
                    f.request.identity(),
                    UUID.randomUUID(),
                    f.request.capacityRearmFence(),
                    f.request.targetNamespace(),
                    f.request.context(),
                    f.request.operation(),
                    f.request.priorShape(),
                    f.request.requestedShape(),
                    f.request.evidence(),
                    f.request.capacityDelta(),
                    null)
                .withDigest(),
            new Request(
                    f.request.identity(),
                    f.request.admissionAttemptId(),
                    2,
                    f.request.targetNamespace(),
                    f.request.context(),
                    f.request.operation(),
                    f.request.priorShape(),
                    f.request.requestedShape(),
                    f.request.evidence(),
                    f.request.capacityDelta(),
                    null)
                .withDigest(),
            new Request(
                    f.request.identity(),
                    f.request.admissionAttemptId(),
                    f.request.capacityRearmFence(),
                    f.request.targetNamespace(),
                    f.request.context(),
                    f.request.operation(),
                    f.request.priorShape(),
                    new net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission
                        .Shape(2, 0, 0),
                    f.request.evidence(),
                    f.request.capacityDelta(),
                    null)
                .withDigest())) {
      assertThatThrownBy(() -> f.owner.commit(changed, changed.evidence().callerWorkload()))
          .hasMessage("IDEMPOTENCY_CONFLICT");
    }
    verifyNoInteractions(f.memberships, f.issuances, f.scopes);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void matchingContentWithoutAccountRetainedAuthorityCannotCreateUsage() throws Exception {
    var f = new Fixture(false);
    f.existing = false;
    assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
        .hasMessage("ENTITLEMENT_UNAVAILABLE");
    verifyNoInteractions(f.memberships, f.issuances, f.scopes);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void wrongPeerOrAbsentDeltaNeverStartsAccountWork() throws Exception {
    var f = new Fixture(false);
    assertThatThrownBy(
            () -> f.owner.commit(f.request, "spiffe://firemud/ns/prod/sa/world-management-service"))
        .isInstanceOfSatisfying(
            AccountTenantCapacityRepository.CapacityException.class,
            failure -> {
              assertThat(failure.code()).isEqualTo(Failure.PERMISSION_DENIED);
              assertThat(failure.code().toStatusException().getStatus().getCode())
                  .isEqualTo(io.grpc.Status.Code.PERMISSION_DENIED);
            });
    var absent =
        new Request(
                f.request.identity(),
                f.request.admissionAttemptId(),
                1,
                f.request.targetNamespace(),
                f.request.context(),
                f.request.operation(),
                f.request.priorShape(),
                f.request.requestedShape(),
                f.request.evidence(),
                null,
                null)
            .withDigest();
    assertThatThrownBy(() -> f.owner.commit(absent, absent.evidence().callerWorkload()))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(f.accounts, f.dsl, f.memberships, f.issuances, f.scopes);
  }

  @Test
  void authenticatedGameSessionCannotSelectWorldOwnerBeforeAccountWork() throws Exception {
    var f = new Fixture(false, CapacityAdmissionTestFixture.worldRequest(1L));
    assertThatThrownBy(() -> f.owner.commit(f.request, CapacityAdmissionTestFixture.PEER))
        .isInstanceOfSatisfying(
            AccountTenantCapacityRepository.CapacityException.class,
            failure -> assertThat(failure.code()).isEqualTo(Failure.PERMISSION_DENIED));
    verifyNoInteractions(f.accounts, f.dsl, f.memberships, f.issuances, f.scopes);
  }

  @Test
  void authenticatedWorldRequestCannotReplayWhileWorldCapacityRemainsHeld() throws Exception {
    var f = new Fixture(false, CapacityAdmissionTestFixture.worldRequest(1L));
    assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
        .isInstanceOfSatisfying(
            AccountTenantCapacityRepository.CapacityException.class,
            failure -> assertThat(failure.code()).isEqualTo(Failure.ENTITLEMENT_UNAVAILABLE));
    verifyNoInteractions(f.accounts, f.dsl, f.memberships, f.issuances, f.scopes);
  }

  @Test
  void wrongTargetNamespaceOrMissingRequestIsInvalidBeforeAccountWork() throws Exception {
    var f = new Fixture(false, CapacityAdmissionTestFixture.playerRequest(), "staging");
    for (String peer :
        java.util.List.of(
            CapacityAdmissionTestFixture.PEER,
            "spiffe://firemud/ns/staging/sa/game-session-service")) {
      assertThatThrownBy(() -> f.owner.commit(f.request, peer))
          .isInstanceOfSatisfying(
              AccountTenantCapacityRepository.CapacityException.class,
              failure -> {
                assertThat(failure.code()).isEqualTo(Failure.INVALID_ARGUMENT);
                assertThat(failure.code().toStatusException().getStatus().getCode())
                    .isEqualTo(io.grpc.Status.Code.INVALID_ARGUMENT);
              });
    }
    assertThatThrownBy(() -> f.owner.commit(null, CapacityAdmissionTestFixture.PEER))
        .isInstanceOfSatisfying(
            AccountTenantCapacityRepository.CapacityException.class,
            failure -> assertThat(failure.code()).isEqualTo(Failure.INVALID_ARGUMENT));
    verifyNoInteractions(f.accounts, f.dsl, f.memberships, f.issuances, f.scopes);
  }

  @Test
  void unretainedNonCreatingEvidenceRemainsUnavailableWithoutAnyUsageWrite() throws Exception {
    var f = new Fixture(false, CapacityAdmissionTestFixture.playerRequest(false));
    f.existing = false;
    assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
        .hasMessage("ENTITLEMENT_UNAVAILABLE");
    verifyNoInteractions(f.memberships, f.issuances, f.scopes);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void exactRetainedNonCreatingEvidenceOutsideWindowIsStale() throws Exception {
    var f = new Fixture(true, CapacityAdmissionTestFixture.playerRequest(false));
    f.existing = false;
    f.retained = true;
    assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
        .hasMessage("CAPACITY_ADMISSION_STALE");
    verifyNoInteractions(f.memberships, f.issuances, f.scopes);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void qualifiedNewerBillingCutoffMakesExactRetainedEvaluationStale() throws Exception {
    var f = new Fixture(false);
    f.existing = false;
    f.retained = true;
    var current = mock(Snapshot.class);
    var tuple = mock(AuthorityTuple.class);
    var tenant = f.request.identity().tenantId().toString();
    var sequence =
        Long.parseLong((String) f.request.evidence().fields().get("tenantBillingSequence"));
    when(current.membershipExists()).thenReturn(true);
    when(current.membershipLifecycleState()).thenReturn("ACTIVE");
    when(current.gameplayAdmissionAllowed()).thenReturn(true);
    when(current.tenantId()).thenReturn(tenant);
    when(current.authorityTuple()).thenReturn(tuple);
    when(tuple.tenantBillingCutoff())
        .thenReturn(
            Optional.of(
                Map.of(
                    tenant,
                    new TenantBillingCutoff("1", Long.toString(sequence + 1), "billing", "2"))));
    when(f.memberships.readCurrent(
            CapacityAdmissionTestFixture.ACCOUNT, f.request.identity().tenantId()))
        .thenReturn(current);
    assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
        .hasMessage("CAPACITY_ADMISSION_STALE");
    verifyNoInteractions(f.issuances, f.scopes);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void currentOwnerReadWithoutQualifiedEvidenceIsUnavailable() throws Exception {
    var f = new Fixture(false);
    f.existing = false;
    f.retained = true;
    when(f.memberships.readCurrent(
            CapacityAdmissionTestFixture.ACCOUNT, f.request.identity().tenantId()))
        .thenThrow(new IllegalStateException("Missing committed source"));
    assertThatThrownBy(() -> f.owner.commit(f.request, f.request.evidence().callerWorkload()))
        .hasMessage("ENTITLEMENT_UNAVAILABLE");
    verifyNoInteractions(f.issuances, f.scopes);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void nonCreatingEvidenceCannotStartRecoveryOrRearmWork() throws Exception {
    var f = new Fixture(false);
    var source =
        mock(
            net.firedevops.firemud.accountservice.service.AccountTenantCapacityRecoveryWorker
                .ExecutionReadClient.class);
    var recovery =
        new net.firedevops.firemud.accountservice.service.AccountTenantCapacityRecoveryWorker(
            f.owner, f.dsl, source, Clock.fixed(CapacityAdmissionTestFixture.NOW, ZoneOffset.UTC));
    var denied = CapacityAdmissionTestFixture.playerRequest(false);
    var next =
        new Request(
                denied.identity(),
                UUID.randomUUID(),
                2,
                denied.targetNamespace(),
                denied.context(),
                denied.operation(),
                denied.priorShape(),
                denied.requestedShape(),
                denied.evidence(),
                denied.capacityDelta(),
                null)
            .withDigest();
    assertThatThrownBy(() -> recovery.rearm(f.request, next))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                recovery.reconcile(
                    denied,
                    net.firedevops.firemud.accountservice.service
                        .AccountTenantCapacityRecoveryWorker.NoCommitAction.RETAIN_FOR_REARM))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(f.accounts, f.dsl, f.memberships, f.issuances, f.scopes, source);
  }

  private static final class Fixture {
    private final DSLContext dsl = mock(DSLContext.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AccountCanonicalRuntimeMembershipSnapshotRepository memberships =
        mock(AccountCanonicalRuntimeMembershipSnapshotRepository.class);
    private final AccountGameplayDelegationIssuanceRepository issuances =
        mock(AccountGameplayDelegationIssuanceRepository.class);
    private final AccountConnectScopeRepository scopes = mock(AccountConnectScopeRepository.class);
    private final Request request;
    private final UUID reservation = UUID.randomUUID();
    private final AccountTenantCapacityOwner owner;
    private boolean existing = true;
    private boolean retained;
    private Record originDecision;

    private final ControlledTransactions transactions = new ControlledTransactions();

    private Fixture(boolean expired) throws Exception {
      this(expired, CapacityAdmissionTestFixture.playerRequest());
    }

    private Fixture(boolean expired, Request request) throws Exception {
      this(expired, request, "prod");
    }

    private Fixture(boolean expired, Request request, String namespace) throws Exception {
      this.request = request;
      when(accounts.findByAccountUuidForUpdate(CapacityAdmissionTestFixture.ACCOUNT))
          .thenReturn(Optional.of(new Account()));
      Connection connection = mock(Connection.class);
      when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_SERIALIZABLE);
      doAnswer(
              invocation -> {
                invocation.getArgument(0, ConnectionRunnable.class).run(connection);
                return null;
              })
          .when(dsl)
          .connection(any(ConnectionRunnable.class));
      Record row = mock(Record.class);
      when(row.get("tenant_uuid", UUID.class)).thenReturn(request.identity().tenantId());
      when(row.get("request_id", UUID.class)).thenReturn(request.identity().requestId());
      when(row.get("admission_id", UUID.class)).thenReturn(request.identity().admissionId());
      when(row.get("admission_attempt_id", UUID.class)).thenReturn(request.admissionAttemptId());
      when(row.get("reservation_id", UUID.class)).thenReturn(reservation);
      when(row.get("capacity_rearm_fence", Long.class)).thenReturn(1L);
      when(row.get("reservation_fence", Long.class)).thenReturn(1L);
      when(row.get("mutation_digest", String.class)).thenReturn(request.mutationDigest());
      when(row.get("canonical_payload", byte[].class))
          .thenReturn(CapacityAdmissionDigest.preimage(request));
      when(row.get("caller_workload", String.class))
          .thenReturn(request.evidence().callerWorkload());
      when(row.get("evaluation_identity", String.class))
          .thenReturn(request.evidence().evaluationIdentity());
      when(row.get("expires_at", java.time.OffsetDateTime.class))
          .thenReturn(AccountTenantCapacityRepository.at(request.evidence().expiresAt()));
      when(row.get("state", String.class)).thenReturn("RESERVED");
      Record evaluation = mock(Record.class);
      when(evaluation.get("snapshot_identity", String.class))
          .thenReturn(request.evidence().snapshotIdentity());
      var evidence = request.evidence();
      when(evaluation.get("tenant_uuid", UUID.class)).thenReturn(evidence.tenantId());
      when(evaluation.get("caller_workload", String.class)).thenReturn(evidence.callerWorkload());
      when(evaluation.get("owner_service", String.class)).thenReturn(evidence.owner().service());
      when(evaluation.get("canonical_evidence", byte[].class))
          .thenReturn(evidence.canonicalBytes());
      when(evaluation.get("evaluated_at", java.time.OffsetDateTime.class))
          .thenReturn(AccountTenantCapacityRepository.at(evidence.evaluatedAt()));
      when(evaluation.get("expires_at", java.time.OffsetDateTime.class))
          .thenReturn(AccountTenantCapacityRepository.at(evidence.expiresAt()));
      when(dsl.fetchOne(anyString(), any(Object[].class)))
          .thenAnswer(
              invocation -> {
                String sql = invocation.getArgument(0, String.class);
                if (sql.startsWith("SELECT * FROM account_tenant_capacity_reservations "))
                  return existing ? row : null;
                if (sql.startsWith("SELECT * FROM account_capacity_origin_decisions "))
                  return originDecision;
                if (sql.startsWith(
                    "SELECT snapshot_identity FROM account_tenant_capacity_evaluations "))
                  return evaluation;
                if (sql.startsWith("SELECT * FROM account_tenant_capacity_evaluations "))
                  return retained ? evaluation : null;
                if (sql.startsWith("SELECT * FROM account_tenant_capacity_usage "))
                  return mock(Record.class);
                throw new AssertionError(sql);
              });
      Record databaseTime = mock(Record.class);
      var databaseNow =
          expired
              ? request.evidence().expiresAt()
              : request.evidence().evaluatedAt().plusSeconds(1);
      when(databaseTime.get("observed_at", java.time.OffsetDateTime.class))
          .thenReturn(AccountTenantCapacityRepository.at(databaseNow));
      when(dsl.fetchSingle("SELECT clock_timestamp() AS observed_at")).thenReturn(databaseTime);
      owner =
          new AccountTenantCapacityOwner(
              dsl,
              accounts,
              memberships,
              issuances,
              scopes,
              transactions,
              Clock.fixed(
                  CapacityAdmissionTestFixture.NOW.plusSeconds(expired ? 60 : 1), ZoneOffset.UTC),
              namespace);
    }
  }

  private static final class ControlledTransactions implements PlatformTransactionManager {
    private RuntimeException commitFailure;

    @Override
    public TransactionStatus getTransaction(
        @org.jspecify.annotations.Nullable TransactionDefinition definition) {
      assertThat(definition).isNotNull();
      assertThat(definition.getIsolationLevel())
          .isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
      assertThat(definition.getPropagationBehavior())
          .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      assertThat(definition.getTimeout()).isEqualTo(5);
      assertThat(definition.isReadOnly()).isFalse();
      TransactionSynchronizationManager.setActualTransactionActive(true);
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      TransactionSynchronizationManager.setActualTransactionActive(false);
      if (commitFailure != null) throw commitFailure;
    }

    @Override
    public void rollback(TransactionStatus status) {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }
}
