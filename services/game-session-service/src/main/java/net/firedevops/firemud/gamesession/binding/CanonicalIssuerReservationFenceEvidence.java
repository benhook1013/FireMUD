package net.firedevops.firemud.gamesession.binding;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

/** Caller-owned reservation and coverage fences; this is intent, not acknowledgement evidence. */
public record CanonicalIssuerReservationFenceEvidence(
    UUID reservationId,
    UUID reservationFence,
    UUID issuerCoverageOperationId,
    BigInteger issuerCoverageOperationFence,
    BigInteger coverageFence,
    BigInteger inventorySnapshotRevision) {
  public CanonicalIssuerReservationFenceEvidence {
    requireNonNil(reservationId, "reservationId");
    requireNonNil(reservationFence, "reservationFence");
    requireNonNil(issuerCoverageOperationId, "issuerCoverageOperationId");
    requirePositive(issuerCoverageOperationFence, "issuerCoverageOperationFence");
    requirePositive(coverageFence, "coverageFence");
    requirePositive(inventorySnapshotRevision, "inventorySnapshotRevision");
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
}
