package net.firedevops.firemud.gamesession.binding;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.UUID;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.junit.jupiter.api.Test;

class CanonicalGameplayBindingProvisionalCasEvidenceTest {
  @Test
  void permitsUnrepairedTransferButRefusesNewOnlyAcknowledgedRepairForTransfer() {
    assertThatCode(
            () -> transfer(CanonicalGameplayBindingInventoryEntry.IndexState.REPAIR_REQUIRED))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> transfer(CanonicalGameplayBindingInventoryEntry.IndexState.ACKNOWLEDGED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("candidate is not an exact non-admissible transition row");
  }

  private static CanonicalGameplayBindingProvisionalCasEvidence transfer(
      CanonicalGameplayBindingInventoryEntry.IndexState candidateAccountState) {
    var prior =
        entry(
            identity(10),
            BigInteger.ONE,
            CanonicalGameplayBindingInventoryEntry.Lifecycle.ACTIVE,
            CanonicalGameplayBindingInventoryEntry.IndexState.ACKNOWLEDGED,
            CanonicalGameplayBindingInventoryEntry.IndexState.ACKNOWLEDGED);
    var candidate =
        entry(
            identity(11),
            BigInteger.TWO,
            CanonicalGameplayBindingInventoryEntry.Lifecycle.PROVISIONAL,
            candidateAccountState,
            CanonicalGameplayBindingInventoryEntry.IndexState.REPAIR_REQUIRED);
    var reservation =
        reservation(candidate, CanonicalIssuerPartitionReservation.Lifecycle.RESERVED);
    var transition =
        new CanonicalGameplayBindingTransitionSnapshot(
            candidate.transitionId(),
            candidate.identity(),
            candidate.bindingGeneration(),
            candidate.bindingRef(),
            candidate.inventoryRevision(),
            candidate.issuerReservationId(),
            reservation.reservationFence(),
            candidate.accountIndexFence(),
            prior.bindingRef(),
            prior.bindingGeneration(),
            CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL,
            CanonicalGameplayAccountCoverageEvidence.noActiveFlow());
    return new CanonicalGameplayBindingProvisionalCasEvidence(
        transition,
        candidate,
        reservation,
        prior,
        reservation(prior, CanonicalIssuerPartitionReservation.Lifecycle.BOUND),
        null);
  }

  private static CanonicalGameplayBindingInventoryEntry entry(
      CanonicalGameplayBindingIdentity identity,
      BigInteger generation,
      CanonicalGameplayBindingInventoryEntry.Lifecycle lifecycle,
      CanonicalGameplayBindingInventoryEntry.IndexState accountState,
      CanonicalGameplayBindingInventoryEntry.IndexState issuerState) {
    return new CanonicalGameplayBindingInventoryEntry(
        identity,
        identity.bindingRef(),
        generation,
        id(20),
        id(30 + generation.longValue()),
        id(40 + generation.longValue()),
        lifecycle,
        accountState,
        issuerState,
        generation);
  }

  private static CanonicalIssuerPartitionReservation reservation(
      CanonicalGameplayBindingInventoryEntry entry,
      CanonicalIssuerPartitionReservation.Lifecycle lifecycle) {
    return new CanonicalIssuerPartitionReservation(
        entry.issuerReservationId(),
        entry.identity(),
        entry.bindingGeneration(),
        entry.transitionId(),
        BigInteger.ZERO,
        lifecycle,
        id(50),
        id(60),
        BigInteger.ONE,
        BigInteger.TWO,
        BigInteger.ONE,
        entry.inventoryRevision());
  }

  private static CanonicalGameplayBindingIdentity identity(long session) {
    return new CanonicalGameplayBindingIdentity(
        id(1),
        id(2),
        id(3),
        id(4),
        "SHARED",
        id(5),
        1L,
        id(session).toString(),
        id(6),
        BigInteger.ONE,
        GameSessionAccountDelegationProfile.ISSUER,
        BigInteger.ONE,
        BigInteger.ONE,
        BigInteger.ONE,
        BigInteger.TEN);
  }

  private static UUID id(long value) {
    return new UUID(0L, value);
  }
}
