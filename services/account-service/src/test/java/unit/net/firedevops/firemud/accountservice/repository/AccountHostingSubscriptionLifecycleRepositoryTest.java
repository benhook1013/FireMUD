package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ReconciliationLimits;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
