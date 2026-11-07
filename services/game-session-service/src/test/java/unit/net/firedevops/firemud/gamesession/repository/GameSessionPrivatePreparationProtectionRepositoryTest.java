package unit.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Snapshot;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadGrpcCodec;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivatePreparationProtectionRepository;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameSessionPrivatePreparationProtectionRepositoryTest {
  @Test
  void busyRequestRollsBackBeforeFreshSerializableRetryAndBeforeAnySourceRead() {
    var dsl = mock(DSLContext.class);
    var transactions = mock(PlatformTransactionManager.class);
    var first = mock(TransactionStatus.class);
    var second = mock(TransactionStatus.class);
    when(transactions.getTransaction(any())).thenReturn(first, second);
    var busy = mock(Record.class);
    when(busy.get("acquired", Boolean.class)).thenReturn(false);
    var acquired = mock(Record.class);
    when(acquired.get("acquired", Boolean.class)).thenReturn(true);
    when(dsl.fetchOne(startsWith("SELECT pg_try_advisory_xact_lock"), any(Object[].class)))
        .thenReturn(busy, acquired);
    var reachedStoredRow =
        new IllegalStateException("reached stored row only after acquiring lock");
    when(dsl.fetchOne(startsWith("SELECT * FROM"), any(Object[].class)))
        .thenThrow(reachedStoredRow);
    var owner = new GameSessionPrivatePreparationProtectionRepository(dsl, transactions);
    var source = mock(Snapshot.class);
    // This test isolates transaction/lock order, not the separately tested evidence codec.
    try (var codec = mockStatic(PrivateWorldPreparationReadGrpcCodec.class)) {
      codec
          .when(() -> PrivateWorldPreparationReadGrpcCodec.stableBytes(source))
          .thenReturn(new byte[] {1});
      assertThatThrownBy(() -> owner.acquire(UUID.randomUUID(), source)).isSameAs(reachedStoredRow);
    }
    var order = inOrder(transactions, dsl);
    order.verify(transactions).getTransaction(argThat(this::finiteSerializableOwner));
    order.verify(dsl).fetchOne(startsWith("SELECT pg_try_advisory_xact_lock"), any(Object[].class));
    order.verify(transactions).rollback(first);
    order.verify(transactions).getTransaction(argThat(this::finiteSerializableOwner));
    order.verify(dsl).fetchOne(startsWith("SELECT pg_try_advisory_xact_lock"), any(Object[].class));
    order.verify(dsl).fetchOne(startsWith("SELECT * FROM"), any(Object[].class));
    order.verify(transactions).rollback(second);
    verifyNoMoreInteractions(transactions, dsl);
  }

  @Test
  void interruptedContentionFailsAfterRollbackWithoutRetainingAConnection() {
    var dsl = mock(DSLContext.class);
    var transactions = mock(PlatformTransactionManager.class);
    var status = mock(TransactionStatus.class);
    when(transactions.getTransaction(any())).thenReturn(status);
    var busy = mock(Record.class);
    when(busy.get("acquired", Boolean.class)).thenReturn(false);
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(busy);
    var owner = new GameSessionPrivatePreparationProtectionRepository(dsl, transactions);
    var source = mock(Snapshot.class);
    try (var codec = mockStatic(PrivateWorldPreparationReadGrpcCodec.class)) {
      codec
          .when(() -> PrivateWorldPreparationReadGrpcCodec.stableBytes(source))
          .thenReturn(new byte[] {1});
      Thread.currentThread().interrupt();
      assertThatThrownBy(() -> owner.acquire(UUID.randomUUID(), source))
          .isInstanceOf(
              GameSessionPrivatePreparationProtectionRepository.ProtectionContendedException.class)
          .hasMessageContaining("interrupted");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
    verify(transactions).getTransaction(argThat(this::finiteSerializableOwner));
    verify(transactions).rollback(status);
    verify(dsl).fetchOne(startsWith("SELECT pg_try_advisory_xact_lock"), any(Object[].class));
    verifyNoMoreInteractions(transactions, dsl);
  }

  @Test
  @Timeout(15)
  void continuedContentionExhaustsOneFiniteBudgetWithoutReachingSourceReads() {
    var dsl = mock(DSLContext.class);
    var transactions = mock(PlatformTransactionManager.class);
    var status = mock(TransactionStatus.class);
    when(transactions.getTransaction(any())).thenReturn(status);
    var busy = mock(Record.class);
    when(busy.get("acquired", Boolean.class)).thenReturn(false);
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(busy);
    var owner = new GameSessionPrivatePreparationProtectionRepository(dsl, transactions);
    var source = mock(Snapshot.class);
    try (var codec = mockStatic(PrivateWorldPreparationReadGrpcCodec.class)) {
      codec
          .when(() -> PrivateWorldPreparationReadGrpcCodec.stableBytes(source))
          .thenReturn(new byte[] {1});
      assertThatThrownBy(() -> owner.acquire(UUID.randomUUID(), source))
          .isInstanceOf(
              GameSessionPrivatePreparationProtectionRepository.ProtectionContendedException.class)
          .hasMessageContaining("remained busy");
    }
    verify(transactions, atLeastOnce()).getTransaction(argThat(this::finiteSerializableOwner));
    verify(transactions, atLeastOnce()).rollback(status);
    verify(dsl, atLeastOnce())
        .fetchOne(startsWith("SELECT pg_try_advisory_xact_lock"), any(Object[].class));
    verifyNoMoreInteractions(transactions, dsl);
  }

  private boolean finiteSerializableOwner(TransactionDefinition definition) {
    return definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW
        && definition.getIsolationLevel() == TransactionDefinition.ISOLATION_SERIALIZABLE
        && !definition.isReadOnly()
        && definition.getTimeout() == 5;
  }

  @Test
  void absentRetainedProtectionIsNotAcquisitionContention() {
    var dsl = mock(DSLContext.class);
    var transactions = mock(PlatformTransactionManager.class);
    var status = mock(TransactionStatus.class);
    when(transactions.getTransaction(any())).thenReturn(status);
    var owner = new GameSessionPrivatePreparationProtectionRepository(dsl, transactions);
    assertThatThrownBy(() -> owner.readCurrent(UUID.randomUUID(), UUID.randomUUID(), 1))
        .isExactlyInstanceOf(
            GameSessionPrivatePreparationProtectionRepository.ProtectionUnavailableException.class)
        .hasMessageContaining("absent");
    verify(transactions).getTransaction(argThat(this::finiteSerializableOwner));
    verify(transactions).rollback(status);
    verify(dsl).fetchOne(startsWith("SELECT * FROM"), any(Object[].class));
    verifyNoMoreInteractions(transactions, dsl);
  }

  @Test
  void malformedIdentitiesFailBeforeOwnerIo() {
    var dsl = mock(DSLContext.class);
    var transactions = mock(PlatformTransactionManager.class);
    var owner = new GameSessionPrivatePreparationProtectionRepository(dsl, transactions);
    assertThatThrownBy(() -> owner.acquire(new UUID(0, 0), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> owner.readCurrent(UUID.randomUUID(), UUID.randomUUID(), 0))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(dsl, transactions);
  }

  @Test
  void ambientTransactionCannotTurnAnUncommittedHoldIntoEvidence() {
    var dsl = mock(DSLContext.class);
    var transactions = mock(PlatformTransactionManager.class);
    var owner = new GameSessionPrivatePreparationProtectionRepository(dsl, transactions);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> owner.acquire(UUID.randomUUID(), null))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> owner.readCurrent(UUID.randomUUID(), UUID.randomUUID(), 1))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    verifyNoInteractions(dsl, transactions);
  }
}
