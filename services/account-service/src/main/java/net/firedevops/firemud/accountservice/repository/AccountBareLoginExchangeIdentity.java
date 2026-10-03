package net.firedevops.firemud.accountservice.repository;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Exact identity for one Account-owned bare first-party LOGIN exchange attempt. */
public record AccountBareLoginExchangeIdentity(
    UUID sourceConnectOperationId,
    long accountId,
    UUID tenantId,
    String connectScopeId,
    String requestId) {

  public static final int MAX_CONNECT_SCOPE_ID_UTF8_BYTES = 2048;
  public static final int MAX_REQUEST_ID_LENGTH = 128;

  public AccountBareLoginExchangeIdentity {
    Objects.requireNonNull(sourceConnectOperationId, "sourceConnectOperationId");
    if (accountId <= 0 || tenantId == null || tenantId.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(
          "Bare LOGIN account ID and non-nil canonical tenant UUID are required");
    }
    connectScopeId = requireText(connectScopeId, "connectScopeId");
    requestId = requireText(requestId, "requestId");
    if (connectScopeId.getBytes(StandardCharsets.UTF_8).length > MAX_CONNECT_SCOPE_ID_UTF8_BYTES) {
      throw new IllegalArgumentException("Bare LOGIN scope ID exceeds its storage bound");
    }
    if (requestId.length() > MAX_REQUEST_ID_LENGTH) {
      throw new IllegalArgumentException("Bare LOGIN request ID exceeds its storage bound");
    }
  }

  String connectScopeHash() {
    return net.firedevops.firemud.accountservice.dto.AccountJoinDigest.tokenHash(connectScopeId);
  }

  @Override
  public String toString() {
    return "AccountBareLoginExchangeIdentity{sourceConnectOperationId="
        + sourceConnectOperationId
        + ", accountId="
        + accountId
        + ", tenantId="
        + tenantId
        + ", connectScopeId=<redacted>, requestId='"
        + requestId
        + "'}";
  }

  private static String requireText(String value, String fieldName) {
    Objects.requireNonNull(value, fieldName);
    if (value.isBlank() || value.indexOf('\0') >= 0 || hasUnpairedSurrogate(value)) {
      throw new IllegalArgumentException("Invalid " + fieldName);
    }
    return value;
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
