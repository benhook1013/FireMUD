package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.Disposition;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ProvisioningClaim;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ReconciliationLimits;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.ResultQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountHostingSubscriptionLifecycleRepositoryTest {
  private final DSLContext dsl = mock(DSLContext.class);
  private final AccountSubscriptionIntentRepository intents =
      new AccountSubscriptionIntentRepository(dsl, new AccountHostingPlanCatalog(List.of()));
  private final AccountHostingSubscriptionLifecycleRepository repository =
      new AccountHostingSubscriptionLifecycleRepository(
          dsl,
          intents,
          Clock.systemUTC(),
          new ReconciliationLimits(
              3, 2, Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofHours(1)),
          new AccountHostingSubscriptionLifecyclePolicy(
              Duration.ofSeconds(15),
              new AccountHostingSubscriptionLifecyclePolicy.RecoveryTiming(
                  Duration.ofSeconds(60), Duration.ofSeconds(120))),
          "prod",
          false);

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @Test
  void retainedPositiveInspectionRequiresBoundedReadOnlyTransactionBeforeSql() {
    assertThatThrownBy(() -> repository.requireNoRetainedPositiveCompletionGaps(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.requireNoRetainedPositiveCompletionGaps(6))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.requireNoRetainedPositiveCompletionGaps(1))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> repository.requireNoRetainedPositiveCompletionGaps(1))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(dsl);
  }

  @Test
  @SuppressWarnings("unchecked")
  void retainedPositiveInspectionOnlyAcceptsExplicitClearSnapshot() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    ResultQuery<Record> query = mock(ResultQuery.class);
    Record result = mock(Record.class);
    when(dsl.resultQuery(anyString())).thenReturn(query);
    when(query.fetchOne()).thenReturn(result);
    when(result.get("unresolved", Boolean.class)).thenReturn(true, null, false);
    assertThatThrownBy(() -> repository.requireNoRetainedPositiveCompletionGaps(2))
        .hasMessageContaining("HOSTING_ACTIVATION_UNAVAILABLE");
    assertThatThrownBy(() -> repository.requireNoRetainedPositiveCompletionGaps(2))
        .hasMessageContaining("HOSTING_ACTIVATION_UNAVAILABLE");
    repository.requireNoRetainedPositiveCompletionGaps(2);
    verify(query, org.mockito.Mockito.times(3)).queryTimeout(2);
    when(query.fetchOne()).thenReturn(null);
    assertThatThrownBy(() -> repository.requireNoRetainedPositiveCompletionGaps(2))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void retainedPositiveInspectionPropagatesDatabaseFailure() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    ResultQuery<Record> query = mock(ResultQuery.class);
    when(dsl.resultQuery(anyString())).thenReturn(query);
    when(query.fetchOne()).thenThrow(new org.jooq.exception.DataAccessException("timeout"));
    assertThatThrownBy(() -> repository.requireNoRetainedPositiveCompletionGaps(1))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    verify(query).queryTimeout(1);
  }

  @Test
  void recoveryOriginIsOnlyRetainedEpisodeHistoryAndMissingHistoryStaysUnresolved() {
    assertThat(AccountHostingSubscriptionLifecycleRepository.retainedPastDueOrigin(null)).isNull();
    var receipt = mock(org.jooq.Record.class);
    assertThat(AccountHostingSubscriptionLifecycleRepository.retainedPastDueOrigin(receipt))
        .isNull();
    Instant original = Instant.parse("2026-05-05T00:00:00Z");
    when(receipt.get("past_due_since", OffsetDateTime.class))
        .thenReturn(original.atOffset(ZoneOffset.UTC));
    assertThat(AccountHostingSubscriptionLifecycleRepository.retainedPastDueOrigin(receipt))
        .isEqualTo(original);
    assertThat(AccountHostingSubscriptionLifecycleRepository.retainedPastDueOrigin(receipt))
        .isEqualTo(original);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 1})
  void expiredExactAttemptBeforeAbsoluteDeadlineLeavesWorkAndReservationUntouched(
      long secondsAfterExpiry) {
    Instant expiresAt = Instant.parse("2026-11-07T00:00:10Z");
    UUID requestId = UUID.randomUUID();
    UUID subscriptionId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    var controlledIntents = mock(AccountSubscriptionIntentRepository.class);
    var controlledRepository =
        new AccountHostingSubscriptionLifecycleRepository(
            dsl,
            controlledIntents,
            Clock.fixed(expiresAt.minusSeconds(5), ZoneOffset.UTC),
            limits(3, 2, Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofHours(1)),
            new AccountHostingSubscriptionLifecyclePolicy(
                Duration.ofSeconds(15),
                new AccountHostingSubscriptionLifecyclePolicy.RecoveryTiming(
                    Duration.ofSeconds(60), Duration.ofSeconds(120))),
            "prod",
            false);
    var creation = mock(AccountSubscriptionCreationRecord.class);
    when(creation.requestId()).thenReturn(requestId);
    when(creation.subscriptionId()).thenReturn(subscriptionId);
    when(controlledIntents.findCreation(requestId)).thenReturn(Optional.of(creation));
    var current = mock(AccountSubscriptionCurrentRecord.class);
    when(current.subscriptionId()).thenReturn(subscriptionId);
    when(current.rowVersion()).thenReturn(7L);
    when(controlledIntents.lockForProvisioningSettlement(requestId, 7L)).thenReturn(current);
    var claim = mock(ProvisioningClaim.class);
    when(claim.creation()).thenReturn(creation);
    when(claim.attemptId()).thenReturn(attemptId);
    when(claim.attemptNumber()).thenReturn(2);
    when(claim.subscriptionRowVersion()).thenReturn(7L);
    when(claim.claimedAt()).thenReturn(expiresAt.minusSeconds(10));
    when(claim.expiresAt()).thenReturn(expiresAt);
    var retainedWork = mock(Record.class);
    when(retainedWork.get("last_attempt_id", UUID.class)).thenReturn(attemptId);
    when(retainedWork.get("last_attempt_id")).thenReturn(attemptId);
    when(retainedWork.get("subscription_id", UUID.class)).thenReturn(subscriptionId);
    when(retainedWork.get("subscription_row_version", Long.class)).thenReturn(7L);
    when(retainedWork.get("phase", String.class)).thenReturn("CLAIMED");
    when(retainedWork.get("deadline_at", OffsetDateTime.class))
        .thenReturn(expiresAt.plusSeconds(3590).atOffset(ZoneOffset.UTC));
    String lockedWorkSql =
        "SELECT * FROM subscription_provisioning_work WHERE request_id = ? FOR UPDATE";
    when(dsl.fetchOne(lockedWorkSql, requestId)).thenReturn(retainedWork);
    String linksSql =
        "SELECT lifecycle_inflight_request_id, current_receipt_request_id "
            + "FROM subscription WHERE subscription_id = ?";
    var links = mock(Record.class);
    when(dsl.fetchOne(linksSql, subscriptionId)).thenReturn(links);
    String retainedAttemptSql =
        "SELECT * FROM subscription_provisioning_attempts WHERE attempt_id = ?";
    var retainedAttempt = mock(Record.class);
    when(retainedAttempt.get("request_id", UUID.class)).thenReturn(requestId);
    when(retainedAttempt.get("subscription_id", UUID.class)).thenReturn(subscriptionId);
    when(retainedAttempt.get("attempt_number", Integer.class)).thenReturn(2);
    when(retainedAttempt.get("claimed_row_version", Long.class)).thenReturn(7L);
    when(retainedAttempt.get("claimed_at", OffsetDateTime.class))
        .thenReturn(expiresAt.minusSeconds(10).atOffset(ZoneOffset.UTC));
    when(retainedAttempt.get("expires_at", OffsetDateTime.class))
        .thenReturn(expiresAt.atOffset(ZoneOffset.UTC));
    when(dsl.fetchOne(retainedAttemptSql, attemptId)).thenReturn(retainedAttempt);
    var databaseClock = mock(Record.class);
    when(databaseClock.get("now", OffsetDateTime.class))
        .thenReturn(expiresAt.plusSeconds(secondsAfterExpiry).atOffset(ZoneOffset.UTC));
    when(dsl.fetchOne("SELECT clock_timestamp() AS now")).thenReturn(databaseClock);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThat(controlledRepository.deferUnresolved(claim)).isEqualTo(Disposition.ATTEMPT_EXPIRED);

    // Only the exact local owner/work locks, retained attempt, lifecycle ownership and DB clock
    // are read: no SQL writes, reservation release, or replacement attempt occur on this return.
    verify(controlledIntents).lockForProvisioningSettlement(requestId, 7L);
    verify(controlledIntents).findCreation(requestId);
    verify(dsl).fetchOne(lockedWorkSql, requestId);
    verify(dsl).fetchOne(linksSql, subscriptionId);
    verify(dsl).fetchOne(retainedAttemptSql, attemptId);
    verify(dsl).fetchOne("SELECT clock_timestamp() AS now");
    verifyNoMoreInteractions(controlledIntents, dsl);
  }

  @Test
  void claimAndSettlementRequireWritableOwnerTransactionBeforeSql() {
    assertTransactionRefusal();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertTransactionRefusal();
    verifyNoInteractions(dsl);
  }

  @Test
  void terminalResolutionCaptureAndApplyRequireWritableOwnerTransactionBeforeSql() {
    assertThatThrownBy(() -> repository.prepareTerminalResolution(null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> repository.applyTerminalResolution(null, null, null))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertThatThrownBy(() -> repository.prepareTerminalResolution(null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> repository.applyTerminalResolution(null, null, null))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(dsl);
  }

  @ParameterizedTest
  @ValueSource(strings = {"tenant", "subscription", "actor"})
  void terminalResolutionRejectsWrongCallerOrTargetBeforeAnyJournalSqlOrLocks(String changed) {
    var request = terminalRequest("Same-owner cancellation resolution");
    var original = mock(AccountSubscriptionCreationRecord.class);
    when(original.tenantId())
        .thenReturn("tenant".equals(changed) ? UUID.randomUUID() : request.tenantId());
    when(original.subscriptionId())
        .thenReturn("subscription".equals(changed) ? UUID.randomUUID() : request.subscriptionId());
    when(original.owner())
        .thenReturn(
            new AccountSubscriptionCreateRequest.OwnerFence(
                "actor".equals(changed) ? UUID.randomUUID() : request.actorAccountId(),
                1,
                UUID.randomUUID()));
    var controlledIntents = mock(AccountSubscriptionIntentRepository.class);
    when(controlledIntents.findCreation(request.creationRequestId()))
        .thenReturn(Optional.of(original));
    var controlledRepository =
        new AccountHostingSubscriptionLifecycleRepository(
            dsl,
            controlledIntents,
            Clock.systemUTC(),
            limits(3, 2, Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofHours(1)),
            new AccountHostingSubscriptionLifecyclePolicy(
                Duration.ofSeconds(15),
                new AccountHostingSubscriptionLifecyclePolicy.RecoveryTiming(
                    Duration.ofSeconds(60), Duration.ofSeconds(120))),
            "prod",
            false);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> controlledRepository.prepareTerminalResolution(request))
        .isExactlyInstanceOf(AccountSubscriptionIntentRepository.FenceRejectedException.class)
        .hasMessage("Exact same-owner terminal lifecycle target rejected");

    verify(controlledIntents).findCreation(request.creationRequestId());
    verifyNoMoreInteractions(controlledIntents);
    verifyNoInteractions(dsl);
  }

  @Test
  void terminalResolutionReasonIsCanonicalBoundedScalarComparisonData() {
    var first = terminalRequest("e\u0301 reason");
    assertThat(first.auditReason()).isEqualTo("é reason");
    assertThat(first.payload()).isNotEmpty();
    for (String invalid :
        List.of("", "\u0085\u00a0", "nul\u0000reason", "\ud800", "é".repeat(501))) {
      assertThatThrownBy(() -> terminalRequest(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(terminalRequest("é".repeat(500)).auditReason()).hasSize(500);
  }

  private static AccountHostingSubscriptionTerminalResolution.Request terminalRequest(
      String reason) {
    return new AccountHostingSubscriptionTerminalResolution.Request(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        4,
        3,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "a".repeat(64),
        UUID.randomUUID(),
        reason);
  }

  private void assertTransactionRefusal() {
    assertThatThrownBy(() -> repository.claim(UUID.randomUUID(), 1, UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable Account transaction");
    assertThatThrownBy(() -> repository.deferUnresolved(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable Account transaction");
  }

  @Test
  void malformedClaimInputsCannotBecomeSqlLookups() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> repository.claim(null, 1, UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.claim(UUID.randomUUID(), 1, new UUID(0, 0)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.claim(UUID.randomUUID(), 0, UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.claim(UUID.randomUUID(), Long.MAX_VALUE, UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(dsl);
  }

  @Test
  void reconciliationRequiresExplicitFiniteNonzeroBudgetsAndRepresentableDurations() {
    assertThatThrownBy(
            () -> limits(0, 1, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofHours(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> limits(1, 0, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofHours(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> limits(1, 1, Duration.ZERO, Duration.ofSeconds(1), Duration.ofHours(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> limits(1, 1, Duration.ofNanos(1), Duration.ofSeconds(1), Duration.ofHours(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> limits(1, 1, Duration.ofSeconds(1), null, Duration.ofHours(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> limits(1, 1, Duration.ofHours(2), Duration.ofSeconds(1), Duration.ofHours(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                limits(
                    1,
                    1,
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(Long.MAX_VALUE)))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(dsl);
  }

  private static ReconciliationLimits limits(
      int attempts, int stalled, Duration lease, Duration backoff, Duration deadline) {
    return new ReconciliationLimits(attempts, stalled, lease, backoff, deadline);
  }
}
