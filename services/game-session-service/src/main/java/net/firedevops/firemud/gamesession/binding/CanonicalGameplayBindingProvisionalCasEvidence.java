package net.firedevops.firemud.gamesession.binding;

import java.util.Objects;

/** Exact durable source/candidate tuple allowed to attempt the non-admitting tenant-local CAS. */
public record CanonicalGameplayBindingProvisionalCasEvidence(
    CanonicalGameplayBindingTransitionSnapshot transition,
    CanonicalGameplayBindingInventoryEntry candidate,
    CanonicalIssuerPartitionReservation candidateReservation,
    CanonicalGameplayBindingInventoryEntry expectedPrior,
    CanonicalIssuerPartitionReservation expectedPriorReservation,
    CanonicalGameplayLegacyMigrationDisposition initialBindingMigrationDisposition) {
  public CanonicalGameplayBindingProvisionalCasEvidence {
    Objects.requireNonNull(transition, "transition");
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(candidateReservation, "candidateReservation");
    if ((expectedPrior == null) != (expectedPriorReservation == null)) {
      throw new IllegalArgumentException(
          "expected prior binding and its reservation must be supplied together");
    }
    boolean initialBinding = expectedPrior == null;
    if ((transition.expectedPriorBindingRef() == null) != initialBinding
        || (transition.expectedPriorBindingGeneration() == null) != initialBinding) {
      throw new IllegalArgumentException(
          "nullable prior source must match the exact durable transition tuple");
    }
    if (initialBinding != (initialBindingMigrationDisposition != null)) {
      throw new IllegalArgumentException(
          "first binding requires an exact VERIFIED legacy migration disposition;"
              + " transfers do not");
    }

    if (transition.status() != CanonicalGameplayBindingTransitionSnapshot.Status.PREPARED
        && transition.status() != CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL) {
      throw new IllegalArgumentException("transition is not a retryable provisional operation");
    }
    CanonicalGameplayBindingInventoryEntry.Lifecycle expectedCandidateLifecycle =
        transition.status() == CanonicalGameplayBindingTransitionSnapshot.Status.PREPARED
            ? CanonicalGameplayBindingInventoryEntry.Lifecycle.CANDIDATE_PREPARED
            : CanonicalGameplayBindingInventoryEntry.Lifecycle.PROVISIONAL;
    if (candidate.lifecycle() != expectedCandidateLifecycle
        || candidate.accountIndexState()
            != CanonicalGameplayBindingInventoryEntry.IndexState.REPAIR_REQUIRED
        || candidate.issuerIndexState()
            != CanonicalGameplayBindingInventoryEntry.IndexState.REPAIR_REQUIRED) {
      throw new IllegalArgumentException("candidate is not an exact non-admissible transition row");
    }
    CanonicalGameplayBindingIdentity next = candidate.identity();
    if (!candidate.bindingRef().equals(transition.bindingRef())
        || !candidate.transitionId().equals(transition.transitionId())
        || !candidate.bindingGeneration().equals(transition.bindingGeneration())
        || !candidate.accountIndexFence().equals(transition.candidateAccountIndexFence())
        || !candidateReservation.reservationId().equals(transition.issuerReservationId())
        || !candidateReservation.reservationFence().equals(transition.reservationFence())
        || !candidateReservation.owner().equals(next)
        || !candidateReservation.bindingGeneration().equals(candidate.bindingGeneration())
        || !candidateReservation.transitionId().equals(transition.transitionId())
        || !candidateReservation.inventoryRevision().equals(transition.inventoryRevision())
        || candidateReservation.lifecycle()
            != CanonicalIssuerPartitionReservation.Lifecycle.RESERVED) {
      throw new IllegalArgumentException(
          "candidate and reservation are not the exact transition row");
    }

    if (initialBinding) {
      if (transition.expectedPriorBindingRef() != null
          || transition.expectedPriorBindingGeneration() != null) {
        throw new IllegalArgumentException("initial binding must carry no prior generation");
      }
    } else {
      CanonicalGameplayBindingIdentity prior = expectedPrior.identity();
      if (expectedPrior.lifecycle() != CanonicalGameplayBindingInventoryEntry.Lifecycle.ACTIVE
          || expectedPrior.accountIndexState()
              != CanonicalGameplayBindingInventoryEntry.IndexState.ACKNOWLEDGED
          || expectedPrior.issuerIndexState()
              != CanonicalGameplayBindingInventoryEntry.IndexState.ACKNOWLEDGED
          || !Objects.equals(expectedPrior.bindingRef(), transition.expectedPriorBindingRef())
          || !Objects.equals(
              expectedPrior.bindingGeneration(), transition.expectedPriorBindingGeneration())
          || !next.accountId().equals(prior.accountId())
          || !next.tenantId().equals(prior.tenantId())
          || !next.playableStateNamespaceId().equals(prior.playableStateNamespaceId())
          || !next.characterId().equals(prior.characterId())
          || !expectedPriorReservation.reservationId().equals(expectedPrior.issuerReservationId())
          || !expectedPriorReservation.owner().equals(prior)
          || !expectedPriorReservation.bindingGeneration().equals(expectedPrior.bindingGeneration())
          || !expectedPriorReservation.transitionId().equals(expectedPrior.transitionId())
          || !expectedPriorReservation.inventoryRevision().equals(expectedPrior.inventoryRevision())
          || expectedPriorReservation.lifecycle()
              != CanonicalIssuerPartitionReservation.Lifecycle.BOUND
          || next.equals(prior)
          || candidate.bindingGeneration().compareTo(expectedPrior.bindingGeneration()) <= 0) {
        throw new IllegalArgumentException(
            "candidate and prior do not form the exact controller transfer");
      }
    }
  }
}
