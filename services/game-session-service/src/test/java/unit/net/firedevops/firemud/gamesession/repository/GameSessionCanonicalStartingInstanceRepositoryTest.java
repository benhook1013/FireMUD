package unit.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalStartingInstanceRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameSessionCanonicalStartingInstanceRepositoryTest {
  private static final String NAMESPACE = "canonical-starting-test";

  @AfterEach
  void clearTransactionContext() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void ownerWriteRequiresWritableTransactionBeforeAnyDatabaseRead() {
    DSLContext dsl = mock(DSLContext.class);
    var repository = new GameSessionCanonicalStartingInstanceRepository(dsl, NAMESPACE);

    assertThatThrownBy(() -> repository.createStarting(null, null, null, null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable owner transaction");

    verifyNoInteractions(dsl);
  }

  @Test
  void refusesReadOnlyOwnerTransactionBeforeAnyOwnerLookup() {
    DSLContext dsl = mock(DSLContext.class);
    var repository = new GameSessionCanonicalStartingInstanceRepository(dsl, NAMESPACE);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

    assertThatThrownBy(() -> repository.createStarting(null, null, null, null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable owner transaction");

    verifyNoInteractions(dsl);
  }

  @Test
  void repositoryIsNotRegisteredAsAnExecutableSpringComponent() {
    assertThat(
            GameSessionCanonicalStartingInstanceRepository.class.isAnnotationPresent(
                Repository.class))
        .isFalse();
  }
}
