package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameSessionStartSessionOperatorAttemptRepositoryTest {
  @AfterEach
  void clearTransactionSynchronization() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void rejectsCallsOutsideWritableOwnerTransactionBeforeStorageAccess() {
    DSLContext dsl = mock(DSLContext.class);
    GameSessionStartSessionOperatorAttemptRepository repository =
        new GameSessionStartSessionOperatorAttemptRepository(dsl, java.time.Duration.ofSeconds(30));

    assertThatThrownBy(() -> repository.reserve(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable READ COMMITTED");

    verifyNoInteractions(dsl);
  }

  @Test
  void rejectsReadOnlyOwnerTransactionBeforeStorageAccess() {
    DSLContext dsl = mock(DSLContext.class);
    GameSessionStartSessionOperatorAttemptRepository repository =
        new GameSessionStartSessionOperatorAttemptRepository(dsl, java.time.Duration.ofSeconds(30));
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

    assertThatThrownBy(() -> repository.reserve(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable READ COMMITTED");

    verifyNoInteractions(dsl);
  }

  @Test
  void requiresAnExplicitlyBoundedClaimLease() {
    DSLContext dsl = mock(DSLContext.class);

    assertThatThrownBy(
            () ->
                new GameSessionStartSessionOperatorAttemptRepository(dsl, java.time.Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive and at most five minutes");
    assertThatThrownBy(
            () ->
                new GameSessionStartSessionOperatorAttemptRepository(
                    dsl, java.time.Duration.ofMinutes(6)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive and at most five minutes");

    verifyNoInteractions(dsl);
  }
}
