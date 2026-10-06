package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence;
import net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence.State;
import net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence.TokenIdentity;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Account-row-first serialization of admission with exact-token revocation intent. Initial rows
 * come only from the real SQL COMMITTED issuance writer. This is not a logout finalizer: no public
 * method accepts a claimed Gateway acknowledgement or commits revocation completion.
 */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Private injected persistence context")
public class AccountGameplayTokenIdentityFenceRepository {
  private static final String TABLE = "account_gameplay_token_identity_fences";
  private static final String COLUMNS =
      "account_uuid, operation_id, issuance_request_id, token_hash, token_jti, "
          + "not_before_epoch_second, token_generation, issuance_fence, token_identity_fence, "
          + "state, revocation_request_id, revocation_digest";
  private final DSLContext dsl;

  public AccountGameplayTokenIdentityFenceRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  /** Must precede all authority, token, operation and admission row locks in the transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockAccountForUpdate(UUID accountId) {
    requireTransaction();
    Objects.requireNonNull(accountId);
    if (dsl.fetchOne(
            "SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE", accountId)
        == null) throw new TokenFenceUnavailableException();
  }

  /** Exact current fence for reserve/finalize; a durable revocation intent denies immediately. */
  @Transactional(propagation = Propagation.MANDATORY)
  public AccountGameplayTokenIdentityFence requireActiveForUpdate(TokenIdentity identity) {
    requireTransaction();
    Objects.requireNonNull(identity);
    lockAccountForUpdate(identity.accountId());
    AccountGameplayTokenIdentityFence fence = requireExact(identity, true);
    if (fence.state() != State.ACTIVE) throw new TokenRevokedException();
    return fence;
  }

  /** Package-private writer called only after the real issuance COMMITTED proof was validated. */
  AccountGameplayTokenIdentityFence registerCommittedIssuance(TokenIdentity identity) {
    requireTransaction();
    lockAccountForUpdate(identity.accountId());
    dsl.execute(
        "INSERT INTO "
            + TABLE
            + " (account_uuid, operation_id, issuance_request_id, token_hash, "
            + "token_jti, not_before_epoch_second, token_generation, issuance_fence) "
            + "SELECT account_uuid, operation_id, request_id, token_hash, token_jti, "
            + "not_before_epoch_second, token_generation, issuance_fence "
            + "FROM account_gameplay_delegation_issuance_operations "
            + "WHERE operation_id = ? AND request_id = ? AND account_uuid = ? "
            + "AND token_hash = ? AND token_jti = ? AND not_before_epoch_second = ? "
            + "AND token_generation = ? AND issuance_fence = ? AND status = 'COMMITTED' "
            + "ON CONFLICT (operation_id) DO NOTHING",
        identity.operationId(),
        identity.issuanceRequestId(),
        identity.accountId(),
        identity.tokenSha256(),
        identity.tokenJti(),
        identity.notBeforeEpochSecond(),
        identity.tokenGeneration(),
        identity.issuanceFence());
    return requireActiveForUpdate(identity);
  }

  /** Independent metadata readback, including the source COMMITTED identity, never registration. */
  AccountGameplayTokenIdentityFence requireActiveReadback(TokenIdentity identity) {
    AccountGameplayTokenIdentityFence fence = requireExact(identity, false);
    if (fence.state() != State.ACTIVE) throw new TokenRevokedException();
    return fence;
  }

  /**
   * Durably starts the exact bounded revocation intent. Same request/digest replays its stored
   * result, and changed requests conflict. No registry deletion or logout-success claim follows.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public AccountGameplayTokenIdentityFence beginRevocationIntent(
      TokenIdentity identity, UUID requestId, String digest) {
    requireTransaction();
    Objects.requireNonNull(identity);
    if (!revocationDigest(identity, requestId).equals(digest)) {
      throw new IllegalArgumentException("Exact Account revocation digest required");
    }
    lockAccountForUpdate(identity.accountId());
    AccountGameplayTokenIdentityFence before = requireExact(identity, true);
    if (before.state() != State.ACTIVE) {
      if (!requestId.equals(before.revocationRequestId())
          || !digest.equals(before.revocationDigest())) {
        throw new RevocationConflictException();
      }
      return before;
    }
    int changed =
        dsl.execute(
            "UPDATE "
                + TABLE
                + " SET state = 'PENDING', token_identity_fence = 2, "
                + "revocation_request_id = ?, revocation_digest = ? "
                + "WHERE operation_id = ? AND state = 'ACTIVE' AND token_identity_fence = 1",
            requestId,
            digest,
            identity.operationId());
    if (changed != 1) throw new TokenFenceUnavailableException();
    AccountGameplayTokenIdentityFence after = requireExact(identity, true);
    if (after.state() != State.PENDING
        || !requestId.equals(after.revocationRequestId())
        || !digest.equals(after.revocationDigest())) throw new TokenFenceUnavailableException();
    return after;
  }

  public static String revocationDigest(TokenIdentity identity, UUID requestId) {
    Objects.requireNonNull(identity);
    if (requestId == null || requestId.version() != 4 || requestId.variant() != 2) {
      throw new IllegalArgumentException("Canonical UUIDv4 revocation request required");
    }
    // All fields have fixed/canonical syntax, so the domain-separated framing is unambiguous.
    String input =
        "account-gameplay-token-revocation-intent/v1\n"
            + requestId
            + "\n"
            + identity.accountId()
            + "\n"
            + identity.operationId()
            + "\n"
            + identity.issuanceRequestId()
            + "\n"
            + identity.tokenSha256()
            + "\n"
            + identity.tokenJti()
            + "\n"
            + identity.notBeforeEpochSecond()
            + "\n"
            + identity.tokenGeneration()
            + "\n"
            + identity.issuanceFence()
            + "\n";
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(input.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private AccountGameplayTokenIdentityFence requireExact(TokenIdentity identity, boolean lock) {
    Record row =
        dsl.fetchOne(
            "SELECT "
                + COLUMNS
                + " FROM "
                + TABLE
                + " WHERE operation_id = ?"
                + (lock ? " FOR UPDATE" : ""),
            identity.operationId());
    if (row == null) throw new TokenFenceUnavailableException();
    AccountGameplayTokenIdentityFence fence = decode(row);
    if (!identity.equals(fence.identity())) throw new TokenFenceUnavailableException();
    Record source =
        dsl.fetchOne(
            "SELECT operation_id FROM account_gameplay_delegation_issuance_operations "
                + "WHERE operation_id = ? AND request_id = ? AND account_uuid = ? AND token_hash = ? "
                + "AND token_jti = ? AND not_before_epoch_second = ? AND token_generation = ? "
                + "AND issuance_fence = ? AND status = 'COMMITTED'",
            identity.operationId(),
            identity.issuanceRequestId(),
            identity.accountId(),
            identity.tokenSha256(),
            identity.tokenJti(),
            identity.notBeforeEpochSecond(),
            identity.tokenGeneration(),
            identity.issuanceFence());
    if (source == null) throw new TokenFenceUnavailableException();
    return fence;
  }

  private static AccountGameplayTokenIdentityFence decode(Record row) {
    try {
      return new AccountGameplayTokenIdentityFence(
          new TokenIdentity(
              row.get("account_uuid", UUID.class), row.get("operation_id", UUID.class),
              row.get("issuance_request_id", UUID.class), row.get("token_hash", String.class),
              row.get("token_jti", UUID.class), row.get("not_before_epoch_second", Long.class),
              row.get("token_generation", Long.class), row.get("issuance_fence", Long.class)),
          row.get("token_identity_fence", Long.class),
          State.valueOf(row.get("state", String.class)),
          row.get("revocation_request_id", UUID.class),
          row.get("revocation_digest", String.class));
    } catch (RuntimeException malformed) {
      throw new TokenFenceUnavailableException();
    }
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Writable Account transaction required");
    }
  }

  public static final class TokenFenceUnavailableException extends IllegalStateException {
    public TokenFenceUnavailableException() {
      super("Exact Account token fence unavailable");
    }
  }

  public static final class TokenRevokedException extends IllegalStateException {
    public TokenRevokedException() {
      super("Account token has a durable revocation fence");
    }
  }

  public static final class RevocationConflictException extends IllegalStateException {
    public RevocationConflictException() {
      super("Account token revocation request conflicts");
    }
  }
}
