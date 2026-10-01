package net.firedevops.firemud.accountservice.repository;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Readback-only view of one durable bare first-party LOGIN exchange operation. */
public record AccountBareLoginExchangeOperation(
    UUID operationId,
    UUID sourceConnectOperationId,
    long accountId,
    UUID tenantId,
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
    Instant createdAt,
    Instant updatedAt) {

  private static final Pattern SCOPE_HASH_PATTERN = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final int DIGEST_LENGTH_BYTES = 32;
  private static final int MAX_TOKEN_IDENTITY_LENGTH = 128;

  public enum Lifecycle {
    PENDING,
    COMMITTED;

    static Lifecycle fromDatabase(String value) {
      try {
        return Lifecycle.valueOf(value);
      } catch (RuntimeException exception) {
        throw new IllegalStateException("Stored bare LOGIN exchange lifecycle is invalid");
      }
    }
  }

  public AccountBareLoginExchangeOperation {
    if (operationId == null
        || sourceConnectOperationId == null
        || accountId <= 0
        || tenantId == null
        || tenantId.equals(new UUID(0L, 0L))
        || connectScopeHash == null
        || !SCOPE_HASH_PATTERN.matcher(connectScopeHash).matches()
        || requestId == null
        || requestId.isBlank()
        || requestId.length() > AccountBareLoginExchangeIdentity.MAX_REQUEST_ID_LENGTH
        || requestDigestVersion != 1
        || lifecycle == null
        || createdAt == null
        || updatedAt == null) {
      throw new IllegalArgumentException("Stored bare LOGIN exchange operation is malformed");
    }
    requestDigest = copyDigest(requestDigest, "request digest", false);
    tokenHash = copyDigest(tokenHash, "token hash", true);
    contextEvidenceDigest = copyDigest(contextEvidenceDigest, "context digest", true);
    authorityTupleDigest = copyDigest(authorityTupleDigest, "authority tuple digest", true);
    issuanceFenceDigest = copyDigest(issuanceFenceDigest, "issuance-fence digest", true);
    postconditionDigest = copyDigest(postconditionDigest, "postcondition digest", true);
    if ((tokenIdentity == null) != (tokenHash == null)) {
      throw new IllegalArgumentException("Bare LOGIN token identity and hash evidence must pair");
    }
    if (tokenIdentity != null
        && (tokenIdentity.isBlank()
            || tokenIdentity.length() > MAX_TOKEN_IDENTITY_LENGTH
            || tokenIdentity.indexOf('\0') >= 0)) {
      throw new IllegalArgumentException("Stored bare LOGIN token identity is malformed");
    }
    if (lifecycle == Lifecycle.PENDING) {
      if (outcomeCode != null) {
        throw new IllegalArgumentException("Pending bare LOGIN exchange cannot have an outcome");
      }
    } else if (!"SUCCESS".equals(outcomeCode)
        || tokenIdentity == null
        || contextEvidenceDigest == null
        || authorityTupleDigest == null
        || issuanceFenceDigest == null
        || postconditionDigest == null) {
      throw new IllegalArgumentException("Committed bare LOGIN exchange evidence is incomplete");
    }
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
    if (!(other instanceof AccountBareLoginExchangeOperation that)) {
      return false;
    }
    return accountId == that.accountId
        && tenantId.equals(that.tenantId)
        && requestDigestVersion == that.requestDigestVersion
        && operationId.equals(that.operationId)
        && sourceConnectOperationId.equals(that.sourceConnectOperationId)
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
        && Objects.equals(createdAt, that.createdAt)
        && Objects.equals(updatedAt, that.updatedAt);
  }

  @Override
  public int hashCode() {
    int result =
        Objects.hash(
            operationId,
            sourceConnectOperationId,
            accountId,
            tenantId,
            connectScopeHash,
            requestId,
            requestDigestVersion,
            lifecycle,
            outcomeCode,
            tokenIdentity,
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
    return "AccountBareLoginExchangeOperation{operationId="
        + operationId
        + ", sourceConnectOperationId="
        + sourceConnectOperationId
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
    if (value.length != DIGEST_LENGTH_BYTES) {
      throw new IllegalArgumentException(fieldName + " must be 32 bytes");
    }
    return Arrays.copyOf(value, value.length);
  }

  private static byte[] copyNullable(byte[] value) {
    return value == null ? null : value.clone();
  }
}
