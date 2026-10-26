package net.firedevops.firemud.gamesession.entity;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;

/** Durable owner row for one exact canonical tenant, game-instance, and operational region. */
public record CanonicalGameplayRegionRuntimeStatus(
    UUID tenantId,
    UUID gameInstanceId,
    UUID canonicalRegionId,
    UUID regionId,
    String tenantRegionTag,
    long lifecycleEpoch,
    long lifecycleRowVersion,
    WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest,
    UUID captureId,
    String graphSha256,
    String preparationInputDigest,
    long regionEpoch,
    BigInteger executorFence,
    String leaseTokenCorrelation,
    String ownerService,
    String ownerInstanceId,
    boolean paused,
    String lastCommittedTickBatchId,
    long lastCommittedTickId,
    Instant updatedAt) {
  public CanonicalGameplayRegionRuntimeStatus {
    requireId(tenantId, "tenantId");
    requireId(gameInstanceId, "gameInstanceId");
    requireId(canonicalRegionId, "canonicalRegionId");
    requireId(regionId, "regionId");
    Objects.requireNonNull(tenantRegionTag, "tenantRegionTag");
    if (!tenantRegionTag.equals(
        "r1-"
            + tenantId.toString().replace("-", "")
            + "-"
            + gameInstanceId.toString().replace("-", "")
            + "-"
            + regionId.toString().replace("-", ""))) {
      throw new IllegalArgumentException("tenantRegionTag must derive from the exact owner scope");
    }
    requireId(captureId, "captureId");
    Objects.requireNonNull(lifecycleRequest, "lifecycleRequest");
    if (!tenantId.equals(lifecycleRequest.canonicalTenantId())
        || !gameInstanceId.equals(lifecycleRequest.canonicalGameInstanceId())) {
      throw new IllegalArgumentException("lifecycleRequest belongs to another runtime target");
    }
    requireDigest(graphSha256, "graphSha256");
    requirePrefixedDigest(preparationInputDigest, "preparationInputDigest");
    requirePositive(regionEpoch, "regionEpoch");
    Objects.requireNonNull(executorFence, "executorFence");
    if (executorFence.signum() <= 0) {
      throw new IllegalArgumentException("executorFence must be positive");
    }
    requireDigest(leaseTokenCorrelation, "leaseTokenCorrelation");
    requireText(ownerService, "ownerService");
    requireText(ownerInstanceId, "ownerInstanceId");
    if (lifecycleEpoch <= 0L || lifecycleRowVersion < 0L) {
      throw new IllegalArgumentException("World lifecycle evidence is invalid");
    }
    if (lastCommittedTickId < -1L) {
      throw new IllegalArgumentException("lastCommittedTickId cannot precede the initial tick");
    }
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  private static void requireId(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
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

  private static void requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank() || !value.equals(value.trim())) {
      throw new IllegalArgumentException(name + " must be nonblank canonical text");
    }
  }

  private static void requirePositive(long value, String name) {
    if (value <= 0L) throw new IllegalArgumentException(name + " must be positive");
  }
}
