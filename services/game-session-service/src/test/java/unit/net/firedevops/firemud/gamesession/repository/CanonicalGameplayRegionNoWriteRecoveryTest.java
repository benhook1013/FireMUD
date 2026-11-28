package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.grpc.Context;
import io.grpc.Deadline;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;
import org.junit.jupiter.api.Test;

class CanonicalGameplayRegionNoWriteRecoveryTest {
  @Test
  void cleanupAdmissionRefusalClearsActiveSlotAndRetainsExactProofForNextLogin() throws Exception {
    var repository = mock(CanonicalGameplayRegionRuntimeStatusRepository.class);
    when(repository.forBudget(any())).thenReturn(repository);
    var owner = owner(repository);
    var preparation =
        mock(CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparation.class);
    var proof = mock(CanonicalGameplayRegionLeaseRedisOwner.InitializationNoWriteProof.class);
    UUID operation = UUID.randomUUID();
    UUID transition = UUID.randomUUID();
    when(preparation.operationId()).thenReturn(operation);
    when(preparation.transitionId()).thenReturn(transition);
    var retained = owner.reserveNoWriteRecovery(operation);
    owner.retainNoWriteProof(preparation, retained, proof);

    var recovery =
        CanonicalGameplayRegionLeaseOwner.class.getDeclaredMethod(
            "recoverNoWrite",
            CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparation.class,
            CanonicalGameplayRegionLeaseOwner.RetainedNoWriteRecovery.class,
            Clock.class,
            long.class,
            Deadline.class);
    recovery.setAccessible(true);
    assertThatThrownBy(() ->
            recovery.invoke(
                owner,
                preparation,
                retained,
                Clock.systemUTC(),
                Long.MAX_VALUE,
                Deadline.after(0, TimeUnit.NANOSECONDS)))
        .hasCauseInstanceOf(IllegalStateException.class);

    owner.releaseNoWriteRecovery(operation, retained);
    var nextLogin = owner.reserveNoWriteRecovery(operation);
    assertThat(nextLogin).isSameAs(retained);
    assertThat(nextLogin.proof).isSameAs(proof);
    owner.releaseNoWriteRecovery(operation, nextLogin);
    owner.recoverRetainedNoWriteForTransition(transition);
    assertThat(owner.reserveNoWriteRecovery(operation)).isNotSameAs(retained);
    verify(repository).releaseInitialTimelineNoWrite(eq(preparation), same(proof), any());
  }

  @Test
  void cleanupFailureRetainsExactProofAndExclusiveSlotForRetry() {
    var repository = mock(CanonicalGameplayRegionRuntimeStatusRepository.class);
    when(repository.forBudget(any())).thenReturn(repository);
    var owner = owner(repository);
    var preparation =
        mock(CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparation.class);
    var proof = mock(CanonicalGameplayRegionLeaseRedisOwner.InitializationNoWriteProof.class);
    UUID operation = UUID.randomUUID();
    UUID transition = UUID.randomUUID();
    when(preparation.operationId()).thenReturn(operation);
    when(preparation.transitionId()).thenReturn(transition);
    var first = owner.reserveNoWriteRecovery(operation);
    owner.retainNoWriteProof(preparation, first, proof);
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
    owner.releaseNoWriteRecovery(operation, retry);
    owner.recoverRetainedNoWriteForTransition(transition);
    assertThat(retry.proof).isNull();
    assertThat(owner.reserveNoWriteRecovery(operation)).isNotSameAs(first);
    verify(repository, times(2)).releaseInitialTimelineNoWrite(eq(preparation), same(proof), any());
  }

  @Test
  void scopedPreparationRetryUsesOriginalProofAndCurrentBudgetedRepository() {
    var originalRepository = mock(CanonicalGameplayRegionRuntimeStatusRepository.class);
    var retryRepository = mock(CanonicalGameplayRegionRuntimeStatusRepository.class);
    var owner = owner(originalRepository, true);
    var retainedPreparation =
        mock(CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparation.class);
    var scopedRead = mock(CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparation.class);
    var unrelatedRead =
        mock(CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparation.class);
    var proof = mock(CanonicalGameplayRegionLeaseRedisOwner.InitializationNoWriteProof.class);
    UUID operation = UUID.randomUUID();
    when(scopedRead.operationId()).thenReturn(operation);
    when(unrelatedRead.operationId()).thenReturn(UUID.randomUUID());

    var first = owner.reserveNoWriteRecovery(operation);
    owner.retainNoWriteProof(retainedPreparation, first, proof);
    assertThat(owner.recoverRetainedNoWriteForPreparation(unrelatedRead, retryRepository))
        .isFalse();
    assertThatThrownBy(
            () -> owner.recoverRetainedNoWriteForPreparation(scopedRead, retryRepository))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(retryRepository);
    owner.releaseNoWriteRecovery(operation, first);

    doThrow(new IllegalStateException("controlled cleanup refusal"))
        .doNothing()
        .when(retryRepository)
        .releaseInitialTimelineNoWrite(same(retainedPreparation), same(proof), any());
    assertThatThrownBy(
            () -> owner.recoverRetainedNoWriteForPreparation(scopedRead, retryRepository))
        .isInstanceOf(IllegalStateException.class);
    assertThat(owner.recoverRetainedNoWriteForPreparation(scopedRead, retryRepository)).isTrue();
    assertThat(owner.recoverRetainedNoWriteForPreparation(scopedRead, retryRepository)).isFalse();
    verify(retryRepository, times(2))
        .releaseInitialTimelineNoWrite(same(retainedPreparation), same(proof), any());
    verifyNoInteractions(originalRepository);
  }

  @Test
  void installRetryUsesCurrentCallerCancellationForRetainedCleanup() {
    var repository = mock(CanonicalGameplayRegionRuntimeStatusRepository.class);
    var bindingInventory = mock(CanonicalGameplayBindingInventoryRepository.class);
    var owner =
        new CanonicalGameplayRegionLeaseOwner(
            bindingInventory,
            repository,
            mock(),
            mock(),
            mock(),
            mock(),
            Duration.ofSeconds(30),
            Clock.systemUTC(),
            true);
    var preparation =
        mock(CanonicalGameplayRegionRuntimeStatusRepository.TimelinePreparation.class);
    var proof = mock(CanonicalGameplayRegionLeaseRedisOwner.InitializationNoWriteProof.class);
    UUID operation = UUID.randomUUID();
    UUID transition = UUID.randomUUID();
    when(preparation.operationId()).thenReturn(operation);
    when(preparation.transitionId()).thenReturn(transition);
    var retained = owner.reserveNoWriteRecovery(operation);
    owner.retainNoWriteProof(preparation, retained, proof);
    owner.releaseNoWriteRecovery(operation, retained);
    var request = CanonicalInitialPlayerLocationRequest.getDefaultInstance();

    try (var cancelled = Context.current().withCancellation()) {
      cancelled.cancel(null);
      assertThatThrownBy(
              () -> cancelled.call(() -> owner.installForTransition(transition, request)))
          .isInstanceOf(RuntimeException.class);
    }
    verifyNoInteractions(repository, bindingInventory);

    when(repository.forBudget(any())).thenReturn(repository);
    when(bindingInventory.forBudget(any())).thenReturn(bindingInventory);
    when(bindingInventory.readProvisionalCasEvidence(transition))
        .thenThrow(new IllegalStateException("controlled missing binding"));
    assertThatThrownBy(() -> owner.installForTransition(transition, request))
        .hasMessage("controlled missing binding");
    var ordered = inOrder(repository, bindingInventory);
    ordered.verify(repository).releaseInitialTimelineNoWrite(same(preparation), same(proof), any());
    ordered.verify(bindingInventory).readProvisionalCasEvidence(transition);
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
    return owner(repository, false);
  }

  private static CanonicalGameplayRegionLeaseOwner owner(
      CanonicalGameplayRegionRuntimeStatusRepository repository,
      boolean deadlineBoundInstallation) {
    return new CanonicalGameplayRegionLeaseOwner(
        mock(),
        repository,
        mock(),
        mock(),
        mock(),
        mock(),
        Duration.ofSeconds(30),
        Clock.systemUTC(),
        deadlineBoundInstallation);
  }
}
