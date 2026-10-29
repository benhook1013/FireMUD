package net.firedevops.firemud.accountservice.repository;

import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation.State;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionOriginalAckReceipt;
import net.firedevops.firemud.accountservice.repository.AccountGameplayAdmissionOriginalCommitExecutor.OriginalCommitAcknowledgement;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered storage for an opaque trusted-JVM acknowledgement of an original Account COMMIT.
 *
 * <p>The receipt preserves the exact original operation and post-COMMIT clock bound. SQL checks
 * shape and binding but does not authenticate the acknowledgement or derive its clock value. A
 * receipt is not current authority, installation, cleanup, or gameplay admission.
 */
final class AccountGameplayAdmissionOriginalAckReceiptRepository {
  private static final String RECEIPTS = "account_gameplay_admission_original_commit_ack_receipts";
  private static final String READ_DURABLY_SQL =
      "SELECT * FROM account_gameplay_admission_read_original_ack_receipt_durably(?, ?, ?)";

  private final DSLContext dsl;
  private final AccountGameplayAdmissionLeaseRepository leaseRepository;

  AccountGameplayAdmissionOriginalAckReceiptRepository(
      DSLContext dsl, AccountGameplayAdmissionLeaseRepository leaseRepository) {
    this.dsl = Objects.requireNonNull(dsl);
    this.leaseRepository = Objects.requireNonNull(leaseRepository);
  }

  /** Stores an exact typed ACK or returns its unchanged retained receipt. */
  Creation create(OriginalCommitAcknowledgement acknowledgement) {
    requireWritableSerializableTransaction();
    Objects.requireNonNull(acknowledgement);
    AccountGameplayAdmissionLeaseEvidence evidence =
        Objects.requireNonNull(acknowledgement.evidence());
    UUID decisionId = Objects.requireNonNull(acknowledgement.decisionId());
    UUID requestId = validateRequest(evidence, decisionId);

    Record inserted =
        dsl.fetchOne(
            "INSERT INTO "
                + RECEIPTS
                + " (request_id, evidence_sha256, binding_decision_id, finalization_xid, committed_before_ms) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (request_id) DO NOTHING RETURNING *",
            requestId,
            evidence.sha256(),
            decisionId,
            acknowledgement.finalizationXid(),
            acknowledgement.committedBeforeMs());
    AccountGameplayAdmissionLeaseOperation operation = exactCommitted(evidence, decisionId);
    AccountGameplayAdmissionOriginalAckReceipt receipt;
    boolean wasInserted;
    if (inserted != null) {
      receipt = decode(inserted, operation);
      wasInserted = true;
    } else {
      Record retained = selectReceipt(requestId);
      if (retained == null) throw unavailable();
      receipt = decode(retained, operation);
      wasInserted = false;
    }
    if (!receipt.finalizationXid().equals(acknowledgement.finalizationXid())
        || receipt.committedBeforeMs() != acknowledgement.committedBeforeMs()) {
      throw unavailable();
    }
    return new Creation(receipt, wasInserted);
  }

  /**
   * Exact storage-only read after the sibling owner proved receipt COMMIT or frozen WAL coverage.
   */
  AccountGameplayAdmissionOriginalAckReceipt readExact(
      AccountGameplayAdmissionLeaseEvidence evidence, UUID decisionId) {
    requireWritableSerializableTransaction();
    UUID requestId = validateRequest(evidence, decisionId);
    Record row = selectReceipt(requestId);
    if (row == null) throw unavailable();
    return decode(row, exactCommitted(evidence, decisionId));
  }

  /** Captures exact pending evidence; this method alone proves no WAL durability. */
  PendingHistoricalReceipt captureHistorical(
      AccountGameplayAdmissionLeaseEvidence evidence, UUID decisionId) {
    requireWritableSerializableTransaction();
    UUID requestId = validateRequest(evidence, decisionId);
    Record row =
        dsl.fetchOne(
            "SELECT (capture.captured_receipt).*, capture.wal_upper_fence::text AS wal_upper_fence "
                + "FROM account_gameplay_admission_capture_original_ack_receipt(?, ?, ?) capture",
            requestId,
            evidence.sha256(),
            decisionId);
    if (row == null) throw unavailable();
    return new PendingHistoricalReceipt(
        decode(row, exactCommitted(evidence, decisionId)),
        required(row, "wal_upper_fence", String.class));
  }

  /** Proves historical independent receipt durability without changing its original evidence. */
  AccountGameplayAdmissionOriginalAckReceipt readDurably(
      AccountGameplayAdmissionLeaseEvidence evidence, UUID decisionId) {
    requireWritableSerializableTransaction();
    UUID requestId = validateRequest(evidence, decisionId);
    Record row = dsl.fetchOne(READ_DURABLY_SQL, requestId, evidence.sha256(), decisionId);
    if (row == null) throw unavailable();
    return decode(row, exactCommitted(evidence, decisionId));
  }

  private Record selectReceipt(UUID requestId) {
    return dsl.fetchOne("SELECT * FROM " + RECEIPTS + " WHERE request_id = ?", requestId);
  }

  private AccountGameplayAdmissionLeaseOperation exactCommitted(
      AccountGameplayAdmissionLeaseEvidence evidence, UUID decisionId) {
    AccountGameplayAdmissionLeaseOperation operation =
        leaseRepository
            .readExact(evidence)
            .orElseThrow(AccountGameplayAdmissionOriginalAckReceiptRepository::unavailable);
    if (operation.state() != State.COMMITTED || !decisionId.equals(operation.bindingDecisionId()))
      throw unavailable();
    return operation;
  }

  private static AccountGameplayAdmissionOriginalAckReceipt decode(
      Record row, AccountGameplayAdmissionLeaseOperation operation) {
    try {
      return new AccountGameplayAdmissionOriginalAckReceipt(
          operation,
          required(row, "schema_version", Short.class),
          required(row, "request_id", UUID.class),
          required(row, "account_uuid", UUID.class),
          required(row, "lease_id", UUID.class),
          required(row, "lease_fence", Long.class),
          required(row, "evidence_sha256", String.class),
          required(row, "binding_decision_id", UUID.class),
          required(row, "expires_at_ms", Long.class),
          required(row, "finalization_xid", String.class),
          required(row, "committed_before_ms", Long.class),
          required(row, "receipt_xid", String.class));
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private static <T> T required(Record row, String field, Class<T> type) {
    if (row == null) throw unavailable();
    T value = row.get(field, type);
    if (value == null) throw unavailable();
    return value;
  }

  private static UUID validateRequest(
      AccountGameplayAdmissionLeaseEvidence evidence, UUID decisionId) {
    Objects.requireNonNull(evidence);
    UUID requestId = UUID.fromString((String) evidence.carrier().get("requestId"));
    requireUuidV4(requestId);
    requireUuidV4(decisionId);
    return requestId;
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
    if (value == null || value.version() != 4 || value.variant() != 2) {
      throw new IllegalArgumentException("Canonical non-nil UUIDv4 identity required");
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException(
        "Original Account admission acknowledgement receipt unavailable");
  }

  /** Non-wire SQL-issued pending capability. Only captureHistorical can stamp its frozen fence. */
  static final class PendingHistoricalReceipt {
    private final AccountGameplayAdmissionOriginalAckReceipt receipt;
    private final String walUpperFence;

    private PendingHistoricalReceipt(
        AccountGameplayAdmissionOriginalAckReceipt receipt, String walUpperFence) {
      this.receipt = Objects.requireNonNull(receipt);
      this.walUpperFence = Objects.requireNonNull(walUpperFence);
      if (!walUpperFence.matches("(0|[1-9A-F][0-9A-F]{0,7})/(0|[1-9A-F][0-9A-F]{0,7})")
          || "0/0".equals(walUpperFence)) throw unavailable();
    }

    AccountGameplayAdmissionOriginalAckReceipt receipt() {
      return receipt;
    }

    String walUpperFence() {
      return walUpperFence;
    }
  }

  record Creation(AccountGameplayAdmissionOriginalAckReceipt receipt, boolean inserted) {
    Creation {
      Objects.requireNonNull(receipt);
    }
  }
}
