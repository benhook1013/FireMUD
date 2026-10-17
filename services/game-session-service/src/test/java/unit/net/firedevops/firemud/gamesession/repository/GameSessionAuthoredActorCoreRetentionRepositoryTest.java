package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameSessionAuthoredActorCoreRetentionRepositoryTest {
  private static final String NAMESPACE = "test";

  @AfterEach
  void clearAmbientTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void rejectsInvalidWorkloadNamespaceBeforeStorage() {
    DSLContext dsl = mock(DSLContext.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    assertThatThrownBy(
            () ->
                new GameSessionAuthoredActorCoreRetentionRepository(
                    dsl, transactionManager, "Not a DNS label"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workload namespace");

    verifyNoInteractions(dsl, transactionManager);
  }

  @Test
  void independentReadRejectsAmbientOwnerTransactionBeforeInspectingIdentityOrStorage() {
    DSLContext dsl = mock(DSLContext.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    var repository =
        new GameSessionAuthoredActorCoreRetentionRepository(dsl, transactionManager, NAMESPACE);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> repository.readByOriginalOperation(null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("independent owner snapshot");

    verifyNoInteractions(dsl, transactionManager);
  }

  @Test
  void appendRequiresCallerOwnedWritableTransactionBeforeInspectingCandidate() {
    DSLContext dsl = mock(DSLContext.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    var repository =
        new GameSessionAuthoredActorCoreRetentionRepository(dsl, transactionManager, NAMESPACE);

    assertThatThrownBy(() -> repository.retainInOwnerTransaction(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable caller-owned owner transaction");

    verifyNoInteractions(dsl, transactionManager);
  }

  @Test
  void invalidReadIdentityIsRejectedBeforeOpeningSnapshot() {
    DSLContext dsl = mock(DSLContext.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    var repository =
        new GameSessionAuthoredActorCoreRetentionRepository(dsl, transactionManager, NAMESPACE);

    assertThatThrownBy(
            () -> repository.readByOriginalOperation("other", UUID.randomUUID().toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("configured Game Session workload namespace");

    verifyNoInteractions(dsl, transactionManager);
  }
}
