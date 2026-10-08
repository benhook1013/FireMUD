package net.firedevops.firemud.gamesession.binding;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

/** Canonical binding identity and separately retained runtime/issuer routing evidence. */
public record CanonicalGameplayBindingIdentity(
    UUID accountId,
    UUID tenantId,
    UUID playableStateNamespaceId,
    UUID characterId,
    String playableStateScope,
    UUID gameInstanceId,
    long runtimeGameInstanceId,
    String sessionId,
    UUID regionId,
    BigInteger regionEpoch,
    UUID issuerId,
    BigInteger issuerAuthGeneration,
    BigInteger issuerIndexLayoutVersion,
    BigInteger issuerIndexPartitionCount,
    BigInteger issuerIndexPartitionCapacity) {
  public CanonicalGameplayBindingIdentity {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(playableStateNamespaceId, "playableStateNamespaceId");
    Objects.requireNonNull(characterId, "characterId");
    Objects.requireNonNull(playableStateScope, "playableStateScope");
    Objects.requireNonNull(gameInstanceId, "gameInstanceId");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(regionId, "regionId");
    Objects.requireNonNull(regionEpoch, "regionEpoch");
    Objects.requireNonNull(issuerId, "issuerId");
    Objects.requireNonNull(issuerAuthGeneration, "issuerAuthGeneration");
    Objects.requireNonNull(issuerIndexLayoutVersion, "issuerIndexLayoutVersion");
    Objects.requireNonNull(issuerIndexPartitionCount, "issuerIndexPartitionCount");
    Objects.requireNonNull(issuerIndexPartitionCapacity, "issuerIndexPartitionCapacity");
    requireNonNil(accountId, "accountId");
    requireNonNil(tenantId, "tenantId");
    requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
    requireNonNil(characterId, "characterId");
    requireNonNil(gameInstanceId, "gameInstanceId");
    if (runtimeGameInstanceId <= 0L) {
      throw new IllegalArgumentException("runtimeGameInstanceId must be positive");
    }
    requireNonNil(regionId, "regionId");
    requireNonNil(issuerId, "issuerId");
    if (!playableStateScope.equals("SHARED") && !playableStateScope.equals("ISOLATED")) {
      throw new IllegalArgumentException("playableStateScope must be SHARED or ISOLATED");
    }
    requirePositive(regionEpoch, "regionEpoch");
    requirePositive(issuerAuthGeneration, "issuerAuthGeneration");
    requirePositive(issuerIndexLayoutVersion, "issuerIndexLayoutVersion");
    requirePositive(issuerIndexPartitionCount, "issuerIndexPartitionCount");
    requirePositive(issuerIndexPartitionCapacity, "issuerIndexPartitionCapacity");
    // Validate the canonical session identifier now; no normalization is performed.
    try {
      UUID canonicalSessionId = UUID.fromString(sessionId);
      if (!canonicalSessionId.toString().equals(sessionId)) {
        throw new IllegalArgumentException("sessionId must use canonical UUID text");
      }
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException("sessionId must use canonical UUID text", malformed);
    }
    CanonicalGameplayBindingRef.of(tenantId, gameInstanceId, sessionId);
  }

  public CanonicalGameplayBindingRef bindingRef() {
    return CanonicalGameplayBindingRef.of(tenantId, gameInstanceId, sessionId);
  }

  private static void requirePositive(BigInteger value, String name) {
    if (value.signum() <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requireNonNil(UUID value, String name) {
    if (value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(name + " must not be nil");
    }
  }
}
