package net.firedevops.firemud.gamesession.binding;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRuntimeActorReference.ActorOrigin;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRuntimeActorReference.CreationOrigin;

/**
 * Durable region bridge obligation projection; a private repository receipt carries ACK authority.
 */
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
    BigInteger inventoryRevision,
    UUID bridgeOperationId,
    String bridgePreimageState,
    Long runtimeEntityId,
    Long entityProjectionGeneration,
    Long entityProjectionFence,
    String entityWorkloadNamespace,
    ActorOrigin actorOrigin,
    String entityReferenceDigest,
    String worldSourceDigest,
    UUID canonicalRegionId,
    Long worldLifecycleEpoch,
    Long worldLifecycleRowVersion,
    UUID worldCaptureId,
    String worldGraphSha256,
    String worldPreparationInputDigest,
    BigInteger executorFence,
    String leaseTokenCorrelation,
    String redisReadbackDigest,
    java.time.Instant acknowledgedAt) {

  /** Retains the V29 required-only constructor used by non-ACK inventory projections. */
  public CanonicalGameplayBindingRegionBridgeObligation(
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
    this(
        transitionId,
        bindingRef,
        bindingGeneration,
        accountId,
        tenantId,
        gameInstanceId,
        runtimeGameInstanceId,
        sessionId,
        regionId,
        regionEpoch,
        status,
        inventoryRevision,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

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
    boolean prepared = bridgeOperationId != null;
    if (prepared != (bridgePreimageState != null)
        || prepared != (runtimeEntityId != null)
        || prepared != (entityProjectionGeneration != null)
        || prepared != (entityProjectionFence != null)
        || prepared != (entityWorkloadNamespace != null)
        || prepared != (actorOrigin != null)
        || prepared != (entityReferenceDigest != null)
        || prepared != (worldSourceDigest != null)
        || prepared != (canonicalRegionId != null)
        || prepared != (worldLifecycleEpoch != null)
        || prepared != (worldLifecycleRowVersion != null)
        || prepared != (worldCaptureId != null)
        || prepared != (worldGraphSha256 != null)
        || prepared != (worldPreparationInputDigest != null)) {
      throw new IllegalArgumentException("Region bridge operation provenance is incomplete");
    }
    if (prepared) {
      if (!"ABSENT".equals(bridgePreimageState)) {
        throw new IllegalArgumentException("First-NEW bridge preimage must be ABSENT");
      }
      requirePositive(runtimeEntityId, "runtimeEntityId");
      requirePositive(entityProjectionGeneration, "entityProjectionGeneration");
      requirePositive(entityProjectionFence, "entityProjectionFence");
      requireNonNil(canonicalRegionId, "canonicalRegionId");
      requireNonNil(worldCaptureId, "worldCaptureId");
      requireDigest(entityReferenceDigest, "entityReferenceDigest");
      requireDigest(worldSourceDigest, "worldSourceDigest");
      requireDigest(worldGraphSha256, "worldGraphSha256");
      requirePrefixedDigest(worldPreparationInputDigest, "worldPreparationInputDigest");
      if (actorOrigin instanceof CreationOrigin created
          && !entityWorkloadNamespace.equals(created.original().getTargetNamespace())) {
        throw new IllegalArgumentException("Created actor origin changed its Entity namespace");
      }
      if (entityWorkloadNamespace.isBlank()
          || !entityWorkloadNamespace.equals(entityWorkloadNamespace.trim())
          || worldLifecycleEpoch <= 0L
          || worldLifecycleRowVersion < 0L) {
        throw new IllegalArgumentException("Region bridge source provenance is invalid");
      }
    }
    boolean acknowledged = status == Status.ACKNOWLEDGED;
    if ((executorFence != null) != (leaseTokenCorrelation != null)
        || (acknowledged != (redisReadbackDigest != null))
        || (acknowledged != (acknowledgedAt != null))
        || (acknowledged && (!prepared || executorFence == null))) {
      throw new IllegalArgumentException("Region bridge acknowledgement provenance is incomplete");
    }
    if (executorFence != null) {
      requirePositive(executorFence, "executorFence");
      requireDigest(leaseTokenCorrelation, "leaseTokenCorrelation");
    }
    if (acknowledged) {
      requireDigest(redisReadbackDigest, "redisReadbackDigest");
    }
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

  private static void requirePositive(Long value, String name) {
    if (value == null || value <= 0L) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requireDigest(String value, String name) {
    Objects.requireNonNull(value, name);
    if (!value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be lowercase SHA-256 hex");
    }
  }

  private static void requirePrefixedDigest(String value, String name) {
    Objects.requireNonNull(value, name);
    if (!value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be prefixed lowercase SHA-256 hex");
    }
  }

  public enum Status {
    REQUIRED,
    ACKNOWLEDGED
  }
}
