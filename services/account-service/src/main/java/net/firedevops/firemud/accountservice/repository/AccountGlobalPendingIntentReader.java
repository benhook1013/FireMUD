package net.firedevops.firemud.accountservice.repository;

import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;

/** Exact Account-wide negative evidence only; absence does not establish admission authority. */
final class AccountGlobalPendingIntentReader {
  private AccountGlobalPendingIntentReader() {}

  /**
   * Reads beneath the caller's already-held Account row lock in its writable owner transaction.
   * V143's Account row-version fences serialize new intent against that lock, including stale
   * repeatable-read/serializable snapshots. Do not lock the intent rows in the reverse order.
   * Tenant-only scopes are deliberately not Account-wide denial evidence.
   */
  static boolean hasPending(DSLContext dsl, UUID accountId) {
    Objects.requireNonNull(accountId, "Exact Account UUID is required");
    Boolean pending =
        dsl.fetchSingle(
                "SELECT EXISTS (SELECT 1 FROM account_security_state_operations "
                    + "WHERE account_uuid = ? AND status = 'WAITING') "
                    + "OR EXISTS (SELECT 1 FROM account_draft_authorization_source_changes c "
                    + "JOIN account_draft_authorization_changed_scopes s ON s.change_id = c.change_id "
                    + "WHERE c.status = 'WAITING' AND s.source_key IN (?, ?))",
                accountId,
                "ACCOUNT:" + accountId,
                "GLOBAL_ROLES:" + accountId)
            .get(0, Boolean.class);
    // An unreadable result must never be interpreted as clear authority.
    return !Boolean.FALSE.equals(pending);
  }
}
