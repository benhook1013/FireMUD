package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CanonicalGameplayRegionNoWriteRecoveryTest {
  @Test
  void cleanupFailureRetainsExactProofAndExclusiveSlotForRetry() {
    var repository = mock(CanonicalGameplayRegionRuntimeStatusRepository.class);
    when(repository.forBudget(any())).thenReturn(repository);
    var owner = owner(repository);
    var preparation =
        mock(CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparation.class);
    var proof = mock(CanonicalGameplayRegionLeaseRedisOwner.InitializationNoWriteProof.class);
    UUID operation = UUID.randomUUID();
    var first = owner.reserveNoWriteRecovery(operation);
    first.proof = proof;
    assertThatThrownBy(() -> owner.reserveNoWriteRecovery(operation))
        .isInstanceOf(IllegalStateException.class);
    doThrow(new IllegalStateException("controlled cleanup refusal"))
        .doNothing()
        .when(repository)
        .releaseInitialTimelineNoWrite(eq(preparation), same(proof), any());
    assertThatThrownBy(() -> owner.recoverNoWrite(preparation, first))
        .isInstanceOf(IllegalStateException.class);
    owner.releaseNoWriteRecovery(operation, first);
    var retry = owner.reserveNoWriteRecovery(operation);
    assertThat(retry).isSameAs(first);
    assertThat(retry.proof).isSameAs(proof);
    owner.recoverNoWrite(preparation, retry);
    assertThat(retry.proof).isNull();
    owner.releaseNoWriteRecovery(operation, retry);
    assertThat(owner.reserveNoWriteRecovery(operation)).isNotSameAs(first);
    verify(repository, times(2)).releaseInitialTimelineNoWrite(eq(preparation), same(proof), any());
  }

  @Test
  void retainedProofCapacityRefusesBeforeNewClaimAndNeverEvictsOldProof() {
    var owner = owner(mock(CanonicalGameplayRegionRuntimeStatusRepository.class));
    UUID original = UUID.randomUUID();
    var first = owner.reserveNoWriteRecovery(original);
    first.proof = mock(CanonicalGameplayRegionLeaseRedisOwner.InitializationNoWriteProof.class);
    owner.releaseNoWriteRecovery(original, first);
    for (int i = 1; i < 64; i++) {
      UUID id = UUID.randomUUID();
      var slot = owner.reserveNoWriteRecovery(id);
      slot.proof = mock(CanonicalGameplayRegionLeaseRedisOwner.InitializationNoWriteProof.class);
      owner.releaseNoWriteRecovery(id, slot);
    }
    assertThatThrownBy(() -> owner.reserveNoWriteRecovery(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(owner.reserveNoWriteRecovery(original)).isSameAs(first);
  }

  private static CanonicalGameplayRegionLeaseOwner owner(
      CanonicalGameplayRegionRuntimeStatusRepository repository) {
    return new CanonicalGameplayRegionLeaseOwner(
        mock(),
        repository,
        mock(),
        mock(),
        mock(),
        mock(),
        Duration.ofSeconds(30),
        Clock.systemUTC());
  }
}
