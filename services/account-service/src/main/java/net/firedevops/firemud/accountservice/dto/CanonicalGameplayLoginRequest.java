package net.firedevops.firemud.accountservice.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Transient canonical LOGIN input. The credential is never persisted or rendered in diagnostics.
 */
public record CanonicalGameplayLoginRequest(
    UUID requestId,
    String email,
    String credential,
    GameplayCredentialSourceContext sourceContext) {
  public CanonicalGameplayLoginRequest {
    if (requestId == null || requestId.version() != 4 || requestId.variant() != 2) {
      throw new IllegalArgumentException("A high-entropy gameplay LOGIN request ID is required");
    }
    Objects.requireNonNull(email, "Email is required");
    Objects.requireNonNull(credential, "Credential is required");
    if (email.length() > 320 || credential.length() > 1024) {
      throw new IllegalArgumentException("Gameplay LOGIN input exceeds its finite bound");
    }
    Objects.requireNonNull(sourceContext, "Typed pre-auth source context is required");
  }

  @Override
  public String toString() {
    return "CanonicalGameplayLoginRequest[redacted]";
  }
}
