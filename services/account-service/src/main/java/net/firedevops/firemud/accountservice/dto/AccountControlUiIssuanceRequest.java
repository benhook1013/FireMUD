package net.firedevops.firemud.accountservice.dto;

import java.util.Objects;
import java.util.UUID;

/** Credential-free semantic identity for one targetless control-UI login issuance. */
public record AccountControlUiIssuanceRequest(String requestId, String accountUuid) {

  public static final String PROFILE = "control-ui";
  public static final String AUDIENCE = "control-ui";

  public AccountControlUiIssuanceRequest {
    requestId = canonicalUuid(requestId, "requestId");
    accountUuid = canonicalUuid(accountUuid, "accountUuid");
  }

  private static String canonicalUuid(String value, String field) {
    Objects.requireNonNull(value, field);
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)
          || (parsed.getMostSignificantBits() == 0L && parsed.getLeastSignificantBits() == 0L)) {
        throw new IllegalArgumentException("Invalid " + field);
      }
      return value;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Invalid " + field, exception);
    }
  }
}
