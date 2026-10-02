package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;
import net.firedevops.firemud.common.account.authority.IssuerProjectionReconciliationRequestDigestV1;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Stores immutable Account-owned issuer projection reconciliation capture receipts. */
@Repository
public class AccountIssuerProjectionReconciliationRepository {
  private static final String TABLE = "account_issuer_projection_reconciliation_receipts";
  private static final String EVENT_STREAM_PREFIX = "account:auth-authority:v1:issuer/";
  private static final String PROJECTION_KEY_PREFIX = "session:game:auth:issuer-generation:v1:";
  private static final HexFormat HEX = HexFormat.of();
  private static final String SELECT_COLUMNS =
      "operation_id, request_id, issuer_id, caller_workload_identity, projection_key, "
          + "request_digest_version, request_digest, issuer_auth_generation, source_version, "
          + "outbox_stream_key, outbox_sequence, event_outbox_sequence, event_id, event_digest, event_payload";

  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "The injected transaction-aware DSLContext is a shared internal collaborator.")
  public AccountIssuerProjectionReconciliationRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /** Reads one immutable receipt by its stable exact issuer/request identity. */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public Optional<Receipt> findByIssuerAndRequestId(String issuerId, UUID requestId) {
    requireOwnerTransaction();
    requireText(issuerId, "issuer ID", 512);
    requireRequestId(requestId);
    Record row =
        dsl.fetchOne(
            "SELECT "
                + SELECT_COLUMNS
                + " FROM "
                + TABLE
                + " WHERE issuer_id = ? AND request_id = ?",
            issuerId,
            requestId);
    return Optional.ofNullable(row == null ? null : toReceipt(row));
  }

  /** Inserts the one immutable receipt for an issuer/request identity. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Receipt insert(Receipt receipt) {
    requireOwnerTransaction();
    validateReceipt(receipt);
    IssuerGenerationAuthorityEvent event = receipt.capturedSource().latestEvent().orElse(null);
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + TABLE
                + " (operation_id, request_id, issuer_id, caller_workload_identity, projection_key, "
                + "request_digest_version, request_digest, issuer_auth_generation, source_version, "
                + "outbox_stream_key, outbox_sequence, event_outbox_sequence, event_id, event_digest, event_payload) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            receipt.operationId(),
            receipt.requestId(),
            receipt.issuerId(),
            receipt.callerWorkloadIdentity(),
            receipt.projectionKey(),
            receipt.requestDigestVersion(),
            decodeDigest(receipt.requestDigest()),
            receipt.capturedSource().issuerAuthGeneration(),
            receipt.capturedSource().sourceVersion(),
            receipt.capturedSource().outboxStreamKey(),
            receipt.capturedSource().outboxSequence(),
            event == null ? null : receipt.capturedSource().outboxSequence(),
            event == null ? null : event.eventId(),
            event == null ? null : event.eventDigest(),
            event == null ? null : event.canonicalJsonUtf8());
    if (inserted != 1) {
      throw new IllegalStateException("Issuer projection reconciliation receipt was not inserted");
    }
    return receipt;
  }

  private Receipt toReceipt(Record row) {
    UUID operationId = requiredUuid(row.get("operation_id", UUID.class), "operation ID");
    UUID requestId = requiredUuid(row.get("request_id", UUID.class), "request ID");
    String issuerId = requiredText(row.get("issuer_id", String.class), "issuer ID", 512);
    String streamKey = requiredText(row.get("outbox_stream_key", String.class), "stream key", 2048);
    long sequence = requiredNonnegative(row.get("outbox_sequence", Long.class), "outbox sequence");
    Long eventSequence = row.get("event_outbox_sequence", Long.class);
    long generation =
        requiredPositive(row.get("issuer_auth_generation", Long.class), "issuer generation");
    long sourceVersion = requiredPositive(row.get("source_version", Long.class), "source version");
    String eventId = row.get("event_id", String.class);
    String eventDigest = row.get("event_digest", String.class);
    byte[] payload = row.get("event_payload", byte[].class);
    Optional<IssuerGenerationAuthorityEvent> latestEvent;
    if (sequence == 0L) {
      if (eventSequence != null || eventId != null || eventDigest != null || payload != null) {
        throw new IllegalStateException("Zero-sequence issuer receipt contains event evidence");
      }
      latestEvent = Optional.empty();
    } else {
      if (eventSequence == null
          || eventSequence != sequence
          || eventId == null
          || eventDigest == null
          || payload == null
          || payload.length == 0) {
        throw new IllegalStateException(
            "Positive issuer receipt is missing complete event evidence");
      }
      IssuerGenerationAuthorityEvent event =
          IssuerGenerationAuthorityEventV1Codec.verify(new String(payload, StandardCharsets.UTF_8));
      if (!MessageDigest.isEqual(payload, event.canonicalJsonUtf8())
          || !eventId.equals(event.eventId())
          || !eventDigest.equals(event.eventDigest())) {
        throw new IllegalStateException(
            "Issuer receipt event bytes contradict stored event fields");
      }
      latestEvent = Optional.of(event);
    }
    IssuerAuthoritySnapshot source =
        new IssuerAuthoritySnapshot(
            issuerId, generation, sourceVersion, streamKey, sequence, latestEvent);
    return new Receipt(
        operationId,
        requestId,
        issuerId,
        requiredText(
            row.get("caller_workload_identity", String.class), "caller workload identity", 512),
        requiredText(row.get("projection_key", String.class), "projection key", 2048),
        requiredPositive(
            row.get("request_digest_version", Integer.class), "request digest version"),
        encodeDigest(row.get("request_digest", byte[].class)),
        source);
  }

  private static void validateReceipt(Receipt receipt) {
    Objects.requireNonNull(receipt, "issuer projection reconciliation receipt is required");
    validateReceipt(
        receipt.operationId(),
        receipt.requestId(),
        receipt.issuerId(),
        receipt.callerWorkloadIdentity(),
        receipt.projectionKey(),
        receipt.requestDigestVersion(),
        receipt.requestDigest(),
        receipt.capturedSource());
  }

  private static void validateReceipt(
      UUID operationId,
      UUID requestId,
      String issuerId,
      String callerWorkloadIdentity,
      String projectionKey,
      int requestDigestVersion,
      String requestDigest,
      IssuerAuthoritySnapshot capturedSource) {
    requireRequestId(operationId);
    requireRequestId(requestId);
    requireText(issuerId, "issuer ID", 512);
    requireText(callerWorkloadIdentity, "caller workload identity", 512);
    requireText(projectionKey, "projection key", 2048);
    Objects.requireNonNull(capturedSource, "captured issuer source is required");
    if (requestDigest == null
        || requestDigestVersion != 1
        || !projectionKey.equals(PROJECTION_KEY_PREFIX + issuerId)
        || !issuerId.equals(capturedSource.issuerId())
        || !requestDigest.equals(
            Receipt.requestDigestFor(issuerId, callerWorkloadIdentity, projectionKey, requestId))) {
      throw new IllegalArgumentException("Issuer projection reconciliation receipt is malformed");
    }
    decodeDigest(requestDigest);
    IssuerGenerationAuthorityEvent event = capturedSource.latestEvent().orElse(null);
    if ((capturedSource.outboxSequence() == 0L) != (event == null)) {
      throw new IllegalArgumentException("Issuer receipt checkpoint and event evidence disagree");
    }
    if (event != null
        && (!capturedSource.outboxStreamKey().equals(event.outboxStreamKey())
            || !issuerId.equals(event.issuerId())
            || !Long.toString(capturedSource.outboxSequence()).equals(event.outboxSequence()))) {
      throw new IllegalArgumentException("Issuer receipt event does not match its checkpoint");
    }
  }

  private void requireOwnerTransaction() {
    if (dsl == null) {
      throw new IllegalStateException("Issuer reconciliation storage requires a DSLContext");
    }
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Issuer projection reconciliation storage requires an active Account transaction");
    }
  }

  private static void requireRequestId(UUID value) {
    if (value == null || new UUID(0L, 0L).equals(value)) {
      throw new IllegalArgumentException("A canonical non-nil reconciliation UUID is required");
    }
  }

  private static UUID requiredUuid(UUID value, String field) {
    requireRequestId(value);
    return value;
  }

  private static long requiredPositive(Long value, String field) {
    if (value == null || value <= 0L) {
      throw new IllegalStateException("Issuer reconciliation receipt " + field + " is invalid");
    }
    return value;
  }

  private static int requiredPositive(Integer value, String field) {
    if (value == null || value <= 0) {
      throw new IllegalStateException("Issuer reconciliation receipt " + field + " is invalid");
    }
    return value;
  }

  private static long requiredNonnegative(Long value, String field) {
    if (value == null || value < 0L) {
      throw new IllegalStateException("Issuer reconciliation receipt " + field + " is invalid");
    }
    return value;
  }

  private static String requiredText(String value, String field, int maximumLength) {
    requireText(value, field, maximumLength);
    return value;
  }

  private static void requireText(String value, String field, int maximumLength) {
    if (value == null || value.isBlank() || value.length() > maximumLength) {
      throw new IllegalArgumentException(
          "Issuer reconciliation receipt "
              + field
              + " must contain 1 to "
              + maximumLength
              + " characters");
    }
  }

  private static byte[] decodeDigest(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Issuer reconciliation request digest is malformed");
    }
    return HEX.parseHex(value);
  }

  private static String encodeDigest(byte[] value) {
    if (value == null || value.length != 32) {
      throw new IllegalStateException("Issuer reconciliation request digest is missing or invalid");
    }
    return HEX.formatHex(value);
  }

  /**
   * Complete immutable capture receipt; a receipt does not claim current authority after advance.
   */
  public record Receipt(
      UUID operationId,
      UUID requestId,
      String issuerId,
      String callerWorkloadIdentity,
      String projectionKey,
      int requestDigestVersion,
      String requestDigest,
      IssuerAuthoritySnapshot capturedSource) {
    public Receipt {
      validateReceipt(
          operationId,
          requestId,
          issuerId,
          callerWorkloadIdentity,
          projectionKey,
          requestDigestVersion,
          requestDigest,
          capturedSource);
    }

    /**
     * Computes the exact fixed six-field byte-framed request digest defined by the Account
     * contract.
     */
    public static String requestDigestFor(
        String issuerId, String callerWorkloadIdentity, String projectionKey, UUID requestId) {
      return IssuerProjectionReconciliationRequestDigestV1.digest(
          issuerId, callerWorkloadIdentity, projectionKey, requestId);
    }
  }

  /** The stable issuer/request key already exists with different immutable bindings. */
  public static final class IdempotencyConflictException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public IdempotencyConflictException(String message) {
      super(message);
    }
  }
}
