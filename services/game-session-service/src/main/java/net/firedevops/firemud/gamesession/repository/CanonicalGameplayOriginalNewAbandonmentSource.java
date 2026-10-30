package net.firedevops.firemud.gamesession.repository;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;

/** Exact retained original NEW selected irreversibly for negative settlement, never admission. */
public final class CanonicalGameplayOriginalNewAbandonmentSource {
  public enum State {
    ABANDONMENT_SELECTED,
    NEVER_ADMITTED_CLOSED
  }

  private final CanonicalGameplayBindingTransitionSnapshot transition;
  private final CanonicalGameplayOriginalNewCandidate candidate;
  private final CanonicalGameplayBindingInventoryEntry inventory;
  private final CanonicalIssuerPartitionReservation reservation;
  private final UUID abandonmentId;
  private final String closureDigest;
  private final State state;
  private final UUID orphanCleanupId;
  private final UUID negativeResolutionId;

  CanonicalGameplayOriginalNewAbandonmentSource(
      CanonicalGameplayBindingTransitionSnapshot transition,
      CanonicalGameplayOriginalNewCandidate candidate,
      CanonicalGameplayBindingInventoryEntry inventory,
      CanonicalIssuerPartitionReservation reservation,
      UUID abandonmentId,
      String closureDigest,
      State state,
      UUID orphanCleanupId,
      UUID negativeResolutionId) {
    this.transition = Objects.requireNonNull(transition);
    this.candidate = Objects.requireNonNull(candidate);
    this.inventory = Objects.requireNonNull(inventory);
    this.reservation = Objects.requireNonNull(reservation);
    this.abandonmentId = Objects.requireNonNull(abandonmentId);
    this.closureDigest = closureDigest;
    this.state = Objects.requireNonNull(state);
    this.orphanCleanupId = orphanCleanupId;
    this.negativeResolutionId = negativeResolutionId;
    if (abandonmentId.equals(new UUID(0L, 0L))
        || !transition.transitionId().equals(candidate.transition().transitionId())
        || !transition.bindingRef().equals(inventory.bindingRef())
        || !transition.bindingGeneration().equals(inventory.bindingGeneration())
        || !candidate.bindingIdentity().equals(inventory.identity())
        || !reservation.reservationId().equals(candidate.reservation().reservationId())
        || !reservation.reservationFence().equals(candidate.reservation().reservationFence())
        || !transition.inventoryRevision().equals(inventory.inventoryRevision())
        || !transition.inventoryRevision().equals(reservation.inventoryRevision())
        || (state == State.ABANDONMENT_SELECTED && closureDigest != null)
        || (state == State.NEVER_ADMITTED_CLOSED
            && (closureDigest == null || !closureDigest.matches("sha256:[0-9a-f]{64}")))
        || ((orphanCleanupId == null) != (negativeResolutionId == null))) {
      throw new IllegalArgumentException("Original NEW abandonment source is inconsistent");
    }
  }

  public CanonicalGameplayBindingTransitionSnapshot transition() {
    return transition;
  }

  public CanonicalGameplayOriginalNewCandidate candidate() {
    return candidate;
  }

  public CanonicalGameplayBindingInventoryEntry inventory() {
    return inventory;
  }

  public CanonicalIssuerPartitionReservation reservation() {
    return reservation;
  }

  public UUID abandonmentId() {
    return abandonmentId;
  }

  public String closureDigest() {
    return closureDigest;
  }

  public State state() {
    return state;
  }

  public UUID orphanCleanupId() {
    return orphanCleanupId;
  }

  public UUID negativeResolutionId() {
    return negativeResolutionId;
  }

  public String candidateDigest() {
    return CanonicalGameplayIssuerMemberV2Codec.sha256(candidate.canonicalBytes());
  }

  public boolean cleanupComplete() {
    return transition.status() == CanonicalGameplayBindingTransitionSnapshot.Status.ABORTED
        && state == State.NEVER_ADMITTED_CLOSED
        && orphanCleanupId != null
        && negativeResolutionId != null
        && inventory.lifecycle() == CanonicalGameplayBindingInventoryEntry.Lifecycle.TERMINAL
        && reservation.lifecycle() == CanonicalIssuerPartitionReservation.Lifecycle.RELEASED;
  }

  @Override
  public String toString() {
    return "CanonicalGameplayOriginalNewAbandonmentSource[redacted]";
  }
}
