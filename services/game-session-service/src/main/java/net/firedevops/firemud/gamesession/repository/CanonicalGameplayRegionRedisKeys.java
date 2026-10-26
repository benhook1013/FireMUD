package net.firedevops.firemud.gamesession.repository;

import java.util.Objects;
import java.util.UUID;

/** Exact region-scoped coordination keys for the manually composed region owner. */
final class CanonicalGameplayRegionRedisKeys {
  private CanonicalGameplayRegionRedisKeys() {}

  static String tenantRegionTag(UUID tenantId, UUID gameInstanceId, UUID regionId) {
    requireCanonicalId(tenantId, "tenantId");
    requireCanonicalId(gameInstanceId, "gameInstanceId");
    requireCanonicalId(regionId, "regionId");
    return "r1-"
        + tenantId.toString().replace("-", "")
        + "-"
        + gameInstanceId.toString().replace("-", "")
        + "-"
        + regionId.toString().replace("-", "");
  }

  static String lease(UUID tenantId, UUID gameInstanceId, UUID regionId) {
    return "tick-executor-lease:{" + tenantRegionTag(tenantId, gameInstanceId, regionId) + "}";
  }

  static String meta(UUID tenantId, UUID gameInstanceId, UUID regionId) {
    return "tick:{" + tenantRegionTag(tenantId, gameInstanceId, regionId) + "}:meta";
  }

  static String sessionBinding(
      UUID tenantId, UUID gameInstanceId, UUID regionId, long runtimeEntityId) {
    if (runtimeEntityId <= 0L)
      throw new IllegalArgumentException("runtimeEntityId must be positive");
    return "tick:{"
        + tenantRegionTag(tenantId, gameInstanceId, regionId)
        + "}:session-binding:"
        + runtimeEntityId;
  }

  private static void requireCanonicalId(UUID id, String name) {
    Objects.requireNonNull(id, name);
    if (id.equals(new UUID(0L, 0L)) || !UUID.fromString(id.toString()).equals(id)) {
      throw new IllegalArgumentException(name + " must be a canonical non-nil UUID");
    }
  }
}
