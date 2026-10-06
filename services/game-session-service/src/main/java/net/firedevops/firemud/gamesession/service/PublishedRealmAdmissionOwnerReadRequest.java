package net.firedevops.firemud.gamesession.service;

import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Exact target and caller-observed revisions for a read-only published realm admission proof. */
public record PublishedRealmAdmissionOwnerReadRequest(
    String targetNamespace,
    UUID canonicalTenantId,
    long gameSessionTenantId,
    String worldSlug,
    String realmSlug,
    long expectedCatalogRevision,
    long expectedPointerVersion) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  public PublishedRealmAdmissionOwnerReadRequest {
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)
        || canonicalTenantId == null
        || NIL_UUID.equals(canonicalTenantId)
        || gameSessionTenantId <= 0
        || expectedCatalogRevision <= 0
        || expectedPointerVersion <= 0) {
      throw new IllegalArgumentException("Published realm admission target is incomplete");
    }
    requireSlug(worldSlug, "worldSlug");
    requireSlug(realmSlug, "realmSlug");
  }

  private static void requireSlug(String value, String name) {
    if (value == null || value.length() > 64 || !value.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
      throw new IllegalArgumentException(name + " must be a canonical published realm selector");
    }
  }
}
