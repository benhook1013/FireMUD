package net.firedevops.firemud.gamesession.entity;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;

/** One immutable Game Design policy within a Game Session-owned catalog snapshot. */
public record PublishedRealmCatalogEntry(
    long tenantId,
    long catalogRevision,
    UUID realmId,
    UUID playableStateNamespaceId,
    NamespaceResolution namespaceResolution,
    PublishedRealmEntryPolicyEvidence policyEvidence) {
  public enum NamespaceResolution {
    RESOLVED,
    AWAITING_LIFECYCLE_PROOF
  }

  public PublishedRealmCatalogEntry {
    if (tenantId <= 0 || catalogRevision <= 0) {
      throw new IllegalArgumentException("Catalog tenant and revision must be positive");
    }
    requireNonNil(realmId, "realmId");
    Objects.requireNonNull(namespaceResolution, "namespaceResolution");
    Objects.requireNonNull(policyEvidence, "policyEvidence");
    if (namespaceResolution == NamespaceResolution.RESOLVED) {
      requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
      if (policyEvidence.policy().stateScope() == RealmEntryPolicy.StateScope.ISOLATED) {
        throw new IllegalArgumentException(
            "An isolated catalog entry cannot resolve without owner-proven lifecycle identity");
      }
    } else if (playableStateNamespaceId != null) {
      throw new IllegalArgumentException(
          "A lifecycle-unresolved catalog entry cannot expose a playable namespace");
    }
  }

  /** Returns the selected entry's namespace only when this entry has owner-resolved scope. */
  public UUID requirePlayableStateNamespaceId() {
    if (namespaceResolution != NamespaceResolution.RESOLVED) {
      throw new IllegalStateException(
          "PUBLISHED_REALM_CATALOG_LIFECYCLE_UNRESOLVED: selected entry has no namespace authority");
    }
    return playableStateNamespaceId;
  }

  private static UUID requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (new UUID(0L, 0L).equals(value)) {
      throw new IllegalArgumentException(label + " must not be nil");
    }
    return value;
  }
}
