package net.firedevops.firemud.gamesession.entity;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogEntry.NamespaceResolution;
import tools.jackson.databind.ObjectMapper;

/** Exact immutable policy-set and identity snapshot materialized by Game Session. */
public record PublishedRealmCatalogSnapshot(
    long tenantId,
    String targetNamespace,
    UUID canonicalTenantId,
    long sourceGameRowId,
    String sourceGameTenantKey,
    String tenantIdentityProvenanceKind,
    long catalogRevision,
    PublishedRealmEntryPolicySetEvidence policySetEvidence,
    List<PublishedRealmCatalogEntry> entries,
    Instant createdAt) {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  public PublishedRealmCatalogSnapshot {
    if (tenantId <= 0 || sourceGameRowId <= 0 || catalogRevision <= 0) {
      throw new IllegalArgumentException("Published realm catalog identity must be positive");
    }
    if (targetNamespace == null || targetNamespace.isBlank()) {
      throw new IllegalArgumentException("Published realm catalog namespace is required");
    }
    Objects.requireNonNull(canonicalTenantId, "canonicalTenantId");
    if (new UUID(0L, 0L).equals(canonicalTenantId)) {
      throw new IllegalArgumentException("canonicalTenantId must not be nil");
    }
    if (sourceGameTenantKey == null || sourceGameTenantKey.isBlank()) {
      throw new IllegalArgumentException("sourceGameTenantKey is required");
    }
    if (!"NEW_GAME_ROW".equals(tenantIdentityProvenanceKind)
        && !"RETAINED_GAME_V29".equals(tenantIdentityProvenanceKind)) {
      throw new IllegalArgumentException("tenantIdentityProvenanceKind is unsupported");
    }
    Objects.requireNonNull(policySetEvidence, "policySetEvidence");
    entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    Objects.requireNonNull(createdAt, "createdAt");
    if (!canonicalTenantId.equals(policySetEvidence.canonicalTenantId())
        || policySetEvidence.policies().size() != entries.size()
        || policySetEvidence.versionId() <= 0
        || policySetEvidence.versionNumber() <= 0
        || !policySetEvidence.hasValidDigest(OBJECT_MAPPER)) {
      throw new IllegalArgumentException("Catalog snapshot does not match complete owner evidence");
    }
    Set<UUID> realmIds = new HashSet<>();
    for (int index = 0; index < entries.size(); index++) {
      PublishedRealmCatalogEntry entry = entries.get(index);
      if (entry.tenantId() != tenantId
          || entry.catalogRevision() != catalogRevision
          || !entry.policyEvidence().equals(policySetEvidence.policies().get(index))
          || !entry
              .policyEvidence()
              .tenantIdentityProvenanceKind()
              .equals(tenantIdentityProvenanceKind)
          || entry.policyEvidence().sourceGameRowId() != sourceGameRowId
          || !entry.policyEvidence().sourceGameTenantKey().equals(sourceGameTenantKey)
          || !realmIds.add(entry.realmId())) {
        throw new IllegalArgumentException(
            "Catalog snapshot entries are incomplete or contradictory");
      }
    }
  }

  /** Diagnostic guard requiring lifecycle proof for every entry in this snapshot. */
  public PublishedRealmCatalogSnapshot requireNamespaceResolved() {
    if (entries.stream()
        .anyMatch(entry -> entry.namespaceResolution() != NamespaceResolution.RESOLVED)) {
      throw new IllegalStateException(
          "PUBLISHED_REALM_CATALOG_LIFECYCLE_UNRESOLVED: complete namespace authority is unavailable");
    }
    return this;
  }

  /** Selects only a visible realm and refuses to authorize it without its exact namespace. */
  public PublishedRealmCatalogEntry requireVisibleEntryForAdmission(
      String worldSlug, String realmSlug) {
    if (worldSlug == null || worldSlug.isBlank() || realmSlug == null || realmSlug.isBlank()) {
      throw new IllegalArgumentException("Exact world and realm selectors are required");
    }
    PublishedRealmCatalogEntry selected =
        entries.stream()
            .filter(
                entry ->
                    worldSlug.equals(entry.policyEvidence().policy().worldSlug())
                        && realmSlug.equals(entry.policyEvidence().policy().realmSlug()))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "PUBLISHED_REALM_CATALOG_SELECTOR_NOT_FOUND: exact realm selector is absent"));
    if (!selected.policyEvidence().policy().visible()) {
      throw new IllegalStateException(
          "PUBLISHED_REALM_CATALOG_ENTRY_HIDDEN: selected realm is not visible for admission");
    }
    selected.requirePlayableStateNamespaceId();
    return selected;
  }
}
