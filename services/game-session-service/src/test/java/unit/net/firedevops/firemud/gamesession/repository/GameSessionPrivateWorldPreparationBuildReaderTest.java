package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.Scope;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadGrpcCodec;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameSessionPrivateWorldPreparationBuildReaderTest {
  @Test
  void refusesProjectionOutsideOneCallerOwnedWritableTransaction() {
    var dsl = mock(DSLContext.class);
    var source = mock(GameSessionPrivateWorldPreparationSourceReader.class);
    var reader = new GameSessionPrivateWorldPreparationBuildReader(dsl, source);
    var request =
        new PrivateWorldPreparationReadGrpcCodec.Request(
            UUID.randomUUID(),
            new Scope("private-build-test", UUID.randomUUID(), "world", "realm"),
            UUID.randomUUID());

    assertThatThrownBy(() -> reader.read(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable owner transaction");
    verifyNoInteractions(dsl, source);

    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    try {
      assertThatThrownBy(() -> reader.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("writable owner transaction");
      verifyNoInteractions(dsl, source);
    } finally {
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }
}
