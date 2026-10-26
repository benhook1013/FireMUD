package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingProvisionalCasEvidence;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;

/**
 * Exact, typed source bytes for the non-admitting original {@code NEW} session candidate.
 *
 * <p>This is a data carrier only. It does not prove that any input came from Account or the
 * durable Game Session owners, establish a physical Redis readback, finalize the Account lease, or
 * authorize gameplay. Authenticated session readers must reject its separate purpose discriminator.
 *
 * <p>The payload retains the complete immutable Account lease and original decision sources plus
 * the durable candidate, transition, reservation, and target identities. It deliberately omits
 * mutable preparation status and current inventory revisions so a retry after unrelated SQL
 * inventory progress reproduces the original candidate bytes.
 */
public final class CanonicalGameplayOriginalNewCandidate {
  public static final String PURPOSE = "original-new-non-admitting-candidate/v1";

  private final UUID bindingDecisionId;
  private final AccountGameplayAdmissionLeaseEvidence leaseEvidence;
  private final CanonicalGameplayBindingIdentity bindingIdentity;
  private final CanonicalPlayableTarget targetEvidence;
  private final TransitionSource transition;
  private final ReservationSource reservation;

  CanonicalGameplayOriginalNewCandidate(
      UUID bindingDecisionId,
      AccountGameplayAdmissionLeaseEvidence leaseEvidence,
      CanonicalGameplayBindingIdentity bindingIdentity,
      CanonicalPlayableTarget targetEvidence,
      TransitionSource transition,
      ReservationSource reservation) {
    this.bindingDecisionId = Objects.requireNonNull(bindingDecisionId, "bindingDecisionId");
    this.leaseEvidence = Objects.requireNonNull(leaseEvidence, "leaseEvidence");
    this.bindingIdentity = Objects.requireNonNull(bindingIdentity, "bindingIdentity");
    this.targetEvidence = Objects.requireNonNull(targetEvidence, "targetEvidence");
    this.transition = Objects.requireNonNull(transition, "transition");
    this.reservation = Objects.requireNonNull(reservation, "reservation");
  }

  /**
   * Freezes the selected original sources after checking their shared identity and NEW-only
   * preconditions. This factory is structural and does not elevate either source to authority.
   */
  static CanonicalGameplayOriginalNewCandidate fromOriginalSources(
      CanonicalGameplayBindingProvisionalCasEvidence provisionalEvidence,
      CanonicalGameplayAdmissionDecision originalDecision) {
    Objects.requireNonNull(provisionalEvidence, "provisionalEvidence");
    Objects.requireNonNull(originalDecision, "originalDecision");
    CanonicalGameplayOriginalNewCandidateCodec.requireOriginalNewSources(
        provisionalEvidence, originalDecision);
    CanonicalGameplayBindingTransitionSnapshot transition = provisionalEvidence.transition();
    CanonicalGameplayBindingInventoryEntry candidate = provisionalEvidence.candidate();
    CanonicalIssuerPartitionReservation reservation = provisionalEvidence.candidateReservation();
    return new CanonicalGameplayOriginalNewCandidate(
        originalDecision.bindingDecisionId(),
        originalDecision.leaseEvidence(),
        candidate.identity(),
        originalDecision.targetEvidence(),
        new TransitionSource(
            transition.transitionId(),
            transition.bindingRef(),
            transition.bindingGeneration(),
            transition.issuerReservationId(),
            transition.reservationFence(),
            transition.candidateAccountIndexFence()),
        CanonicalGameplayOriginalNewCandidateCodec.reservationSource(reservation));
  }

  /** Parses only this closed candidate format; the result is not a verified readback capability. */
  public static CanonicalGameplayOriginalNewCandidate parseCanonical(byte[] encoded) {
    return CanonicalGameplayOriginalNewCandidateCodec.decode(encoded);
  }

  public UUID bindingDecisionId() {
    return bindingDecisionId;
  }

  /** Complete immutable lease carrier, including its unchanged baseline, tuple, and checkpoints. */
  public AccountGameplayAdmissionLeaseEvidence leaseEvidence() {
    return leaseEvidence;
  }

  /** Exact canonical session identity from which the session key is derived by its owner. */
  public CanonicalGameplayBindingIdentity bindingIdentity() {
    return bindingIdentity;
  }

  /** Full original canonical target source retained with the decision. */
  public CanonicalPlayableTarget targetEvidence() {
    return targetEvidence;
  }

  /** Stable transition identity and fences used by the original provisional CAS. */
  public TransitionSource transition() {
    return transition;
  }

  /** Immutable reservation identity, fences and original snapshot; current revisions may advance. */
  public ReservationSource reservation() {
    return reservation;
  }

  /** The one exact canonical JSON preimage to install and later exact-compare. */
  public byte[] canonicalBytes() {
    return CanonicalGameplayOriginalNewCandidateCodec.encode(this);
  }

  public String canonicalJson() {
    return new String(canonicalBytes(), java.nio.charset.StandardCharsets.UTF_8);
  }

  /** Exact byte comparison for a physical preimage/readback or final-CAS expected value. */
  public boolean exactlyMatches(byte[] physicalValue) {
    return physicalValue != null && Arrays.equals(canonicalBytes(), physicalValue);
  }

  /**
   * Compares every retained original-decision field. The decision class has no value equality, so
   * this comparison is explicit and includes its complete Account lease and full target source.
   */
  public boolean matchesOriginalDecision(CanonicalGameplayAdmissionDecision decision) {
    return CanonicalGameplayOriginalNewCandidateCodec.matchesOriginalDecision(this, decision);
  }

  /** Checks stable source identity while allowing only the documented PREPARED/PROVISIONAL retry. */
  public boolean matchesProvisionalSources(
      CanonicalGameplayBindingProvisionalCasEvidence evidence) {
    return CanonicalGameplayOriginalNewCandidateCodec.matchesProvisionalSources(this, evidence);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof CanonicalGameplayOriginalNewCandidate candidate
        && Arrays.equals(canonicalBytes(), candidate.canonicalBytes());
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(canonicalBytes());
  }

  /** Avoid logging the lease or source payload through implicit object formatting. */
  @Override
  public String toString() {
    return "CanonicalGameplayOriginalNewCandidate[purpose=" + PURPOSE + ", nonAdmitting=true]";
  }

  /** Immutable stable projection of the transition's original identity and fences. */
  public record TransitionSource(
      UUID transitionId,
      CanonicalGameplayBindingRef bindingRef,
      BigInteger bindingGeneration,
      UUID issuerReservationId,
      UUID reservationFence,
      UUID candidateAccountIndexFence) {
    public TransitionSource {
      Objects.requireNonNull(transitionId, "transitionId");
      Objects.requireNonNull(bindingRef, "bindingRef");
      requirePositive(bindingGeneration, "bindingGeneration");
      Objects.requireNonNull(issuerReservationId, "issuerReservationId");
      Objects.requireNonNull(reservationFence, "reservationFence");
      Objects.requireNonNull(candidateAccountIndexFence, "candidateAccountIndexFence");
    }
  }

  /** Immutable reservation source fields; the current mutable inventory revision is excluded. */
  public record ReservationSource(
      UUID reservationId,
      CanonicalGameplayBindingRef ownerBindingRef,
      BigInteger bindingGeneration,
      UUID transitionId,
      BigInteger partitionId,
      UUID reservationFence,
      UUID issuerCoverageOperationId,
      BigInteger issuerCoverageOperationFence,
      BigInteger coverageFence,
      BigInteger inventorySnapshotRevision) {
    public ReservationSource {
      Objects.requireNonNull(reservationId, "reservationId");
      Objects.requireNonNull(ownerBindingRef, "ownerBindingRef");
      requirePositive(bindingGeneration, "bindingGeneration");
      Objects.requireNonNull(transitionId, "transitionId");
      requireNonNegative(partitionId, "partitionId");
      Objects.requireNonNull(reservationFence, "reservationFence");
      Objects.requireNonNull(issuerCoverageOperationId, "issuerCoverageOperationId");
      requirePositive(issuerCoverageOperationFence, "issuerCoverageOperationFence");
      requirePositive(coverageFence, "coverageFence");
      requirePositive(inventorySnapshotRevision, "inventorySnapshotRevision");
    }
  }

  private static void requirePositive(BigInteger value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
  }

  private static void requireNonNegative(BigInteger value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() < 0) throw new IllegalArgumentException(name + " must not be negative");
  }
}
