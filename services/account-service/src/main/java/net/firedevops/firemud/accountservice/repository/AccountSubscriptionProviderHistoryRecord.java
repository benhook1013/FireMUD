package net.firedevops.firemud.accountservice.repository;

import java.time.Instant;
import java.util.UUID;

/** Immutable typed creation mapping, not current billing authority. Transfer writers are absent. */
public record AccountSubscriptionProviderHistoryRecord(
    String providerSubscriptionId,
    UUID subscriptionId,
    String operationKind,
    UUID requestId,
    UUID providerOperationId,
    String role,
    Instant effectiveAt,
    String evidenceDigest,
    Instant recordedAt) {

  /**
   * Separate immutable provider absence/cancellation evidence; not inferred from mapping or time.
   */
  public record TerminalResolution(
      UUID resolutionId,
      UUID requestId,
      UUID subscriptionId,
      UUID cleanupProviderIdentity,
      String providerSubscriptionId,
      String outcome,
      Instant effectiveAt,
      String evidenceDigest,
      Instant recordedAt) {}
}
