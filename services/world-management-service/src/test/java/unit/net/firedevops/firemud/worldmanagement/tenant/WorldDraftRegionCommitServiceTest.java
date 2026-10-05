package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldDraftRegionCommitServiceTest {
  private final WorldDraftRegionCommitRepository repository =
      mock(WorldDraftRegionCommitRepository.class);
  private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
  private final WorldDraftRegionCommitService.PermissionVerifier verifier =
      mock(WorldDraftRegionCommitService.PermissionVerifier.class);
  private final WorldDraftRegionCommitPlan plan = mock(WorldDraftRegionCommitPlan.class);

  @AfterEach
  void clearTransactionFixture() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void defaultDenialCannotStartTransactionOrAccessOwnerStorage() {
    WorldDraftRegionCommitService service = new WorldDraftRegionCommitService(repository, manager);
    assertThatThrownBy(() -> service.store(plan)).hasMessageContaining("permission is unavailable");
    verifyNoInteractions(repository, manager);
  }

  @Test
  void rejectsCallerTransactionBeforeVerifierCouldMakeAnExternalCall() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    WorldDraftRegionCommitService service =
        new WorldDraftRegionCommitService(repository, manager, verifier);
    assertThatThrownBy(() -> service.store(plan)).hasMessageContaining("existing transaction");
    verifyNoInteractions(verifier, repository, manager);
  }

  @Test
  void syntheticVerifierPrecedesDedicatedWritableReadCommittedTransactionAndCommit() {
    // This sequencing fixture is not authenticated Account ordering or fence release proof.
    TransactionStatus status = mock(TransactionStatus.class);
    WorldDraftRegionCommitEvidence evidence = mock(WorldDraftRegionCommitEvidence.class);
    when(manager.getTransaction(any())).thenReturn(status);
    when(repository.store(plan)).thenReturn(evidence);
    WorldDraftRegionCommitService service =
        new WorldDraftRegionCommitService(repository, manager, verifier);
    assertThat(service.store(plan)).isSameAs(evidence);
    ArgumentCaptor<TransactionDefinition> definition =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    InOrder order = inOrder(verifier, manager, repository);
    order.verify(verifier).verify(plan);
    order.verify(manager).getTransaction(definition.capture());
    order.verify(repository).store(plan);
    order.verify(manager).commit(status);
    assertThat(definition.getValue().getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThat(definition.getValue().getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(definition.getValue().isReadOnly()).isFalse();
  }

  @Test
  void localFailureRollsBackWithoutReturningStorageEvidence() {
    TransactionStatus status = mock(TransactionStatus.class);
    when(manager.getTransaction(any())).thenReturn(status);
    when(repository.store(plan)).thenThrow(new IllegalStateException("late tuple conflict"));
    WorldDraftRegionCommitService service =
        new WorldDraftRegionCommitService(repository, manager, verifier);
    assertThatThrownBy(() -> service.store(plan)).hasMessage("late tuple conflict");
    InOrder order = inOrder(manager, repository);
    order.verify(manager).getTransaction(any());
    order.verify(repository).store(plan);
    order.verify(manager).rollback(status);
  }
}
