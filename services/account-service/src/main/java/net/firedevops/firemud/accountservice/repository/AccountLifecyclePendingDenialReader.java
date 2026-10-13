package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Reads exact target-tenant lifecycle operations and Account-wide pending source operations as a
 * fail-closed admission denial predicate. It proves no producer identity, World result, source
 * finalization, current serving state, or admission permission.
 */
@Repository
public class AccountLifecyclePendingDenialReader {
  private static final String ACCOUNT_LOCK_SQL =
      "SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE";
  private static final String PENDING_SQL =
      "SELECT EXISTS (SELECT 1 FROM account_lifecycle_serving_operations "
          + "WHERE account_uuid = ? AND tenant_uuid = ? "
          + "AND status IN ('PENDING', 'WORLD_TERMINAL')) "
          + "OR EXISTS (SELECT 1 FROM account_security_state_operations "
          + "WHERE account_uuid = ? AND status = 'WAITING') "
          + "OR EXISTS (SELECT 1 FROM account_draft_authorization_source_changes c "
          + "JOIN account_draft_authorization_changed_scopes s ON s.change_id = c.change_id "
          + "WHERE c.status = 'WAITING' AND s.source_key IN (?, ?))";
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Keep the required DSL precondition fail-fast; this non-final Spring repository is proxied and no partially initialized instance escapes.")
  public AccountLifecyclePendingDenialReader(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
  }

  /**
   * Requires no unresolved exact target-tenant lifecycle operation or Account-wide pending source
   * operation.
   *
   * <p>The existing writable owner transaction must be READ_COMMITTED, REPEATABLE_READ, or
   * SERIALIZABLE. At READ_COMMITTED, the exact Account row lock makes this query observe a
   * lifecycle insert committed before the lock is acquired; the lifecycle journal's and
   * Account-wide pending-intent tables' row-version fences prevent an insert from crossing a later
   * lock holder. When Spring uses the datasource's default isolation, the actual PostgreSQL
   * transaction isolation is checked. A pending lifecycle intent, a correlated World terminal
   * receipt, an Account security-state operation in WAITING, or an Account/GLOBAL_ROLES source
   * change in WAITING remains denial evidence. Tenant membership and tenant source scopes are not
   * promoted to Account-wide scope.
   */
  public void requireNoPending(UUID accountUuid, UUID tenantUuid) {
    requireWritableOwnerTransaction(dsl);
    requireUuid(accountUuid, "Account UUID");
    requireUuid(tenantUuid, "tenant UUID");

    try {
      Record account = dsl.fetchOne(ACCOUNT_LOCK_SQL, accountUuid);
      if (account == null || !accountUuid.equals(account.get(0, UUID.class))) {
        throw new PendingStateUnavailableException(
            "Account lifecycle pending state could not be read");
      }
      Record pendingRecord =
          dsl.fetchOne(
              PENDING_SQL,
              accountUuid,
              tenantUuid,
              accountUuid,
              "ACCOUNT:" + accountUuid,
              "GLOBAL_ROLES:" + accountUuid);
      Boolean pending = pendingRecord == null ? null : pendingRecord.get(0, Boolean.class);
      if (pending == null) {
        throw new PendingStateUnavailableException(
            "Account lifecycle pending state could not be read");
      }
      if (pending) {
        throw new PendingOperationException(
            "Account-wide or target-tenant invalidation is unresolved");
      }
    } catch (PendingOperationException | PendingStateUnavailableException denied) {
      throw denied;
    } catch (DataAccessException unavailable) {
      throw new PendingStateUnavailableException(
          "Account lifecycle pending state could not be read", unavailable);
    }
  }

  private static void requireWritableOwnerTransaction(DSLContext dsl) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Account lifecycle pending read requires an owner transaction");
    }
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Account lifecycle pending read requires a writable transaction");
    }
    Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    if (isolation == null) {
      final String databaseIsolation;
      try {
        Record isolationRecord = dsl.fetchOne("SHOW transaction_isolation");
        databaseIsolation = isolationRecord == null ? null : isolationRecord.get(0, String.class);
      } catch (DataAccessException unavailable) {
        throw new IllegalStateException(
            "Account lifecycle pending read could not determine transaction isolation",
            unavailable);
      }
      if ("read committed".equals(databaseIsolation)) {
        isolation = TransactionDefinition.ISOLATION_READ_COMMITTED;
      } else if ("repeatable read".equals(databaseIsolation)) {
        isolation = TransactionDefinition.ISOLATION_REPEATABLE_READ;
      } else if ("serializable".equals(databaseIsolation)) {
        isolation = TransactionDefinition.ISOLATION_SERIALIZABLE;
      }
    }
    if (isolation == null
        || (isolation != TransactionDefinition.ISOLATION_READ_COMMITTED
            && isolation != TransactionDefinition.ISOLATION_REPEATABLE_READ
            && isolation != TransactionDefinition.ISOLATION_SERIALIZABLE)) {
      throw new IllegalStateException(
          "Account lifecycle pending read requires READ_COMMITTED, REPEATABLE_READ, or SERIALIZABLE isolation");
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
