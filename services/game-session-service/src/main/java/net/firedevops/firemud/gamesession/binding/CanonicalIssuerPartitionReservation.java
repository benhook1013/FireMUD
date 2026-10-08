package net.firedevops.firemud.gamesession.binding;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

/** Complete typed durable reservation evidence, never an aggregate capacity acknowledgement. */
public record CanonicalIssuerPartitionReservation(
    UUID reservationId,
    CanonicalGameplayBindingIdentity owner,
    BigInteger bindingGeneration,
    UUID transitionId,
    BigInteger partitionId,
    Lifecycle lifecycle,
    UUID reservationFence,
    UUID issuerCoverageOperationId,
    BigInteger issuerCoverageOperationFence,
    BigInteger coverageFence,
    BigInteger inventorySnapshotRevision,
    BigInteger inventoryRevision) {
  public CanonicalIssuerPartitionReservation {
    requireNonNil(reservationId, "reservationId");
    Objects.requireNonNull(owner, "owner");
    requirePositive(bindingGeneration, "bindingGeneration");
    requireNonNil(transitionId, "transitionId");
    Objects.requireNonNull(lifecycle, "lifecycle");
    requireNonNil(reservationFence, "reservationFence");
    requireNonNil(issuerCoverageOperationId, "issuerCoverageOperationId");
    requirePositive(issuerCoverageOperationFence, "issuerCoverageOperationFence");
    requirePositive(coverageFence, "coverageFence");
    requirePositive(inventorySnapshotRevision, "inventorySnapshotRevision");
    requirePositive(inventoryRevision, "inventoryRevision");
    Objects.requireNonNull(partitionId, "partitionId");
    if (partitionId.signum() < 0 || partitionId.compareTo(owner.issuerIndexPartitionCount()) >= 0) {
      throw new IllegalArgumentException("partitionId must be within the pinned issuer layout");
    }
    if (!partitionId.equals(owner.bindingRef().partitionId(owner.issuerIndexPartitionCount()))) {
      throw new IllegalArgumentException("partitionId does not match the canonical bindingRef");
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

  public enum Lifecycle {
    RESERVED,
    BOUND,
    RELEASE_PENDING,
    RELEASED
  }
}
