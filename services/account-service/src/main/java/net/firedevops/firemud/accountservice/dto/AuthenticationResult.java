package net.firedevops.firemud.accountservice.dto;

import java.util.UUID;

/** Result of a successful authentication attempt. */
public record AuthenticationResult(String accountId, String authToken) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public AuthenticationResult {
    if (accountId == null) {
      throw new IllegalArgumentException("accountId must be a canonical non-nil UUID");
    }
    UUID parsedAccountId;
    try {
      parsedAccountId = UUID.fromString(accountId);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("accountId must be a canonical non-nil UUID", exception);
    }
    if (parsedAccountId.equals(NIL_UUID) || !parsedAccountId.toString().equals(accountId)) {
      throw new IllegalArgumentException("accountId must be a canonical non-nil UUID");
    }
  }
}
