package net.firedevops.firemud.gamesession.dto;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;

/** Immutable catalog evidence for one public-production realm; it is not admission authority. */
public record CanonicalRealmCatalogSnapshot(
    String targetNamespace,
    UUID tenantId,
    String tenantSlug,
    String worldSlug,
    UUID realmId,
    String realmSlug,
    String realmDisplayName,
    boolean visible,
    boolean publicProduction,
    String stateScope,
    UUID playableStateNamespaceId,
    String characterCreationPolicy,
    long catalogRevision,
    UUID creationRequestId,
    String requestDigest,
    String receiptDigest,
    IntakeReceipt sourceIntakeReceipt) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public CanonicalRealmCatalogSnapshot {
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    requireNonNil(tenantId, "tenantId");
    Objects.requireNonNull(tenantSlug, "tenantSlug");
    Objects.requireNonNull(worldSlug, "worldSlug");
    requireNonNil(realmId, "realmId");
    Objects.requireNonNull(realmSlug, "realmSlug");
    Objects.requireNonNull(realmDisplayName, "realmDisplayName");
    Objects.requireNonNull(stateScope, "stateScope");
    requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
    Objects.requireNonNull(characterCreationPolicy, "characterCreationPolicy");
    requireNonNil(creationRequestId, "creationRequestId");
    Objects.requireNonNull(requestDigest, "requestDigest");
    Objects.requireNonNull(receiptDigest, "receiptDigest");
    Objects.requireNonNull(sourceIntakeReceipt, "sourceIntakeReceipt");
    if (!visible || !publicProduction || catalogRevision != 1L) {
      throw new IllegalArgumentException("Unsupported canonical initial realm catalog state");
    }
    var source = sourceIntakeReceipt.source();
    if (!targetNamespace.equals(source.targetNamespace())
        || !tenantId.equals(source.canonicalTenantId())
        || !tenantSlug.equals(source.tenantSlug())
        || !worldSlug.equals(source.worldSlug())) {
      throw new IllegalArgumentException(
          "Catalog selectors do not match the persisted authored-world source receipt");
    }
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
    }
  }
}
