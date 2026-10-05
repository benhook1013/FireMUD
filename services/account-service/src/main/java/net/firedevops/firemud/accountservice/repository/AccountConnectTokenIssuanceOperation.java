package net.firedevops.firemud.accountservice.repository;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Readback-only view of the non-secret durable issuance operation row. */
public record AccountConnectTokenIssuanceOperation(
    UUID operationId,
    long accountId,
    long tenantId,
    String connectScopeHash,
    String requestId,
    int requestDigestVersion,
    byte[] requestDigest,
    Lifecycle lifecycle,
    String outcomeCode,
    String tokenIdentity,
    byte[] tokenHash,
    byte[] contextEvidenceDigest,
    byte[] authorityTupleDigest,
    byte[] issuanceFenceDigest,
    byte[] postconditionDigest,
    int reconciliationAttemptCount,
    Instant lastReconciliationAttemptAt,
    String lastReconciliationAttemptReason,
    Instant nextReconciliationAttemptAt,
    Instant createdAt,
    Instant updatedAt) {

  public enum Lifecycle {
    PENDING,
    COMMITTED,
    FAILED,
    ABORTED;

    static Lifecycle fromDatabase(String value) {
      try {
        return Lifecycle.valueOf(value);
      } catch (RuntimeException exception) {
        throw new IllegalStateException("Stored connect-token issuance lifecycle is invalid");
      }
    }
  }

  public AccountConnectTokenIssuanceOperation {
    if (operationId == null
        || accountId <= 0
        || tenantId <= 0
        || connectScopeHash == null
        || requestId == null
        || requestDigestVersion != 1
        || lifecycle == null
        || reconciliationAttemptCount < 0
        || nextReconciliationAttemptAt == null
        || createdAt == null
        || updatedAt == null) {
      throw new IllegalArgumentException("Stored connect-token issuance operation is malformed");
    }
    requestDigest = copyDigest(requestDigest, "request digest", false);
    tokenHash = copyDigest(tokenHash, "token hash", true);
    contextEvidenceDigest = copyDigest(contextEvidenceDigest, "context digest", true);
    authorityTupleDigest = copyDigest(authorityTupleDigest, "authority tuple digest", true);
    issuanceFenceDigest = copyDigest(issuanceFenceDigest, "issuance-fence digest", true);
    postconditionDigest = copyDigest(postconditionDigest, "postcondition digest", true);
  }

  @Override
  public byte[] requestDigest() {
    return requestDigest.clone();
  }

  @Override
  public byte[] tokenHash() {
    return copyNullable(tokenHash);
  }

  @Override
  public byte[] contextEvidenceDigest() {
    return copyNullable(contextEvidenceDigest);
  }

  @Override
  public byte[] authorityTupleDigest() {
    return copyNullable(authorityTupleDigest);
  }

  @Override
  public byte[] issuanceFenceDigest() {
    return copyNullable(issuanceFenceDigest);
  }

  @Override
  public byte[] postconditionDigest() {
    return copyNullable(postconditionDigest);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof AccountConnectTokenIssuanceOperation that)) {
      return false;
    }
    return accountId == that.accountId
        && tenantId == that.tenantId
        && requestDigestVersion == that.requestDigestVersion
        && reconciliationAttemptCount == that.reconciliationAttemptCount
        && operationId.equals(that.operationId)
        && connectScopeHash.equals(that.connectScopeHash)
        && requestId.equals(that.requestId)
        && Arrays.equals(requestDigest, that.requestDigest)
        && lifecycle == that.lifecycle
        && Objects.equals(outcomeCode, that.outcomeCode)
        && Objects.equals(tokenIdentity, that.tokenIdentity)
        && Arrays.equals(tokenHash, that.tokenHash)
        && Arrays.equals(contextEvidenceDigest, that.contextEvidenceDigest)
        && Arrays.equals(authorityTupleDigest, that.authorityTupleDigest)
        && Arrays.equals(issuanceFenceDigest, that.issuanceFenceDigest)
        && Arrays.equals(postconditionDigest, that.postconditionDigest)
        && Objects.equals(lastReconciliationAttemptAt, that.lastReconciliationAttemptAt)
        && Objects.equals(lastReconciliationAttemptReason, that.lastReconciliationAttemptReason)
        && Objects.equals(nextReconciliationAttemptAt, that.nextReconciliationAttemptAt)
        && Objects.equals(createdAt, that.createdAt)
        && Objects.equals(updatedAt, that.updatedAt);
  }

  @Override
  public int hashCode() {
    int result =
        Objects.hash(
            operationId,
            accountId,
            tenantId,
            connectScopeHash,
            requestId,
            requestDigestVersion,
            lifecycle,
            outcomeCode,
            tokenIdentity,
            reconciliationAttemptCount,
            lastReconciliationAttemptAt,
            lastReconciliationAttemptReason,
            nextReconciliationAttemptAt,
            createdAt,
            updatedAt);
    result = 31 * result + Arrays.hashCode(requestDigest);
    result = 31 * result + Arrays.hashCode(tokenHash);
    result = 31 * result + Arrays.hashCode(contextEvidenceDigest);
    result = 31 * result + Arrays.hashCode(authorityTupleDigest);
    result = 31 * result + Arrays.hashCode(issuanceFenceDigest);
    result = 31 * result + Arrays.hashCode(postconditionDigest);
    return result;
  }

  @Override
  public String toString() {
    return "AccountConnectTokenIssuanceOperation{operationId="
        + operationId
        + ", accountId="
        + accountId
        + ", tenantId="
        + tenantId
        + ", requestId='"
        + requestId
        + "', lifecycle="
        + lifecycle
        + ", secretEvidence=<redacted>}";
  }

  private static byte[] copyDigest(byte[] value, String fieldName, boolean nullable) {
    if (value == null) {
      if (nullable) {
        return null;
      }
      throw new IllegalArgumentException(fieldName + " is required");
    }
    if (value.length != 32) {
      throw new IllegalArgumentException(fieldName + " must be 32 bytes");
    }
    return Arrays.copyOf(value, value.length);
  }

  private static byte[] copyNullable(byte[] value) {
    return value == null ? null : value.clone();
  }
}
