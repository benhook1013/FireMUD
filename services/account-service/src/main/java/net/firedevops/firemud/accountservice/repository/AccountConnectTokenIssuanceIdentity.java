package net.firedevops.firemud.accountservice.repository;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Exact caller-supplied identity for one durable connect-token issuance attempt. */
public record AccountConnectTokenIssuanceIdentity(
    long accountId, long tenantId, String connectScopeId, String requestId) {

  public static final int MAX_CONNECT_SCOPE_ID_UTF8_BYTES = 2048;
  public static final int MAX_REQUEST_ID_LENGTH = 128;

  public AccountConnectTokenIssuanceIdentity {
    if (accountId <= 0 || tenantId <= 0) {
      throw new IllegalArgumentException("Connect-token account and tenant IDs must be positive");
    }
    connectScopeId = requireText(connectScopeId, "connectScopeId");
    requestId = requireText(requestId, "requestId");
    if (connectScopeId.getBytes(StandardCharsets.UTF_8).length > MAX_CONNECT_SCOPE_ID_UTF8_BYTES) {
      throw new IllegalArgumentException("Connect-token scope ID exceeds its storage bound");
    }
    if (requestId.length() > MAX_REQUEST_ID_LENGTH) {
      throw new IllegalArgumentException("Connect-token request ID exceeds its storage bound");
    }
  }

  String connectScopeHash() {
    return net.firedevops.firemud.accountservice.dto.AccountJoinDigest.tokenHash(connectScopeId);
  }

  @Override
  public String toString() {
    return "AccountConnectTokenIssuanceIdentity{accountId="
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
