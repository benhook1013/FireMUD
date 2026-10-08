package net.firedevops.firemud.gamesession.binding;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

/** Exact intended region-lease bridge tuple; it contains no acknowledgement or success claim. */
public record CanonicalGameplayBindingRegionBridgeObligation(
    UUID transitionId,
    CanonicalGameplayBindingRef bindingRef,
    BigInteger bindingGeneration,
    UUID accountId,
    UUID tenantId,
    UUID gameInstanceId,
    long runtimeGameInstanceId,
    String sessionId,
    UUID regionId,
    BigInteger regionEpoch,
    Status status,
    BigInteger inventoryRevision) {
  public CanonicalGameplayBindingRegionBridgeObligation {
    requireNonNil(transitionId, "transitionId");
    Objects.requireNonNull(bindingRef, "bindingRef");
    requirePositive(bindingGeneration, "bindingGeneration");
    requireNonNil(accountId, "accountId");
    requireNonNil(tenantId, "tenantId");
    requireNonNil(gameInstanceId, "gameInstanceId");
    if (runtimeGameInstanceId <= 0L) {
      throw new IllegalArgumentException("runtimeGameInstanceId must be positive");
    }
    Objects.requireNonNull(sessionId, "sessionId");
    requireNonNil(regionId, "regionId");
    requirePositive(regionEpoch, "regionEpoch");
    Objects.requireNonNull(status, "status");
    requirePositive(inventoryRevision, "inventoryRevision");
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(name + " must not be nil");
    }
  }

  private static void requirePositive(BigInteger value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  public enum Status {
    REQUIRED
  }
}
