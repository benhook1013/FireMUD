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

class WorldCanonicalFrozenTopologyServiceTest {
  private final WorldCanonicalFrozenTopologyRepository repository =
      mock(WorldCanonicalFrozenTopologyRepository.class);
  private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
  private final WorldCanonicalFrozenTopology.Request request =
      mock(WorldCanonicalFrozenTopology.Request.class);
  private final WorldCanonicalFrozenTopology result = mock(WorldCanonicalFrozenTopology.class);

  @AfterEach
  void clear() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactHistoricalRetryNeverReentersWriterOrOwnerTransaction() {
    when(repository.readCommitted(request)).thenReturn(Optional.of(result));
    var service = new WorldCanonicalFrozenTopologyService(repository, manager);
    assertThat(service.capture(request)).isSameAs(result);
    verifyNoInteractions(manager);
    org.mockito.Mockito.verify(repository).readCommitted(request);
    org.mockito.Mockito.verifyNoMoreInteractions(repository);
  }

  @Test
  void absentHistoricalReadRemainsUnknownAndCannotStartCapture() {
    when(repository.readCommitted(request)).thenReturn(Optional.empty());
    assertThat(new WorldCanonicalFrozenTopologyService(repository, manager).readCommitted(request))
        .isEmpty();
    verifyNoInteractions(manager);
    org.mockito.Mockito.verify(repository).readCommitted(request);
    org.mockito.Mockito.verifyNoMoreInteractions(repository);
  }

  @Test
  void newCaptureUsesDedicatedWritableReadCommittedTransactionAndCommit() {
    var status = mock(TransactionStatus.class);
    when(repository.readCommitted(request)).thenReturn(Optional.empty());
    when(manager.getTransaction(any())).thenReturn(status);
    when(repository.capture(request)).thenReturn(result);
    assertThat(new WorldCanonicalFrozenTopologyService(repository, manager).capture(request))
        .isSameAs(result);
    var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
    var order = inOrder(repository, manager);
    order.verify(repository).readCommitted(request);
    order.verify(manager).getTransaction(definition.capture());
    order.verify(repository).capture(request);
    order.verify(manager).commit(status);
    assertThat(definition.getValue().getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(definition.getValue().getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThat(definition.getValue().isReadOnly()).isFalse();
  }

  @Test
  void lateFailureRollsBackWithoutReturningAResult() {
    var status = mock(TransactionStatus.class);
    when(repository.readCommitted(request)).thenReturn(Optional.empty());
    when(manager.getTransaction(any())).thenReturn(status);
    when(repository.capture(request)).thenThrow(new IllegalStateException("mismatched freeze"));
    assertThatThrownBy(
            () -> new WorldCanonicalFrozenTopologyService(repository, manager).capture(request))
        .hasMessage("mismatched freeze");
    org.mockito.Mockito.verify(manager).rollback(status);
  }

  @Test
  void rejectsActiveCallerBeforeReadOrCapture() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    var service = new WorldCanonicalFrozenTopologyService(repository, manager);
    assertThatThrownBy(() -> service.capture(request))
        .hasMessageContaining("independent owner operation");
    assertThatThrownBy(() -> service.readCommitted(request))
        .hasMessageContaining("independent owner operation");
    verifyNoInteractions(repository, manager);
  }
}
