package net.firedevops.firemud.accountservice.repository;

import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Reads the canonical Account lifecycle operation table as a fail-closed admission denial
 * predicate. It proves no producer identity, World result, source finalization, current serving
 * state, or admission permission.
 */
public final class AccountLifecyclePendingDenialReader {
  private static final String ACCOUNT_LOCK_SQL =
      "SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE";
  private static final String PENDING_SQL =
      "SELECT EXISTS (SELECT 1 FROM account_lifecycle_serving_operations "
          + "WHERE account_uuid = ? AND tenant_uuid = ? "
          + "AND status IN ('PENDING', 'WORLD_TERMINAL'))";
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final DSLContext dsl;

  public AccountLifecyclePendingDenialReader(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
  }

  /**
   * Requires no unresolved lifecycle operation for the exact Account/tenant pair.
   *
   * <p>The existing writable owner transaction must be REPEATABLE_READ or SERIALIZABLE. The
   * canonical journal's BEFORE INSERT row-version fence forces an older snapshot waiting on the
   * persisted Account lock to fail with a serialization error instead of reading a stale clear
   * predicate. Both a pending intent and a correlated World terminal receipt remain denial
   * evidence.
   */
  public void requireNoPending(UUID accountUuid, UUID tenantUuid) {
    requireWritableOwnerTransaction();
    requireUuid(accountUuid, "Account UUID");
    requireUuid(tenantUuid, "tenant UUID");

    try {
      Record account = dsl.fetchOne(ACCOUNT_LOCK_SQL, accountUuid);
      if (account == null || !accountUuid.equals(account.get(0, UUID.class))) {
        throw new PendingStateUnavailableException(
            "Account lifecycle pending state could not be read");
      }
      Record pendingRecord = dsl.fetchOne(PENDING_SQL, accountUuid, tenantUuid);
      Boolean pending = pendingRecord == null ? null : pendingRecord.get(0, Boolean.class);
      if (pending == null) {
        throw new PendingStateUnavailableException(
            "Account lifecycle pending state could not be read");
      }
      if (pending) {
        throw new PendingOperationException(
            "Account lifecycle invalidation is unresolved for this Account and tenant");
      }
    } catch (PendingOperationException | PendingStateUnavailableException denied) {
      throw denied;
    } catch (DataAccessException unavailable) {
      throw new PendingStateUnavailableException(
          "Account lifecycle pending state could not be read", unavailable);
    }
  }

  private static void requireWritableOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Account lifecycle pending read requires an owner transaction");
    }
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Account lifecycle pending read requires a writable transaction");
    }
    Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    if (isolation == null
        || (isolation != TransactionDefinition.ISOLATION_REPEATABLE_READ
            && isolation != TransactionDefinition.ISOLATION_SERIALIZABLE)) {
      throw new IllegalStateException(
          "Account lifecycle pending read requires REPEATABLE_READ or SERIALIZABLE isolation");
    }
  }

  private static void requireUuid(UUID value, String name) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
    }
  }

  public static final class PendingOperationException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public PendingOperationException(String message) {
      super(message);
    }
  }

  public static final class PendingStateUnavailableException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public PendingStateUnavailableException(String message) {
      super(message);
    }

    public PendingStateUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
