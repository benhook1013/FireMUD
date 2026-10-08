package net.firedevops.firemud.accountservice.dto;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation.State;

/**
 * Immutable storage readback of a trusted-JVM original COMMIT acknowledgement.
 *
 * <p>{@code committedBeforeMs} is opaque in-process capability evidence produced after the original
 * physical COMMIT. SQL checks its shape and exact operation binding; SQL does not derive,
 * authenticate, or independently prove that clock value. This receipt grants no current authority,
 * Game Session installation, cleanup, or gameplay admission.
 */
public record AccountGameplayAdmissionOriginalAckReceipt(
    AccountGameplayAdmissionLeaseOperation operation,
    short schemaVersion,
    UUID requestId,
    UUID accountId,
    UUID leaseId,
    long leaseFence,
    String evidenceSha256,
    UUID bindingDecisionId,
    long expiresAtMs,
    String finalizationXid,
    long committedBeforeMs,
    String receiptXid) {
  private static final BigInteger UINT64_MAX =
      BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

  public AccountGameplayAdmissionOriginalAckReceipt {
    Objects.requireNonNull(operation, "original committed lease operation is required");
    Objects.requireNonNull(requestId, "request ID is required");
    Objects.requireNonNull(accountId, "Account ID is required");
    Objects.requireNonNull(leaseId, "lease ID is required");
    Objects.requireNonNull(evidenceSha256, "evidence digest is required");
    Objects.requireNonNull(bindingDecisionId, "binding decision ID is required");
    Objects.requireNonNull(finalizationXid, "finalization transaction ID is required");
    Objects.requireNonNull(receiptXid, "receipt transaction ID is required");

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
        || schemaVersion != 1
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
    requireCanonicalFullXid(receiptXid);
    if (finalizationXid.equals(receiptXid)) throw invalid();
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

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException(
        "Invalid original Account admission acknowledgement receipt");
  }

  @Override
  public String toString() {
    return "AccountGameplayAdmissionOriginalAckReceipt[requestId="
        + requestId
        + ", schemaVersion="
        + schemaVersion
        + "]";
  }
}
