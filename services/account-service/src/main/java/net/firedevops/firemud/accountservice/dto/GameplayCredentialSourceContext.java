package net.firedevops.firemud.accountservice.dto;

import java.util.UUID;

/** Typed, Game Session-attested source context bound to one pre-auth socket attempt. */
public record GameplayCredentialSourceContext(
    UUID contextId, String canonicalClientAddress, String transportClass) {
  public GameplayCredentialSourceContext {
    if (contextId == null || contextId.version() != 4 || contextId.variant() != 2) {
      throw new IllegalArgumentException("A high-entropy pre-auth context ID is required");
    }
    requireBoundedText(canonicalClientAddress, 256, "Canonical client address");
    requireBoundedText(transportClass, 64, "Transport class");
  }

  private static void requireBoundedText(String value, int maxLength, String field) {
    if (value == null
        || value.isBlank()
        || value.length() > maxLength
        || !value.equals(value.trim())
        || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(field + " is malformed");
    }
  }

  @Override
  public String toString() {
    return "GameplayCredentialSourceContext[redacted]";
  }
}
