package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionCommitConfirmation;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation.State;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered, non-admitting storage confirmation for an already retained COMMITTED lease.
 *
 * <p>The retained historical database function alone independently reads a durability receipt. A
 * raw operation state, timestamp, visible receipt row, or returned Java clock value is
 * insufficient.
 */
public final class AccountGameplayAdmissionCommitConfirmationRepository {
  private final DSLContext dsl;
  private final AccountGameplayAdmissionLeaseRepository leaseRepository;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Private injected persistence context; the repository never exposes it")
  public AccountGameplayAdmissionCommitConfirmationRepository(
      DSLContext dsl, AccountGameplayAdmissionLeaseRepository leaseRepository) {
    this.dsl = Objects.requireNonNull(dsl);
    this.leaseRepository = Objects.requireNonNull(leaseRepository);
  }

  /** Reads only a committed, independently durable receipt; this path never creates one. */
  AccountGameplayAdmissionCommitConfirmation readCommitConfirmation(
      AccountGameplayAdmissionLeaseEvidence evidence, UUID bindingDecisionId) {
    UUID requestId = validateRequest(evidence, bindingDecisionId);
    Record row =
        dsl.fetchOne(
            "SELECT * FROM account_gameplay_admission_read_commit_confirmation(?, ?, ?)",
            requestId,
            evidence.sha256(),
            bindingDecisionId);
    if (row == null) throw unavailable();
    AccountGameplayAdmissionLeaseOperation operation = exactCommitted(evidence, bindingDecisionId);
    return confirmation(row, operation);
  }

  private static UUID validateRequest(
      AccountGameplayAdmissionLeaseEvidence evidence, UUID bindingDecisionId) {
    requireWritableSerializableTransaction();
    Objects.requireNonNull(evidence);
    UUID requestId = requestId(evidence);
    requireUuidV4(requestId);
    requireUuidV4(bindingDecisionId);
    return requestId;
  }

  private AccountGameplayAdmissionLeaseOperation exactCommitted(
      AccountGameplayAdmissionLeaseEvidence evidence, UUID bindingDecisionId) {
    AccountGameplayAdmissionLeaseOperation operation =
        leaseRepository
            .readExact(evidence)
            .orElseThrow(AccountGameplayAdmissionCommitConfirmationRepository::unavailable);
    if (operation.state() != State.COMMITTED
        || !bindingDecisionId.equals(operation.bindingDecisionId())) throw unavailable();
    return operation;
  }

  private static AccountGameplayAdmissionCommitConfirmation confirmation(
      Record row, AccountGameplayAdmissionLeaseOperation operation) {
    if (row == null) throw unavailable();
    try {
      return new AccountGameplayAdmissionCommitConfirmation(
          operation,
          required(row, "confirmation_version", Short.class),
          required(row, "request_id", UUID.class),
          required(row, "account_uuid", UUID.class),
          required(row, "lease_id", UUID.class),
          required(row, "lease_fence", Long.class),
          required(row, "evidence_sha256", String.class),
          required(row, "binding_decision_id", UUID.class),
          required(row, "expires_at_ms", Long.class),
          required(row, "finalization_xid", String.class),
          required(row, "wal_insert_lsn", String.class),
          required(row, "wal_flush_lsn", String.class),
          required(row, "committed_before_ms", Long.class),
          required(row, "confirmation_xid", String.class));
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private static <T> T required(Record row, String field, Class<T> type) {
    T value = row.get(field, type);
    if (value == null) throw unavailable();
    return value;
  }

  private static UUID requestId(AccountGameplayAdmissionLeaseEvidence evidence) {
    return UUID.fromString((String) evidence.carrier().get("requestId"));
  }

  private static void requireWritableSerializableTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_SERIALIZABLE)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException("Writable SERIALIZABLE Account transaction required");
    }
  }

  private static void requireUuidV4(UUID value) {
    if (value == null || value.version() != 4 || value.variant() != 2)
      throw new IllegalArgumentException("Canonical non-nil UUIDv4 identity required");
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Durable Account admission commit confirmation unavailable");
  }
}
