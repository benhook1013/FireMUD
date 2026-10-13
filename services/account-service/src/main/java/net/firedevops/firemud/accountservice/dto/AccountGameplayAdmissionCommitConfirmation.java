package net.firedevops.firemud.accountservice.dto;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation.State;

/**
 * Non-authorizing readback of PostgreSQL's durable confirmation for one original COMMITTED lease.
 *
 * <p>This immutable value preserves the exact lease operation and database-stamped proof fields. It
 * does not prove current Account authority, cleanup, or permission to admit gameplay.
 */
public record AccountGameplayAdmissionCommitConfirmation(
    AccountGameplayAdmissionLeaseOperation operation,
    short confirmationVersion,
    UUID requestId,
    UUID accountId,
    UUID leaseId,
    long leaseFence,
    String evidenceSha256,
    UUID bindingDecisionId,
    long expiresAtMs,
    String finalizationXid,
    String walInsertLsn,
    String walFlushLsn,
    long committedBeforeMs,
    String confirmationXid) {
  private static final BigInteger UINT32_MAX =
      BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
  private static final BigInteger UINT64_MAX =
      BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

  public AccountGameplayAdmissionCommitConfirmation {
    Objects.requireNonNull(operation, "original committed lease operation is required");
    Objects.requireNonNull(requestId, "request ID is required");
    Objects.requireNonNull(accountId, "Account ID is required");
    Objects.requireNonNull(leaseId, "lease ID is required");
    Objects.requireNonNull(evidenceSha256, "evidence digest is required");
    Objects.requireNonNull(bindingDecisionId, "binding decision ID is required");
    Objects.requireNonNull(finalizationXid, "finalization transaction ID is required");
    Objects.requireNonNull(walInsertLsn, "WAL insert LSN is required");
    Objects.requireNonNull(walFlushLsn, "WAL flush LSN is required");
    Objects.requireNonNull(confirmationXid, "confirmation transaction ID is required");

    var evidence = operation.evidence();
    var carrier = evidence.carrier();
    var scope = (java.util.Map<?, ?>) carrier.get("bindingScope");
    UUID expectedRequest = UUID.fromString((String) carrier.get("requestId"));
    UUID expectedLease = UUID.fromString((String) carrier.get("leaseId"));
    UUID expectedAccount = UUID.fromString((String) scope.get("accountId"));
    long expectedFence = evidence.leaseFence().longValueExact();
    long expectedExpiry = Long.parseLong((String) carrier.get("expiresAt"));

    if (operation.state() != State.COMMITTED
        || operation.bindingDecisionId() == null
        || confirmationVersion != 1
        || !requestId.equals(expectedRequest)
        || !accountId.equals(expectedAccount)
        || !leaseId.equals(expectedLease)
        || leaseFence <= 0L
        || leaseFence != expectedFence
        || !evidenceSha256.matches("[0-9a-f]{64}")
        || !evidenceSha256.equals(evidence.sha256())
        || !bindingDecisionId.equals(operation.bindingDecisionId())
        || expiresAtMs <= 0L
        || expiresAtMs != expectedExpiry
        || committedBeforeMs <= 0L
        || committedBeforeMs >= expiresAtMs) {
      throw invalid();
    }

    requireCanonicalFullXid(finalizationXid);
    requireCanonicalFullXid(confirmationXid);
    if (finalizationXid.equals(confirmationXid)) throw invalid();
    BigInteger insert = parseLsn(walInsertLsn);
    BigInteger flush = parseLsn(walFlushLsn);
    if (insert.signum() <= 0 || flush.compareTo(insert) < 0) throw invalid();
  }

  private static void requireCanonicalFullXid(String value) {
    if (value.isEmpty() || !value.matches("[1-9][0-9]*")) throw invalid();
    try {
      BigInteger xid = new BigInteger(value);
      if (xid.signum() <= 0 || xid.compareTo(UINT64_MAX) > 0) throw invalid();
    } catch (NumberFormatException failure) {
      throw invalid();
    }
  }

  private static BigInteger parseLsn(String value) {
    if (!value.matches("(0|[1-9A-F][0-9A-F]{0,7})/(0|[1-9A-F][0-9A-F]{0,7})")) throw invalid();
    try {
      int separator = value.indexOf('/');
      BigInteger high = new BigInteger(value.substring(0, separator), 16);
      BigInteger low = new BigInteger(value.substring(separator + 1), 16);
      if (high.compareTo(UINT32_MAX) > 0 || low.compareTo(UINT32_MAX) > 0) throw invalid();
      return high.shiftLeft(32).add(low);
    } catch (NumberFormatException failure) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid durable Account admission commit confirmation");
  }

  @Override
  public String toString() {
    return "AccountGameplayAdmissionCommitConfirmation[requestId="
        + requestId
        + ", confirmationVersion="
        + confirmationVersion
        + "]";
  }
}
