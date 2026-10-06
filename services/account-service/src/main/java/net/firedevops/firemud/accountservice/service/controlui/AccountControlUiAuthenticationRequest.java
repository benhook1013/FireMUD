package net.firedevops.firemud.accountservice.service.controlui;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.service.CredentialAttemptSource;

/** A fresh credential attempt for one existing targetless control-UI issuance operation. */
public record AccountControlUiAuthenticationRequest(
    UUID requestId,
    String accountIdentifier,
    String credential,
    Purpose purpose,
    CredentialAttemptSource credentialAttemptSource) {

  public AccountControlUiAuthenticationRequest {
    Objects.requireNonNull(requestId, "requestId");
    if (requestId.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("requestId must be non-nil");
    }
    Objects.requireNonNull(purpose, "purpose");
    if (accountIdentifier == null
        || accountIdentifier.isBlank()
        || accountIdentifier.length() > 320) {
      throw new IllegalArgumentException("Account identifier is required and bounded");
    }
    if (credential == null || credential.isEmpty() || credential.length() > 100) {
      throw new IllegalArgumentException("Credential is required and bounded");
    }
  }

  public enum Purpose {
    INITIAL_ISSUANCE,
    EXACT_RESPONSE_RECOVERY
  }

  @Override
  public String toString() {
    return "AccountControlUiAuthenticationRequest[requestId="
        + requestId
        + ", purpose="
        + purpose
        + ", credential=<redacted>, identifier=<redacted>]";
  }
}
