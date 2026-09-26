package net.firedevops.firemud.accountservice.security;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Exact Account operation evidence authenticated as byte-framed AEAD additional data. Evidence
 * digests are Account-computed, versioned fixed-length digests of the exact canonical request,
 * context, authority tuple, issuance fence, and committed postconditions.
 */
public record AccountEnvelopeBinding(
    OperationKind operationKind,
    String operationId,
    String requestId,
    String accountId,
    String tenantId,
    String connectScopeId,
    String sourceConnectOperationId,
    byte[] requestDigest,
    byte[] contextEvidenceDigest,
    byte[] authorityTupleDigest,
    byte[] issuanceFenceDigest,
    byte[] postconditionDigest) {

  private static final int DIGEST_LENGTH_BYTES = 32;
  private static final int MAX_TEXT_LENGTH_BYTES = 4096;

  public enum OperationKind {
    CONNECT_TOKEN_ISSUANCE,
    BARE_LOGIN_EXCHANGE
  }

  public AccountEnvelopeBinding {
    Objects.requireNonNull(operationKind, "operationKind");
    operationId = requireText(operationId, "operationId");
    requestId = requireText(requestId, "requestId");
    accountId = requireText(accountId, "accountId");
    tenantId = requireText(tenantId, "tenantId");
    connectScopeId = requireText(connectScopeId, "connectScopeId");
    if (operationKind == OperationKind.BARE_LOGIN_EXCHANGE) {
      sourceConnectOperationId = requireText(sourceConnectOperationId, "sourceConnectOperationId");
    } else if (sourceConnectOperationId != null) {
      throw new IllegalArgumentException("sourceConnectOperationId is only valid for bare LOGIN");
    }
    requestDigest = copyDigest(requestDigest, "requestDigest");
    contextEvidenceDigest = copyDigest(contextEvidenceDigest, "contextEvidenceDigest");
    authorityTupleDigest = copyDigest(authorityTupleDigest, "authorityTupleDigest");
    issuanceFenceDigest = copyDigest(issuanceFenceDigest, "issuanceFenceDigest");
    postconditionDigest = copyDigest(postconditionDigest, "postconditionDigest");
  }

  @Override
  public byte[] requestDigest() {
    return requestDigest.clone();
  }

  @Override
  public byte[] contextEvidenceDigest() {
    return contextEvidenceDigest.clone();
  }

  @Override
  public byte[] authorityTupleDigest() {
    return authorityTupleDigest.clone();
  }

  @Override
  public byte[] issuanceFenceDigest() {
    return issuanceFenceDigest.clone();
  }

  @Override
  public byte[] postconditionDigest() {
    return postconditionDigest.clone();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof AccountEnvelopeBinding that)) {
      return false;
    }
    return operationKind == that.operationKind
        && operationId.equals(that.operationId)
        && requestId.equals(that.requestId)
        && accountId.equals(that.accountId)
        && tenantId.equals(that.tenantId)
        && connectScopeId.equals(that.connectScopeId)
        && Objects.equals(sourceConnectOperationId, that.sourceConnectOperationId)
        && Arrays.equals(requestDigest, that.requestDigest)
        && Arrays.equals(contextEvidenceDigest, that.contextEvidenceDigest)
        && Arrays.equals(authorityTupleDigest, that.authorityTupleDigest)
        && Arrays.equals(issuanceFenceDigest, that.issuanceFenceDigest)
        && Arrays.equals(postconditionDigest, that.postconditionDigest);
  }

  @Override
  public int hashCode() {
    int result =
        Objects.hash(
            operationKind,
            operationId,
            requestId,
            accountId,
            tenantId,
            connectScopeId,
            sourceConnectOperationId);
    result = 31 * result + Arrays.hashCode(requestDigest);
    result = 31 * result + Arrays.hashCode(contextEvidenceDigest);
    result = 31 * result + Arrays.hashCode(authorityTupleDigest);
    result = 31 * result + Arrays.hashCode(issuanceFenceDigest);
    result = 31 * result + Arrays.hashCode(postconditionDigest);
    return result;
  }

  @Override
  public String toString() {
    return "AccountEnvelopeBinding{operationKind=" + operationKind + ", evidence=<redacted>}";
  }

  private static String requireText(String value, String fieldName) {
    Objects.requireNonNull(value, fieldName);
    if (value.isBlank()
        || hasUnpairedSurrogate(value)
        || value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_LENGTH_BYTES) {
      throw new IllegalArgumentException("Invalid " + fieldName);
    }
    return value;
  }

  private static byte[] copyDigest(byte[] value, String fieldName) {
    Objects.requireNonNull(value, fieldName);
    if (value.length != DIGEST_LENGTH_BYTES) {
      throw new IllegalArgumentException(fieldName + " must be 32 bytes");
    }
    return Arrays.copyOf(value, value.length);
  }

  private static boolean hasUnpairedSurrogate(String value) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          return true;
        }
        index++;
      } else if (Character.isLowSurrogate(current)) {
        return true;
      }
    }
    return false;
  }
}
