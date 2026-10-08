package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountLifecyclePendingDenialReader;
import net.firedevops.firemud.accountservice.repository.AccountLifecyclePendingDenialReader.PendingOperationException;
import net.firedevops.firemud.accountservice.repository.AccountLifecyclePendingDenialReader.PendingStateUnavailableException;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountLifecyclePendingDenialReaderTest {
  private static final UUID ACCOUNT = UUID.randomUUID();
  private static final UUID TENANT = UUID.randomUUID();
  private static final String DENIAL_SQL =
      "SELECT EXISTS (SELECT 1 FROM account_lifecycle_serving_operations "
          + "WHERE account_uuid = ? AND tenant_uuid = ? "
          + "AND status IN ('PENDING', 'WORLD_TERMINAL')) "
          + "OR EXISTS (SELECT 1 FROM account_security_state_operations "
          + "WHERE account_uuid = ? AND status = 'WAITING') "
          + "OR EXISTS (SELECT 1 FROM account_draft_authorization_source_changes c "
          + "JOIN account_draft_authorization_changed_scopes s ON s.change_id = c.change_id "
          + "WHERE c.status = 'WAITING' AND s.source_key IN (?, ?))";

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void pendingAccountOrTargetTenantOperationDeniesAfterLockingExactAccount() {
    DSLContext dsl = mock(DSLContext.class);
    Record account = accountRow();
    Record pending = mock(Record.class);
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(account, pending);
    when(pending.get(0, Boolean.class)).thenReturn(true);
    transaction(TransactionDefinition.ISOLATION_REPEATABLE_READ);

    assertThatThrownBy(
            () -> new AccountLifecyclePendingDenialReader(dsl).requireNoPending(ACCOUNT, TENANT))
        .isInstanceOf(PendingOperationException.class)
        .hasMessage("Account-wide or target-tenant invalidation is unresolved");

    var ordered = inOrder(dsl);
    ordered
        .verify(dsl)
        .fetchOne("SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE", ACCOUNT);
    verifyDenialQuery(ordered, dsl);
  }

  @Test
  void noPendingOperationDoesNotTripTheDenialPredicate() {
    DSLContext dsl = mock(DSLContext.class);
    Record account = accountRow();
    Record pending = mock(Record.class);
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(account, pending);
    when(pending.get(0, Boolean.class)).thenReturn(false);
    transaction(TransactionDefinition.ISOLATION_SERIALIZABLE);

    new AccountLifecyclePendingDenialReader(dsl).requireNoPending(ACCOUNT, TENANT);

    verifyDenialQuery(dsl);
  }

  @Test
  void readCommittedWritableOwnerTransactionIsAllowedForNegativePredicate() {
    DSLContext dsl = mock(DSLContext.class);
    Record account = accountRow();
    Record pending = mock(Record.class);
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(account, pending);
    when(pending.get(0, Boolean.class)).thenReturn(false);
    transaction(TransactionDefinition.ISOLATION_READ_COMMITTED);

    new AccountLifecyclePendingDenialReader(dsl).requireNoPending(ACCOUNT, TENANT);

    verifyDenialQuery(dsl);
  }

  @Test
  void datasourceDefaultReadCommittedIsolationIsAccepted() {
    DSLContext dsl = mock(DSLContext.class);
    Record account = accountRow();
    Record pending = mock(Record.class);
    Record isolation = mock(Record.class);
    when(dsl.fetchOne("SHOW transaction_isolation")).thenReturn(isolation);
    when(isolation.get(0, String.class)).thenReturn("read committed");
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(account, pending);
    when(pending.get(0, Boolean.class)).thenReturn(false);
    defaultIsolationTransaction();

    new AccountLifecyclePendingDenialReader(dsl).requireNoPending(ACCOUNT, TENANT);

    var ordered = inOrder(dsl);
    ordered.verify(dsl).fetchOne("SHOW transaction_isolation");
    ordered
        .verify(dsl)
        .fetchOne("SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE", ACCOUNT);
    verifyDenialQuery(ordered, dsl);
  }

  @Test
  void unknownDatasourceDefaultIsolationDeniesBeforeOwnerReads() {
    DSLContext dsl = mock(DSLContext.class);
    defaultIsolationTransaction();

    assertThatThrownBy(
            () -> new AccountLifecyclePendingDenialReader(dsl).requireNoPending(ACCOUNT, TENANT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("requires READ_COMMITTED");

    verify(dsl).fetchOne("SHOW transaction_isolation");
    verify(dsl, never()).fetchOne(anyString(), any(Object[].class));
  }

  @Test
  void nullDatasourceDefaultIsolationValueDeniesBeforeOwnerReads() {
    DSLContext dsl = mock(DSLContext.class);
    Record isolation = mock(Record.class);
    when(dsl.fetchOne("SHOW transaction_isolation")).thenReturn(isolation);
    when(isolation.get(0, String.class)).thenReturn(null);
    defaultIsolationTransaction();

    assertThatThrownBy(
            () -> new AccountLifecyclePendingDenialReader(dsl).requireNoPending(ACCOUNT, TENANT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("requires READ_COMMITTED");

    verify(dsl).fetchOne("SHOW transaction_isolation");
    verify(dsl, never()).fetchOne(anyString(), any(Object[].class));
  }

  @Test
  void datasourceDefaultIsolationQueryFailureDeniesBeforeOwnerReads() {
    DSLContext dsl = mock(DSLContext.class);
    DataAccessException unavailable = new DataAccessException("isolation unavailable");
    when(dsl.fetchOne("SHOW transaction_isolation")).thenThrow(unavailable);
    defaultIsolationTransaction();

    assertThatThrownBy(
            () -> new AccountLifecyclePendingDenialReader(dsl).requireNoPending(ACCOUNT, TENANT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not determine transaction isolation")
        .hasCause(unavailable);

    verify(dsl).fetchOne("SHOW transaction_isolation");
    verify(dsl, never()).fetchOne(anyString(), any(Object[].class));
  }

  @Test
  void missingAccountAndUnreadablePredicateFailClosed() {
    DSLContext missingAccountDsl = mock(DSLContext.class);
    when(missingAccountDsl.fetchOne(anyString(), any(Object[].class))).thenReturn(null);
    transaction(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    assertThatThrownBy(
            () ->
                new AccountLifecyclePendingDenialReader(missingAccountDsl)
                    .requireNoPending(ACCOUNT, TENANT))
        .isInstanceOf(PendingStateUnavailableException.class);
    verify(missingAccountDsl)
        .fetchOne("SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE", ACCOUNT);

    DSLContext unreadableDsl = mock(DSLContext.class);
    Record account = accountRow();
    when(unreadableDsl.fetchOne(anyString(), any(Object[].class))).thenReturn(account, null);
    assertThatThrownBy(
            () ->
                new AccountLifecyclePendingDenialReader(unreadableDsl)
                    .requireNoPending(ACCOUNT, TENANT))
        .isInstanceOf(PendingStateUnavailableException.class);
  }

  @Test
  void ownerQueryFailureFailsClosed() {
    DSLContext dsl = mock(DSLContext.class);
    when(dsl.fetchOne(anyString(), any(Object[].class)))
        .thenThrow(new DataAccessException("unavailable"));
    transaction(TransactionDefinition.ISOLATION_REPEATABLE_READ);

    assertThatThrownBy(
            () -> new AccountLifecyclePendingDenialReader(dsl).requireNoPending(ACCOUNT, TENANT))
        .isInstanceOf(PendingStateUnavailableException.class)
        .hasCauseInstanceOf(DataAccessException.class);
  }

  @Test
  void ownerTransactionAndExactUuidInputsAreRequiredBeforeReads() {
    DSLContext dsl = mock(DSLContext.class);
    var reader = new AccountLifecyclePendingDenialReader(dsl);

    assertThatThrownBy(() -> reader.requireNoPending(ACCOUNT, TENANT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("owner transaction");
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    assertThatThrownBy(() -> reader.requireNoPending(ACCOUNT, TENANT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("isolation");
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        TransactionDefinition.ISOLATION_READ_UNCOMMITTED);
    assertThatThrownBy(() -> reader.requireNoPending(ACCOUNT, TENANT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("isolation");
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThatThrownBy(() -> reader.requireNoPending(ACCOUNT, TENANT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable transaction");
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        TransactionDefinition.ISOLATION_REPEATABLE_READ);
    assertThatThrownBy(() -> reader.requireNoPending(new UUID(0L, 0L), TENANT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-nil UUID");
    verify(dsl).fetchOne("SHOW transaction_isolation");
    verify(dsl, never()).fetchOne(anyString(), any(Object[].class));
  }

  private static Record accountRow() {
    Record account = mock(Record.class);
    when(account.get(0, UUID.class)).thenReturn(ACCOUNT);
    return account;
  }

  private static void verifyDenialQuery(DSLContext dsl) {
    verify(dsl)
        .fetchOne(
            DENIAL_SQL, ACCOUNT, TENANT, ACCOUNT, "ACCOUNT:" + ACCOUNT, "GLOBAL_ROLES:" + ACCOUNT);
  }

  private static void verifyDenialQuery(InOrder ordered, DSLContext dsl) {
    ordered
        .verify(dsl)
        .fetchOne(
            DENIAL_SQL, ACCOUNT, TENANT, ACCOUNT, "ACCOUNT:" + ACCOUNT, "GLOBAL_ROLES:" + ACCOUNT);
  }

  private static void transaction(int isolation) {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(isolation);
  }

  private static void defaultIsolationTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
  }
}
