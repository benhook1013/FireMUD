package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Stores immutable proof for one Account-owned password-reset source commit. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class AccountPasswordResetOperationRepository {
  private static final String TABLE = "account_password_reset_operation_receipts";
  private static final String REQUEST_ID_PREFIX = "account-password-reset-request-v1:";
  private static final String EVENT_ID_PREFIX = "account-password-reset-event-v1:";
  private static final String SELECT_COLUMNS =
      "token_hash, account_id, account_uuid, operation_kind, request_id, "
          + "request_digest_version, request_digest, token_expires_at, "
          + "password_verifier_digest, outbox_stream_key, outbox_sequence, event_id, "
          + "event_digest, account_authority_generation, account_source_version, "
          + "issuance_fence, issuance_fence_source_version";
  private static final HexFormat HEX = HexFormat.of();

  private final DSLContext dsl;

  public AccountPasswordResetOperationRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /** Read-only lookup by the SHA-256 identity of the token, including after token consumption. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<PasswordResetReceipt> findByTokenHash(String tokenHash) {
    requireOwnerTransaction();
    byte[] tokenHashBytes = decodeDigest(tokenHash, "token hash");
    Record row =
        dsl.fetchOne(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE + " WHERE token_hash = ?",
            tokenHashBytes);
    return Optional.ofNullable(row == null ? null : toReceipt(row));
  }

  /** Read-only source-history lookup used to prove a progressed Account stream. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<PasswordResetReceipt> findByRequestId(String requestId) {
    requireOwnerTransaction();
    requireBoundedText(requestId, "request ID", 128);
    Record row =
        dsl.fetchOne(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE + " WHERE request_id = ?", requestId);
    return Optional.ofNullable(row == null ? null : toReceipt(row));
  }

  /** Inserts the one immutable receipt for this token hash. */
  @Transactional(propagation = Propagation.MANDATORY)
  public PasswordResetReceipt insert(PasswordResetReceipt receipt) {
    requireOwnerTransaction();
    validateReceipt(receipt);
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + TABLE
                + " (token_hash, account_id, account_uuid, operation_kind, request_id, "
                + "request_digest_version, request_digest, token_expires_at, "
                + "password_verifier_digest, outbox_stream_key, outbox_sequence, event_id, "
                + "event_digest, account_authority_generation, account_source_version, "
                + "issuance_fence, issuance_fence_source_version) "
                + "VALUES (?, ?, ?, 'PASSWORD_RESET', ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            decodeDigest(receipt.tokenHash(), "token hash"),
            receipt.accountId(),
            receipt.accountUuid(),
            receipt.requestId(),
            decodeDigest(receipt.requestDigest(), "request digest"),
            receipt.tokenExpiresAt(),
            decodeDigest(receipt.passwordVerifierDigest(), "password verifier digest"),
            receipt.outboxStreamKey(),
            receipt.outboxSequence(),
            receipt.eventId(),
            receipt.eventDigest(),
            receipt.accountAuthorityGeneration(),
            receipt.accountSourceVersion(),
            receipt.issuanceFence(),
            receipt.issuanceFenceSourceVersion());
    if (inserted != 1) {
      throw new IllegalStateException("Password-reset operation receipt was not inserted");
    }
    return receipt;
  }

  private PasswordResetReceipt toReceipt(Record row) {
    return new PasswordResetReceipt(
        requiredPositive(row.get("account_id", Long.class), "Account association"),
        Objects.requireNonNull(row.get("account_uuid", UUID.class), "receipt Account UUID"),
        encodeDigest(row.get("token_hash", byte[].class), "token hash"),
        requireText(row.get("operation_kind", String.class), "operation kind"),
        requireText(row.get("request_id", String.class), "request ID"),
        requiredPositive(row.get("request_digest_version", Integer.class), "digest version"),
        encodeDigest(row.get("request_digest", byte[].class), "request digest"),
        Objects.requireNonNull(
            row.get("token_expires_at", java.time.LocalDateTime.class), "token deadline"),
        encodeDigest(row.get("password_verifier_digest", byte[].class), "password verifier digest"),
        requireText(row.get("outbox_stream_key", String.class), "outbox stream key"),
        requiredPositive(row.get("outbox_sequence", Long.class), "outbox sequence"),
        requireText(row.get("event_id", String.class), "event ID"),
        requireText(row.get("event_digest", String.class), "event digest"),
        requiredPositive(
            row.get("account_authority_generation", Long.class), "Account authority generation"),
        requiredPositive(row.get("account_source_version", Long.class), "Account source version"),
        requiredPositive(row.get("issuance_fence", Long.class), "issuance fence"),
        requiredPositive(
            row.get("issuance_fence_source_version", Long.class), "issuance-fence source version"));
  }

  private static void validateReceipt(PasswordResetReceipt receipt) {
    Objects.requireNonNull(receipt, "password-reset receipt is required");
    decodeDigest(receipt.tokenHash(), "token hash");
    decodeDigest(receipt.requestDigest(), "request digest");
    decodeDigest(receipt.passwordVerifierDigest(), "password verifier digest");
    if (receipt.accountId() <= 0L
        || receipt.accountUuid() == null
        || new UUID(0L, 0L).equals(receipt.accountUuid())
        || receipt.requestDigestVersion() != 1
        || receipt.tokenExpiresAt() == null
        || receipt.outboxSequence() <= 0L
        || receipt.accountAuthorityGeneration() <= 0L
        || receipt.accountSourceVersion() <= 0L
        || receipt.issuanceFence() <= 0L
        || receipt.issuanceFenceSourceVersion() <= 0L
        || !accountStreamKey(receipt.accountUuid()).equals(receipt.outboxStreamKey())
        || receipt.eventDigest() == null
        || !receipt.eventDigest().matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Password-reset operation receipt is malformed");
    }
    requireBoundedText(receipt.operationKind(), "operation kind", 32);
    if (!"PASSWORD_RESET".equals(receipt.operationKind())) {
      throw new IllegalArgumentException("Password-reset operation kind is invalid");
    }
    requireBoundedText(receipt.requestId(), "request ID", 128);
    requireBoundedText(receipt.eventId(), "event ID", 128);
    if (!(REQUEST_ID_PREFIX + receipt.tokenHash()).equals(receipt.requestId())
        || !(EVENT_ID_PREFIX + receipt.tokenHash()).equals(receipt.eventId())) {
      throw new IllegalArgumentException(
          "Password-reset operation IDs must be derived from the token hash");
    }
  }

  private static void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Password-reset operation storage requires an active Account transaction");
    }
  }

  private static long requiredPositive(Long value, String field) {
    if (value == null || value <= 0L) {
      throw new IllegalStateException("Password-reset receipt " + field + " is missing or invalid");
    }
    return value;
  }

  private static int requiredPositive(Integer value, String field) {
    if (value == null || value <= 0) {
      throw new IllegalStateException("Password-reset receipt " + field + " is missing or invalid");
    }
    return value;
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Password-reset receipt " + field + " is missing or invalid");
    }
    return value;
  }

  private static void requireBoundedText(String value, String field, int maximumLength) {
    if (value == null || value.isBlank() || value.length() > maximumLength) {
      throw new IllegalArgumentException(
          "Password-reset receipt "
              + field
              + " must contain 1 to "
              + maximumLength
              + " characters");
    }
  }

  private static byte[] decodeDigest(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Password-reset receipt " + field + " is malformed");
    }
    return HEX.parseHex(value);
  }

  private static String encodeDigest(byte[] value, String field) {
    if (value == null || value.length != 32) {
      throw new IllegalStateException("Password-reset receipt " + field + " is missing or invalid");
    }
    return HEX.formatHex(value);
  }

  private static String accountStreamKey(UUID accountUuid) {
    return "account:auth-authority:v1:account/" + accountUuid;
  }

  /** Immutable commit and retry evidence; digest values are lowercase SHA-256 hex strings. */
  public record PasswordResetReceipt(
      long accountId,
      UUID accountUuid,
      String tokenHash,
      String operationKind,
      String requestId,
      int requestDigestVersion,
      String requestDigest,
      java.time.LocalDateTime tokenExpiresAt,
      String passwordVerifierDigest,
      String outboxStreamKey,
      long outboxSequence,
      String eventId,
      String eventDigest,
      long accountAuthorityGeneration,
      long accountSourceVersion,
      long issuanceFence,
      long issuanceFenceSourceVersion) {
    public PasswordResetReceipt {
      decodeDigest(tokenHash, "token hash");
      decodeDigest(requestDigest, "request digest");
      decodeDigest(passwordVerifierDigest, "password verifier digest");
      if (accountId <= 0L
          || accountUuid == null
          || new UUID(0L, 0L).equals(accountUuid)
          || requestDigestVersion != 1
          || tokenExpiresAt == null
          || outboxSequence <= 0L
          || accountAuthorityGeneration <= 0L
          || accountSourceVersion <= 0L
          || issuanceFence <= 0L
          || issuanceFenceSourceVersion <= 0L
          || !accountStreamKey(accountUuid).equals(outboxStreamKey)
          || eventDigest == null
          || !eventDigest.matches("sha256:[0-9a-f]{64}")) {
        throw new IllegalArgumentException("Password-reset operation receipt is malformed");
      }
      requireBoundedText(operationKind, "operation kind", 32);
      if (!"PASSWORD_RESET".equals(operationKind)) {
        throw new IllegalArgumentException("Password-reset operation kind is invalid");
      }
      requireBoundedText(requestId, "request ID", 128);
      requireBoundedText(eventId, "event ID", 128);
      if (!(REQUEST_ID_PREFIX + tokenHash).equals(requestId)
          || !(EVENT_ID_PREFIX + tokenHash).equals(eventId)) {
        throw new IllegalArgumentException(
            "Password-reset operation IDs must be derived from the token hash");
      }
    }

    public static PasswordResetReceipt committed(
        long accountId,
        UUID accountUuid,
        String tokenHash,
        String requestId,
        String requestDigest,
        java.time.LocalDateTime tokenExpiresAt,
        String passwordVerifierDigest,
        String outboxStreamKey,
        long outboxSequence,
        String eventId,
        String eventDigest,
        ScopeState accountAuthority,
        IssuanceFence issuanceFence) {
      if (accountAuthority == null
          || issuanceFence == null
          || accountAuthority.issuanceFence() == null
          || !issuanceFence.equals(accountAuthority.issuanceFence())) {
        throw new IllegalArgumentException(
            "Committed password-reset authority and issuance fence must match exactly");
      }
      return new PasswordResetReceipt(
          accountId,
          accountUuid,
          tokenHash,
          "PASSWORD_RESET",
          requestId,
          1,
          requestDigest,
          tokenExpiresAt,
          passwordVerifierDigest,
          outboxStreamKey,
          outboxSequence,
          eventId,
          eventDigest,
          accountAuthority.generation(),
          accountAuthority.sourceVersion(),
          issuanceFence.value(),
          issuanceFence.sourceVersion());
    }

    public IssuanceFence issuanceFenceEvidence() {
      return new IssuanceFence(accountUuid, issuanceFence, issuanceFenceSourceVersion);
    }
  }

  /** A token identity was already committed with a different supplied password. */
  public static final class OperationConflictException extends IllegalStateException {
    public OperationConflictException(String message) {
      super(message);
    }
  }
}
