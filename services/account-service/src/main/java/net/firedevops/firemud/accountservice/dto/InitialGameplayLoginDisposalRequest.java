package net.firedevops.firemud.accountservice.dto;

import java.util.Objects;
import java.util.UUID;

/** Lifecycle-only original LOGIN identity; no credential or caller-selected Account binding. */
public record InitialGameplayLoginDisposalRequest(
    UUID requestId, UUID callerContextId, TokenWitness tokenWitness) {
  public InitialGameplayLoginDisposalRequest {
    requireUuid(requestId);
    requireUuid(callerContextId);
  }

  public record TokenWitness(
      UUID tokenJti, String tokenSha256, long tokenGeneration, long issuanceFence) {
    public TokenWitness {
      requireUuid(tokenJti);
      if (tokenSha256 == null
          || !tokenSha256.matches("[0-9a-f]{64}")
          || tokenGeneration != 1L
          || issuanceFence <= 0L) {
        throw new IllegalArgumentException("Complete original LOGIN token witness required");
      }
    }

    @Override
    public String toString() {
      return "TokenWitness[redacted]";
    }
  }

  private static void requireUuid(UUID value) {
    Objects.requireNonNull(value, "Original LOGIN UUID required");
    if (value.version() != 4 || value.variant() != 2) {
      throw new IllegalArgumentException("Original LOGIN UUIDv4 required");
    }
  }

  @Override
  public String toString() {
    return "InitialGameplayLoginDisposalRequest[redacted]";
  }
}
