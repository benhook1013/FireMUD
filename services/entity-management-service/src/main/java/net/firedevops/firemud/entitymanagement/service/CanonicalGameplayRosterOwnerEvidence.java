package net.firedevops.firemud.entitymanagement.service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Live owner-resolved target evidence; it is not actor admission or runtime authorization. */
public record CanonicalGameplayRosterOwnerEvidence(
    UUID requestUuid,
    UUID canonicalAccountUuid,
    CanonicalGameplayRosterTarget target,
    Instant resolvedAt) {
  public CanonicalGameplayRosterOwnerEvidence {
    Objects.requireNonNull(requestUuid, "requestUuid");
    Objects.requireNonNull(canonicalAccountUuid, "canonicalAccountUuid");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(resolvedAt, "resolvedAt");
  }
}
