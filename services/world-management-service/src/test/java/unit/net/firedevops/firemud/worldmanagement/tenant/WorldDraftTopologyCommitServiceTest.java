package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldDraftTopologyCommitServiceTest {
  private final WorldDraftTopologyCommitRepository repository =
      mock(WorldDraftTopologyCommitRepository.class);
  private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
  private final WorldDraftTopologyCommitService.PermissionVerifier verifier =
      mock(WorldDraftTopologyCommitService.PermissionVerifier.class);
  private final WorldDraftTopologyCommitPlan plan = mock(WorldDraftTopologyCommitPlan.class);

  @AfterEach
  void clearTransactionFixture() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void defaultDenialCannotStartTransactionOrAccessOwnerStorage() {
    WorldDraftTopologyCommitService service =
        new WorldDraftTopologyCommitService(repository, manager);
    assertThatThrownBy(() -> service.store(plan)).hasMessageContaining("permission is unavailable");
    verifyNoInteractions(repository, manager);
  }

  @Test
  void rejectsCallerTransactionBeforeVerifierCouldMakeAnExternalCall() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    WorldDraftTopologyCommitService service =
        new WorldDraftTopologyCommitService(repository, manager, verifier);
    assertThatThrownBy(() -> service.store(plan)).hasMessageContaining("existing transaction");
    verifyNoInteractions(verifier, repository, manager);
  }

  @Test
  void syntheticVerifierPrecedesDedicatedWritableReadCommittedTransactionAndCommit() {
    // This sequencing fixture is not authenticated Account ordering or fence release proof.
    TransactionStatus status = mock(TransactionStatus.class);
    WorldDraftTopologyCommitEvidence evidence = mock(WorldDraftTopologyCommitEvidence.class);
    when(manager.getTransaction(any())).thenReturn(status);
    when(repository.store(plan)).thenReturn(evidence);
    WorldDraftTopologyCommitService service =
        new WorldDraftTopologyCommitService(repository, manager, verifier);
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
    WorldDraftTopologyCommitService service =
        new WorldDraftTopologyCommitService(repository, manager, verifier);
    assertThatThrownBy(() -> service.store(plan)).hasMessage("late tuple conflict");
    InOrder order = inOrder(manager, repository);
    order.verify(manager).getTransaction(any());
    order.verify(repository).store(plan);
    order.verify(manager).rollback(status);
  }

  @Test
  void committedReadbackReturnsOriginalEvidenceWithoutVerifierOrTransactionInteractions() {
    WorldDraftTopologyCommitEvidence evidence = mock(WorldDraftTopologyCommitEvidence.class);
    when(repository.readCommitted(plan)).thenReturn(Optional.of(evidence));
    WorldDraftTopologyCommitService service =
        new WorldDraftTopologyCommitService(repository, manager, verifier);
    assertThat(service.readCommitted(plan)).containsSame(evidence);
    verify(repository).readCommitted(plan);
    verifyNoMoreInteractions(repository);
    verifyNoInteractions(verifier, manager);
  }

  @Test
  void missingCommittedReadbackRemainsUnknownWithoutAttemptingDeniedWriter() {
    when(repository.readCommitted(plan)).thenReturn(Optional.empty());
    WorldDraftTopologyCommitService service =
        new WorldDraftTopologyCommitService(repository, manager);
    assertThat(service.readCommitted(plan)).isEmpty();
    verify(repository).readCommitted(plan);
    verifyNoMoreInteractions(repository);
    verifyNoInteractions(verifier, manager);
  }

  @Test
  void committedReadbackRejectsActiveCallerBeforeAnyCollaboratorInteraction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    WorldDraftTopologyCommitService service =
        new WorldDraftTopologyCommitService(repository, manager, verifier);
    assertThatThrownBy(() -> service.readCommitted(plan))
        .hasMessageContaining("no active caller transaction");
    verifyNoInteractions(repository, verifier, manager, plan);
  }
}
