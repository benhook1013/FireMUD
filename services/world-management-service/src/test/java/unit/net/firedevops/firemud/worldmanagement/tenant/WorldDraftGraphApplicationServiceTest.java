package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldDraftGraphApplicationServiceTest {
  private final WorldDraftGraphApplicationRepository repository =
      mock(WorldDraftGraphApplicationRepository.class);
  private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
  private final WorldDraftGraphApplication application = mock(WorldDraftGraphApplication.class);
  private final WorldDraftTerminalOperation operation = mock(WorldDraftTerminalOperation.class);
  private final WorldDraftGraphApplicationService.CommitOrderVerifier verifier =
      mock(WorldDraftGraphApplicationService.CommitOrderVerifier.class);

  @AfterEach
  void clearTransactionFixture() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void defaultDenialPerformsOnlyCommittedLookupAndStartsNoWriterTransaction() {
    when(application.operation()).thenReturn(operation);
    when(repository.readCommitted(application)).thenReturn(Optional.empty());
    var service = new WorldDraftGraphApplicationService(repository, manager);
    assertThatThrownBy(() -> service.apply(application))
        .hasMessageContaining("COMMIT_ORDER producer is unavailable");
    verifyNoInteractions(manager);
  }

  @Test
  void callerTransactionDeniesBeforeAnyAuthorityOrStorageAccess() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    var service = new WorldDraftGraphApplicationService(repository, manager, verifier);
    assertThatThrownBy(() -> service.apply(application))
        .hasMessageContaining("no active caller transaction");
    verifyNoInteractions(repository, manager, verifier);
  }

  @Test
  void immutableExactRetryRequiresNeitherFreshPermissionNorWriterTransaction() {
    var result = mock(WorldDraftGraphAppliedResult.class);
    when(repository.readCommitted(application)).thenReturn(Optional.of(result));
    assertThat(
            new WorldDraftGraphApplicationService(repository, manager, verifier).apply(application))
        .isSameAs(result);
    verifyNoInteractions(manager, verifier);
  }

  @Test
  void changedOriginalProofDeniesBeforeWriterTransaction() {
    when(application.operation()).thenReturn(operation);
    when(operation.canonicalBytes()).thenReturn(new byte[] {1});
    when(operation.accountBindingBytes()).thenReturn(new byte[] {2});
    var proof = new WorldDraftGraphApplicationService.CommitOrderProof(operation);
    when(operation.accountBindingBytes()).thenReturn(new byte[] {3});
    when(repository.readCommitted(application)).thenReturn(Optional.empty());
    when(verifier.verifyHeldOriginalCommitOrder(operation)).thenReturn(proof);
    assertThatThrownBy(
            () ->
                new WorldDraftGraphApplicationService(repository, manager, verifier)
                    .apply(application))
        .hasMessageContaining("proof differs from original complete operation");
    verifyNoInteractions(manager);
  }

  @Test
  void stipulatedAuthorityPrecedesDedicatedTransactionAndLateFailureRollsBack() {
    // Component sequencing only: this fixture is not an authenticated Account producer.
    when(application.operation()).thenReturn(operation);
    when(operation.canonicalBytes()).thenReturn(new byte[] {1});
    when(operation.accountBindingBytes()).thenReturn(new byte[] {2});
    var proof = new WorldDraftGraphApplicationService.CommitOrderProof(operation);
    when(repository.readCommitted(application)).thenReturn(Optional.empty());
    when(verifier.verifyHeldOriginalCommitOrder(operation)).thenReturn(proof);
    var status = mock(TransactionStatus.class);
    when(manager.getTransaction(any())).thenReturn(status);
    when(repository.apply(application, proof)).thenThrow(new IllegalStateException("late failure"));
    assertThatThrownBy(
            () ->
                new WorldDraftGraphApplicationService(repository, manager, verifier)
                    .apply(application))
        .hasMessage("late failure");
    var order = inOrder(repository, verifier, manager);
    order.verify(repository).readCommitted(application);
    order.verify(verifier).verifyHeldOriginalCommitOrder(operation);
    var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
    order.verify(manager).getTransaction(definition.capture());
    order.verify(repository).apply(application, proof);
    order.verify(manager).rollback(status);
    assertThat(definition.getValue().getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThat(definition.getValue().getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(definition.getValue().isReadOnly()).isFalse();
  }
}
