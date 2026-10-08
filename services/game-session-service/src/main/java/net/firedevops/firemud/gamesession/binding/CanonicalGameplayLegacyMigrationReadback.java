package net.firedevops.firemud.gamesession.binding;

import java.util.List;
import java.util.Objects;

/** Exact typed session-record and controller-index observations returned by the Redis owner. */
public record CanonicalGameplayLegacyMigrationReadback(
    List<CanonicalGameplayBindingInventoryEntry> sessionRecords,
    List<CanonicalGameplayBindingInventoryEntry> characterIndexRecords,
    List<String> remainingLegacySourceKeyDigests,
    List<String> unexpectedNamespaceKeyDigests) {
  public CanonicalGameplayLegacyMigrationReadback {
    sessionRecords = List.copyOf(Objects.requireNonNull(sessionRecords, "sessionRecords"));
    characterIndexRecords =
        List.copyOf(Objects.requireNonNull(characterIndexRecords, "characterIndexRecords"));
    remainingLegacySourceKeyDigests =
        List.copyOf(
            Objects.requireNonNull(
                remainingLegacySourceKeyDigests, "remainingLegacySourceKeyDigests"));
    unexpectedNamespaceKeyDigests =
        List.copyOf(
            Objects.requireNonNull(unexpectedNamespaceKeyDigests, "unexpectedNamespaceKeyDigests"));
    if (sessionRecords.stream().anyMatch(Objects::isNull)
        || characterIndexRecords.stream().anyMatch(Objects::isNull)
        || remainingLegacySourceKeyDigests.stream().anyMatch(Objects::isNull)
        || unexpectedNamespaceKeyDigests.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("Redis readback must not contain null observations");
    }
  }
}
