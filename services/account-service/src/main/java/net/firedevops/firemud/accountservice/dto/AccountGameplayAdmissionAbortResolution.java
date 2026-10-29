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
    UUID bindingDecisionId,
    String purpose,
    UUID transitionId,
    UUID abandonmentId,
    String candidateDigest,
    String closureDigest) {
  public static final String POSITIVE_PURPOSE = "provisional-decision/v1";
  public static final String NEGATIVE_PURPOSE = "original-new-never-admitted-closure/v1";

  public AccountGameplayAdmissionAbortResolution(
      UUID resolutionId,
      UUID originalLeaseId,
      BigInteger originalLeaseFence,
      String originalLeaseSha256,
      UUID orphanCleanupId,
      UUID bindingDecisionId) {
    this(
        resolutionId,
        originalLeaseId,
        originalLeaseFence,
        originalLeaseSha256,
        orphanCleanupId,
        bindingDecisionId,
        POSITIVE_PURPOSE,
        null,
        null,
        null,
        null);
  }

  public AccountGameplayAdmissionAbortResolution {
    requireUuid(resolutionId);
    requireUuid(originalLeaseId);
    requireUuid(orphanCleanupId);
    if (POSITIVE_PURPOSE.equals(purpose)) {
      requireUuid(bindingDecisionId);
      if (transitionId != null
          || abandonmentId != null
          || candidateDigest != null
          || closureDigest != null)
        throw new IllegalArgumentException(
            "Positive resolution cannot carry negative purpose evidence");
    } else if (NEGATIVE_PURPOSE.equals(purpose)) {
      if (bindingDecisionId != null)
        throw new IllegalArgumentException("Negative resolution cannot carry a positive decision");
      requireNonNil(transitionId);
      requireNonNil(abandonmentId);
      if (candidateDigest == null
          || !candidateDigest.matches("[0-9a-f]{64}")
          || closureDigest == null
          || !closureDigest.matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("Exact negative closure digests required");
    } else throw new IllegalArgumentException("Unknown abort resolution purpose");
    if (Objects.requireNonNull(originalLeaseFence).signum() <= 0
        || originalLeaseSha256 == null
        || !originalLeaseSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Exact original abort lease evidence required");
    }
  }

  private static void requireNonNil(UUID value) {
    if (value == null || value.equals(new UUID(0L, 0L)))
      throw new IllegalArgumentException("Canonical nonnil negative resolution identity required");
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
