package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountLifecyclePendingDenialReaderTest {
  private static final UUID ACCOUNT = UUID.randomUUID();
  private static final UUID TENANT = UUID.randomUUID();

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void pendingOperationDeniesAfterLockingExactAccount() {
    DSLContext dsl = mock(DSLContext.class);
    Record account = accountRow();
    Record pending = mock(Record.class);
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(account, pending);
    when(pending.get(0, Boolean.class)).thenReturn(true);
    transaction(TransactionDefinition.ISOLATION_REPEATABLE_READ);

    assertThatThrownBy(
            () -> new AccountLifecyclePendingDenialReader(dsl).requireNoPending(ACCOUNT, TENANT))
        .isInstanceOf(PendingOperationException.class)
        .hasMessage("Account lifecycle invalidation is unresolved for this Account and tenant");

    var ordered = inOrder(dsl);
    ordered
        .verify(dsl)
        .fetchOne("SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE", ACCOUNT);
    ordered
        .verify(dsl)
        .fetchOne(
            "SELECT EXISTS (SELECT 1 FROM account_lifecycle_serving_operations "
                + "WHERE account_uuid = ? AND tenant_uuid = ? "
                + "AND status IN ('PENDING', 'WORLD_TERMINAL'))",
            ACCOUNT,
            TENANT);
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

    verify(dsl)
        .fetchOne(
            "SELECT EXISTS (SELECT 1 FROM account_lifecycle_serving_operations "
                + "WHERE account_uuid = ? AND tenant_uuid = ? "
                + "AND status IN ('PENDING', 'WORLD_TERMINAL'))",
            ACCOUNT,
            TENANT);
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
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThatThrownBy(() -> reader.requireNoPending(ACCOUNT, TENANT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("REPEATABLE_READ or SERIALIZABLE");
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        TransactionDefinition.ISOLATION_REPEATABLE_READ);
    assertThatThrownBy(() -> reader.requireNoPending(new UUID(0L, 0L), TENANT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-nil UUID");
    verifyNoInteractions(dsl);
  }

  private static Record accountRow() {
    Record account = mock(Record.class);
    when(account.get(0, UUID.class)).thenReturn(ACCOUNT);
    return account;
  }

  private static void transaction(int isolation) {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(isolation);
  }
}
