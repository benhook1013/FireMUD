package net.firedevops.firemud.gamesession.binding;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;

/** Exact existing first-NEW transition and its original lease, before or after Account abort. */
public record CanonicalGameplayAdmissionAbortSource(
    CanonicalGameplayBindingTransitionSnapshot transition,
    CanonicalGameplayBindingInventoryEntry candidate,
    CanonicalIssuerPartitionReservation reservation,
    CanonicalGameplayAdmissionDecision originalDecision,
    UUID orphanCleanupId) {
  public CanonicalGameplayAdmissionAbortSource {
    Objects.requireNonNull(transition, "transition");
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(reservation, "reservation");
    Objects.requireNonNull(originalDecision, "originalDecision");
    if (transition.expectedPriorBindingRef() != null
        || transition.expectedPriorBindingGeneration() != null
        || originalDecision.leaseKind() != AccountGameplayAdmissionLeaseEvidence.LeaseKind.NEW_BINDING
        || originalDecision.expectedOldBindingGeneration() != null
        || !transition.transitionId().equals(originalDecision.bindingDecisionId())
        || !transition.transitionId().equals(candidate.transitionId())
        || !transition.transitionId().equals(reservation.transitionId())
        || !transition.bindingRef().equals(candidate.bindingRef())
        || !transition.bindingRef().equals(reservation.owner().bindingRef())
        || !transition.bindingGeneration().equals(candidate.bindingGeneration())
        || !transition.bindingGeneration().equals(reservation.bindingGeneration())
        || !transition.issuerReservationId().equals(reservation.reservationId())
        || !transition.reservationFence().equals(reservation.reservationFence())
        || !transition.candidateAccountIndexFence().equals(candidate.accountIndexFence())
        || !transition.candidate().equals(candidate.identity())
        || !transition.candidate().equals(reservation.owner())
        || !transition.inventoryRevision().equals(candidate.inventoryRevision())
        || !transition.inventoryRevision().equals(reservation.inventoryRevision())) {
      throw new IllegalArgumentException("Abort source does not identify one exact first-NEW owner");
    }

    if (transition.status() == CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL) {
      if (candidate.lifecycle()
              != CanonicalGameplayBindingInventoryEntry.Lifecycle.PROVISIONAL
          || reservation.lifecycle() != CanonicalIssuerPartitionReservation.Lifecycle.RESERVED
          || orphanCleanupId != null) {
        throw new IllegalArgumentException("Provisional abort source has terminal storage state");
      }
    } else if (transition.status() == CanonicalGameplayBindingTransitionSnapshot.Status.ABORTED) {
      requireNonNil(orphanCleanupId, "orphanCleanupId");
      if (candidate.lifecycle()
              != CanonicalGameplayBindingInventoryEntry.Lifecycle.TERMINAL_UNRESOLVED
          || (reservation.lifecycle()
                  != CanonicalIssuerPartitionReservation.Lifecycle.RELEASE_PENDING
              && reservation.lifecycle()
                  != CanonicalIssuerPartitionReservation.Lifecycle.RELEASED)) {
        throw new IllegalArgumentException("Aborted source is not retained for exact cleanup");
      }
    } else {
      throw new IllegalArgumentException("Only a provisional or aborted first-NEW source is valid");
    }

    AccountGameplayAdmissionLeaseEvidence lease = originalDecision.leaseEvidence();
    CanonicalPlayableTarget target = originalDecision.targetEvidence();
    if (!candidate.identity().equals(originalDecision.candidate())
        || !transition.bindingGeneration().equals(originalDecision.bindingGeneration())
        || !target.canonicalTenantId().equals(candidate.identity().tenantId())
        || !target.playableStateNamespaceId().equals(candidate.identity().playableStateNamespaceId())
        || lease.leaseKind() != AccountGameplayAdmissionLeaseEvidence.LeaseKind.NEW_BINDING) {
      throw new IllegalArgumentException("Original decision differs from its exact first-NEW source");
    }
  }

  public boolean isAccountAborted() {
    return transition.status() == CanonicalGameplayBindingTransitionSnapshot.Status.ABORTED;
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(name + " must not be nil");
    }
  }
}
