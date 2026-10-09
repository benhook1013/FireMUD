package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Read-only exact lookup of immutable Account control-ui issuance evidence. */
@Repository
public class AccountControlUiCommittedIssuanceRepository {
  private static final String TABLE = "account_control_ui_issuance_operations";
  private static final Pattern TOKEN_HASH = Pattern.compile("[0-9a-f]{64}");
  private static final int MAX_CLAIMS = 16 * 1024;
  private static final int MAX_SOURCE = 131072;
  private static final int MAX_BUNDLE = 131072;
  private static final int MAX_SIGNER_RECEIPT = 65536;

  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = {"EI_EXPOSE_REP2", "CT_CONSTRUCTOR_THROW"},
      justification =
          "Spring injects the shared transaction-aware DSLContext; this proxyable repository only validates that dependency and acquires no resources in its constructor.")
  public AccountControlUiCommittedIssuanceRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  /** Reads only the exact still-COMMITTED issuance row in the caller's Account transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<CommittedIssuance> readCommitted(String tokenHash) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Committed Account issuance read requires an Account transaction");
    }
    if (tokenHash == null || !TOKEN_HASH.matcher(tokenHash).matches()) {
      throw denied();
    }
    Record row =
        dsl.fetchOne(
            "SELECT request_id, operation_id, token_jti, account_uuid, tenant_uuid, "
                + "caller_workload, caller_context_id, request_digest, claims_payload, "
                + "source_payload, bundle_payload, signer_receipt, issued_at_epoch_second, "
                + "expires_at_epoch_second, recovery_expires_at, status, token_hash, "
                + "pending_registry, active_registry, pending_receipt, committed_at, "
                + "recovery_failed_at FROM "
                + TABLE
                + " WHERE token_hash = ? FOR SHARE",
            tokenHash);
    if (row == null) {
      return Optional.empty();
    }
    try {
      CommittedIssuance evidence = new CommittedIssuance(row);
      if (!"COMMITTED".equals(evidence.status())
          || !tokenHash.equals(evidence.tokenHash())
          || evidence.recoveryFailedAt() != null
          || evidence.committedAt() == null
          || evidence.pendingReceipt().length == 0) {
        throw denied();
      }
      return Optional.of(evidence);
    } catch (RuntimeException malformed) {
      throw denied();
    }
  }

  private static IllegalStateException denied() {
    return new IllegalStateException(
        "Exact committed Account control-ui issuance evidence unavailable");
  }

  private static byte[] bytes(Record row, String field, int maximum) {
    byte[] value = row.get(field, byte[].class);
    if (value == null || value.length == 0 || value.length > maximum) {
      throw denied();
    }
    return value.clone();
  }

  private static UUID uuid(Record row, String field) {
    UUID value = row.get(field, UUID.class);
    if (value == null || isNil(value)) {
      throw denied();
    }
    return value;
  }

  private static boolean isNil(UUID value) {
    return value.getMostSignificantBits() == 0L && value.getLeastSignificantBits() == 0L;
  }

  private static long positive(Record row, String field) {
    Long value = row.get(field, Long.class);
    if (value == null || value <= 0L) {
      throw denied();
    }
    return value;
  }

  private static String text(Record row, String field) {
    String value = row.get(field, String.class);
    if (value == null || value.isBlank()) {
      throw denied();
    }
    return value;
  }

  private static String tokenHash(Record row) {
    String value = row.get("token_hash", String.class);
    if (value == null || !TOKEN_HASH.matcher(value).matches()) {
      throw denied();
    }
    return value;
  }

  /** Defensive immutable owner row; it does not contain the compact JWT or response envelope. */
  public static final class CommittedIssuance {
    private final UUID requestId;
    private final UUID operationId;
    private final UUID tokenJti;
    private final UUID accountId;
    private final UUID tenantId;
    private final String callerWorkload;
    private final UUID callerContextId;
    private final String requestDigest;
    private final byte[] claims;
    private final byte[] source;
    private final byte[] bundle;
    private final byte[] signerReceipt;
    private final long issuedAtEpochSecond;
    private final long expiresAtEpochSecond;
    private final String status;
    private final String tokenHash;
    private final byte[] pendingRegistry;
    private final byte[] activeRegistry;
    private final byte[] pendingReceipt;
    private final java.time.OffsetDateTime committedAt;
    private final java.time.OffsetDateTime recoveryFailedAt;

    private CommittedIssuance(Record row) {
      requestId = uuid(row, "request_id");
      operationId = uuid(row, "operation_id");
      tokenJti = uuid(row, "token_jti");
      accountId = uuid(row, "account_uuid");
      tenantId = uuid(row, "tenant_uuid");
      callerWorkload = text(row, "caller_workload");
      callerContextId = uuid(row, "caller_context_id");
      requestDigest = digest(row, "request_digest");
      claims = bytes(row, "claims_payload", MAX_CLAIMS);
      source = bytes(row, "source_payload", MAX_SOURCE);
      bundle = bytes(row, "bundle_payload", MAX_BUNDLE);
      signerReceipt = bytes(row, "signer_receipt", MAX_SIGNER_RECEIPT);
      issuedAtEpochSecond = positive(row, "issued_at_epoch_second");
      expiresAtEpochSecond = positive(row, "expires_at_epoch_second");
      if (expiresAtEpochSecond <= issuedAtEpochSecond
          || expiresAtEpochSecond - issuedAtEpochSecond > 300L) {
        throw denied();
      }
      status = text(row, "status");
      tokenHash = AccountControlUiCommittedIssuanceRepository.tokenHash(row);
      pendingRegistry = bytes(row, "pending_registry", 32768);
      activeRegistry = bytes(row, "active_registry", 32768);
      pendingReceipt = bytes(row, "pending_receipt", 8192);
      committedAt = row.get("committed_at", java.time.OffsetDateTime.class);
      recoveryFailedAt = row.get("recovery_failed_at", java.time.OffsetDateTime.class);
    }

    private static String digest(Record row, String field) {
      String value = text(row, field);
      if (!Pattern.matches("[0-9a-f]{64}", value)) {
        throw denied();
      }
      return value;
    }

    public UUID requestId() {
      return requestId;
    }

    public UUID operationId() {
      return operationId;
    }

    public UUID tokenJti() {
      return tokenJti;
    }

    public UUID accountId() {
      return accountId;
    }

    public UUID tenantId() {
      return tenantId;
    }

    public String callerWorkload() {
      return callerWorkload;
    }

    public UUID callerContextId() {
      return callerContextId;
    }

    public String requestDigest() {
      return requestDigest;
    }

    public byte[] claims() {
      return claims.clone();
    }

    public byte[] source() {
      return source.clone();
    }

    public byte[] bundle() {
      return bundle.clone();
    }

    public byte[] signerReceipt() {
      return signerReceipt.clone();
    }

    public long issuedAtEpochSecond() {
      return issuedAtEpochSecond;
    }

    public long expiresAtEpochSecond() {
      return expiresAtEpochSecond;
    }

    public String status() {
      return status;
    }

    public String tokenHash() {
      return tokenHash;
    }

    public byte[] pendingRegistry() {
      return pendingRegistry.clone();
    }

    public byte[] activeRegistry() {
      return activeRegistry.clone();
    }

    public byte[] pendingReceipt() {
      return pendingReceipt.clone();
    }

    public java.time.OffsetDateTime committedAt() {
      return committedAt;
    }

    public java.time.OffsetDateTime recoveryFailedAt() {
      return recoveryFailedAt;
    }

    public long expiryMillis() {
      try {
        return Math.multiplyExact(expiresAtEpochSecond, 1000L);
      } catch (ArithmeticException malformed) {
        throw denied();
      }
    }

    @Override
    public String toString() {
      return "AccountControlUiCommittedIssuanceRepository.CommittedIssuance[redacted]";
    }
  }
}
