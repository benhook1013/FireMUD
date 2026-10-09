package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountLogoutRequestDigest;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Stores immutable source and lifecycle-result evidence for one Account logout-all operation. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class AccountLogoutAllOperationRepository {
  private static final String TABLE = "account_logout_all_operation_receipts";
  private static final String EVENT_ID_PREFIX = "account-logout-all-event-v1:";
  private static final String LIFECYCLE_RESULT = "LOGOUT_ALL_COMMITTED";
  private static final String SELECT_COLUMNS =
      "request_id, account_id, account_uuid, operation_kind, request_digest_version, "
          + "request_digest, presented_token_hash, token_profile, outbox_stream_key, outbox_sequence, "
          + "event_id, event_digest, account_authority_generation, account_source_version, "
          + "issuance_fence, issuance_fence_source_version, lifecycle_result";
  private static final HexFormat HEX = HexFormat.of();

  private final DSLContext dsl;

  public AccountLogoutAllOperationRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /** Exact request lookup; callers compare every immutable request binding before recovery. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<LogoutAllReceipt> findByRequestId(UUID requestId) {
    requireOwnerTransaction();
    requireRequestId(requestId);
    Record row =
        dsl.fetchOne(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE + " WHERE request_id = ?", requestId);
    return Optional.ofNullable(row == null ? null : toReceipt(row));
  }

  /**
   * Token identity lookup prevents an old presented token from being rebound to another request.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<LogoutAllReceipt> findByPresentedTokenHash(String tokenHash) {
    requireOwnerTransaction();
    byte[] hash = decodeDigest(tokenHash, "presented token hash");
    Record row =
        dsl.fetchOne(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE + " WHERE presented_token_hash = ?",
            hash);
    return Optional.ofNullable(row == null ? null : toReceipt(row));
  }

  /** Inserts the one immutable lifecycle receipt for the source event. */
  @Transactional(propagation = Propagation.MANDATORY)
  public LogoutAllReceipt insert(LogoutAllReceipt receipt) {
    requireOwnerTransaction();
    validateReceipt(receipt);
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + TABLE
                + " (request_id, account_id, account_uuid, operation_kind, "
                + "request_digest_version, request_digest, presented_token_hash, token_profile, "
                + "outbox_stream_key, outbox_sequence, event_id, event_digest, "
                + "account_authority_generation, account_source_version, issuance_fence, "
                + "issuance_fence_source_version, lifecycle_result) "
                + "VALUES (?, ?, ?, 'ACCOUNT_LOGOUT_ALL', 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'LOGOUT_ALL_COMMITTED')",
            receipt.requestId(),
            receipt.accountId(),
            receipt.accountUuid(),
            decodeDigest(receipt.requestDigest(), "request digest"),
            decodeDigest(receipt.presentedTokenHash(), "presented token hash"),
            receipt.tokenProfile(),
            receipt.outboxStreamKey(),
            receipt.outboxSequence(),
            receipt.eventId(),
            receipt.eventDigest(),
            receipt.accountAuthorityGeneration(),
            receipt.accountSourceVersion(),
            receipt.issuanceFence(),
            receipt.issuanceFenceSourceVersion());
    if (inserted != 1) {
      throw new IllegalStateException("Logout-all operation receipt was not inserted");
    }
    return receipt;
  }

  private LogoutAllReceipt toReceipt(Record row) {
    return new LogoutAllReceipt(
        requiredUuid(row.get("request_id", UUID.class), "request ID"),
        requiredPositive(row.get("account_id", Long.class), "Account association"),
        requiredUuid(row.get("account_uuid", UUID.class), "Account UUID"),
        requireText(row.get("operation_kind", String.class), "operation kind"),
        requiredPositive(row.get("request_digest_version", Integer.class), "digest version"),
        encodeDigest(row.get("request_digest", byte[].class), "request digest"),
        encodeDigest(row.get("presented_token_hash", byte[].class), "presented token hash"),
        requireText(row.get("token_profile", String.class), "token profile"),
        requireText(row.get("outbox_stream_key", String.class), "outbox stream key"),
        requiredPositive(row.get("outbox_sequence", Long.class), "outbox sequence"),
        requireText(row.get("event_id", String.class), "event ID"),
        requireText(row.get("event_digest", String.class), "event digest"),
        requiredPositive(
            row.get("account_authority_generation", Long.class), "Account authority generation"),
        requiredPositive(row.get("account_source_version", Long.class), "Account source version"),
        requiredPositive(row.get("issuance_fence", Long.class), "issuance fence"),
        requiredPositive(
            row.get("issuance_fence_source_version", Long.class), "issuance-fence source version"),
        requireText(row.get("lifecycle_result", String.class), "lifecycle result"));
  }

  private static void validateReceipt(LogoutAllReceipt receipt) {
    Objects.requireNonNull(receipt, "logout-all receipt is required");
    requireRequestId(receipt.requestId());
    decodeDigest(receipt.requestDigest(), "request digest");
    decodeDigest(receipt.presentedTokenHash(), "presented token hash");
    AccountLogoutRequestDigest.validateTokenProfile(receipt.tokenProfile());
    if (receipt.accountId() <= 0L
        || receipt.accountUuid() == null
        || isNil(receipt.accountUuid())
        || receipt.requestDigestVersion() != 1
        || !AccountLogoutRequestDigest.accountLogoutAll(
                receipt.accountUuid(), receipt.tokenProfile(), receipt.presentedTokenHash())
            .equals(receipt.requestDigest())
        || receipt.outboxSequence() <= 0L
        || receipt.accountAuthorityGeneration() <= 0L
        || receipt.accountSourceVersion() <= 0L
        || receipt.issuanceFence() <= 0L
        || receipt.issuanceFenceSourceVersion() <= 0L
        || !accountStreamKey(receipt.accountUuid()).equals(receipt.outboxStreamKey())
        || !expectedEventId(receipt.requestId()).equals(receipt.eventId())
        || receipt.eventDigest() == null
        || !receipt.eventDigest().matches("sha256:[0-9a-f]{64}")
        || !LIFECYCLE_RESULT.equals(receipt.lifecycleResult())) {
      throw new IllegalArgumentException("Logout-all operation receipt is malformed");
    }
    requireBoundedText(receipt.operationKind(), "operation kind", 32);
    if (!"ACCOUNT_LOGOUT_ALL".equals(receipt.operationKind())) {
      throw new IllegalArgumentException("Logout-all operation kind is invalid");
    }
  }

  private static void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Logout-all operation storage requires an active Account transaction");
    }
  }

  private static UUID requiredUuid(UUID value, String field) {
    if (value == null || isNil(value)) {
      throw new IllegalStateException("Logout-all receipt " + field + " is missing or invalid");
    }
    return value;
  }

  private static long requiredPositive(Long value, String field) {
    if (value == null || value <= 0L) {
      throw new IllegalStateException("Logout-all receipt " + field + " is missing or invalid");
    }
    return value;
  }

  private static int requiredPositive(Integer value, String field) {
    if (value == null || value <= 0) {
      throw new IllegalStateException("Logout-all receipt " + field + " is missing or invalid");
    }
    return value;
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Logout-all receipt " + field + " is missing or invalid");
    }
    return value;
  }

  private static void requireBoundedText(String value, String field, int maximumLength) {
    if (value == null || value.isBlank() || value.length() > maximumLength) {
      throw new IllegalArgumentException(
          "Logout-all receipt " + field + " must contain 1 to " + maximumLength + " characters");
    }
  }

  private static void requireRequestId(UUID requestId) {
    if (requestId == null || isNil(requestId)) {
      throw new IllegalArgumentException("A canonical non-nil logout-all request UUID is required");
    }
  }

  private static byte[] decodeDigest(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Logout-all receipt " + field + " is malformed");
    }
    return HEX.parseHex(value);
  }

  private static String encodeDigest(byte[] value, String field) {
    if (value == null || value.length != 32) {
      throw new IllegalStateException("Logout-all receipt " + field + " is missing or invalid");
    }
    return HEX.formatHex(value);
  }

  private static String accountStreamKey(UUID accountUuid) {
    return "account:auth-authority:v1:account/" + accountUuid;
  }

  private static String expectedEventId(UUID requestId) {
    return EVENT_ID_PREFIX + requestId;
  }

  private static boolean isNil(UUID value) {
    return new UUID(0L, 0L).equals(value);
  }

  /** Immutable committed request binding, source checkpoint and lifecycle-only result. */
  public record LogoutAllReceipt(
      UUID requestId,
      long accountId,
      UUID accountUuid,
      String operationKind,
      int requestDigestVersion,
      String requestDigest,
      String presentedTokenHash,
      String tokenProfile,
      String outboxStreamKey,
      long outboxSequence,
      String eventId,
      String eventDigest,
      long accountAuthorityGeneration,
      long accountSourceVersion,
      long issuanceFence,
      long issuanceFenceSourceVersion,
      String lifecycleResult) {
    public LogoutAllReceipt {
      requireRequestId(requestId);
      decodeDigest(requestDigest, "request digest");
      decodeDigest(presentedTokenHash, "presented token hash");
      AccountLogoutRequestDigest.validateTokenProfile(tokenProfile);
      if (accountId <= 0L
          || accountUuid == null
          || isNil(accountUuid)
          || requestDigestVersion != 1
          || !AccountLogoutRequestDigest.accountLogoutAll(
                  accountUuid, tokenProfile, presentedTokenHash)
              .equals(requestDigest)
          || outboxSequence <= 0L
          || accountAuthorityGeneration <= 0L
          || accountSourceVersion <= 0L
          || issuanceFence <= 0L
          || issuanceFenceSourceVersion <= 0L
          || !accountStreamKey(accountUuid).equals(outboxStreamKey)
          || !expectedEventId(requestId).equals(eventId)
          || eventDigest == null
          || !eventDigest.matches("sha256:[0-9a-f]{64}")
          || !LIFECYCLE_RESULT.equals(lifecycleResult)) {
        throw new IllegalArgumentException("Logout-all operation receipt is malformed");
      }
      requireBoundedText(operationKind, "operation kind", 32);
      if (!"ACCOUNT_LOGOUT_ALL".equals(operationKind)) {
        throw new IllegalArgumentException("Logout-all operation kind is invalid");
      }
    }

    public static LogoutAllReceipt committed(
        UUID requestId,
        long accountId,
        UUID accountUuid,
        String requestDigest,
        String presentedTokenHash,
        String tokenProfile,
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
            "Committed logout-all authority and issuance fence must match exactly");
      }
      return new LogoutAllReceipt(
          requestId,
          accountId,
          accountUuid,
          "ACCOUNT_LOGOUT_ALL",
          1,
          requestDigest,
          presentedTokenHash,
          tokenProfile,
          outboxStreamKey,
          outboxSequence,
          eventId,
          eventDigest,
          accountAuthority.generation(),
          accountAuthority.sourceVersion(),
          issuanceFence.value(),
          issuanceFence.sourceVersion(),
          LIFECYCLE_RESULT);
    }

    public IssuanceFence issuanceFenceEvidence() {
      return new IssuanceFence(accountUuid, issuanceFence, issuanceFenceSourceVersion);
    }
  }
}
