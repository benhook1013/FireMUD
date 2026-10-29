package net.firedevops.firemud.accountservice.dto;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

/** Append-only linkage of an unknown-decision abort to its verified Game Session transition. */
public record AccountGameplayAdmissionAbortResolution(
    UUID resolutionId,
    UUID originalLeaseId,
    BigInteger originalLeaseFence,
    String originalLeaseSha256,
    UUID orphanCleanupId,
    UUID bindingDecisionId) {
  public AccountGameplayAdmissionAbortResolution {
    requireUuid(resolutionId);
    requireUuid(originalLeaseId);
    requireUuid(orphanCleanupId);
    requireUuid(bindingDecisionId);
    if (Objects.requireNonNull(originalLeaseFence).signum() <= 0
        || originalLeaseSha256 == null
        || !originalLeaseSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Exact original abort lease evidence required");
    }
  }

  private static void requireUuid(UUID value) {
    if (value == null || value.version() != 4 || value.variant() != 2) {
      throw new IllegalArgumentException("Canonical UUIDv4 resolution identity required");
    }
  }

  @Override
  public String toString() {
    return "AccountGameplayAdmissionAbortResolution[redacted]";
  }
}
