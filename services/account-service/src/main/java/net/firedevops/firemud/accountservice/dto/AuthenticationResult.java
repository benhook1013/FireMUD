package net.firedevops.firemud.accountservice.dto;

import net.firedevops.firemud.accountservice.AccountUuidText;

/** Result of a successful authentication attempt. */
public record AuthenticationResult(String accountId, String authToken) {
  public AuthenticationResult {
    if (AccountUuidText.parseOrNull(accountId) == null) {
      throw new IllegalArgumentException("accountId must be a canonical non-nil UUID");
    }
  }
}
