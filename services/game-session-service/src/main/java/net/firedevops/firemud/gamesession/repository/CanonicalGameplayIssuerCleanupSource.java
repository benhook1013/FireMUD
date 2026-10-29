package net.firedevops.firemud.gamesession.repository;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionAbortSource;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;

/**
 * Purpose-preserving union of actual positive-operation abort and never-admitted negative proof.
 */
final class CanonicalGameplayIssuerCleanupSource {
  private final CanonicalGameplayAdmissionAbortSource positive;
  private final CanonicalGameplayOriginalNewAbandonmentSource negative;

  private CanonicalGameplayIssuerCleanupSource(
      CanonicalGameplayAdmissionAbortSource positive,
      CanonicalGameplayOriginalNewAbandonmentSource negative) {
    this.positive = positive;
    this.negative = negative;
  }

  static CanonicalGameplayIssuerCleanupSource positive(
      CanonicalGameplayAdmissionAbortSource source) {
    return new CanonicalGameplayIssuerCleanupSource(Objects.requireNonNull(source), null);
  }

  static CanonicalGameplayIssuerCleanupSource negative(
      CanonicalGameplayOriginalNewAbandonmentSource source) {
    Objects.requireNonNull(source);
    if (source.transition().status() != CanonicalGameplayBindingTransitionSnapshot.Status.ABORTED
        || source.state()
            != CanonicalGameplayOriginalNewAbandonmentSource.State.NEVER_ADMITTED_CLOSED
        || source.negativeResolutionId() == null
        || source.orphanCleanupId() == null) {
      throw new IllegalArgumentException(
          "Issuer negative cleanup requires attributable Account terminal evidence");
    }
    return new CanonicalGameplayIssuerCleanupSource(null, source);
  }

  boolean neverAdmitted() {
    return negative != null;
  }

  CanonicalGameplayAdmissionAbortSource positiveSource() {
    if (positive == null)
      throw new IllegalStateException("Negative cleanup is not a positive abort source");
    return positive;
  }

  CanonicalGameplayBindingTransitionSnapshot transition() {
    return positive != null ? positive.transition() : negative.transition();
  }

  CanonicalGameplayBindingInventoryEntry candidate() {
    return positive != null ? positive.candidate() : negative.inventory();
  }

  CanonicalIssuerPartitionReservation reservation() {
    return positive != null ? positive.reservation() : negative.reservation();
  }

  UUID orphanCleanupId() {
    return positive != null ? positive.orphanCleanupId() : negative.orphanCleanupId();
  }

  boolean isAccountAborted() {
    return positive != null ? positive.isAccountAborted() : negative.negativeResolutionId() != null;
  }

  CanonicalGameplayAdmissionDecision originalDecision() {
    return positive != null
        ? positive.originalDecision()
        : new CanonicalGameplayAdmissionDecision(
            negative.candidate().bindingDecisionId(),
            negative.candidate().leaseEvidence(),
            negative.candidate().bindingIdentity(),
            negative.candidate().transition().bindingGeneration(),
            null,
            negative.candidate().targetEvidence());
  }
}
