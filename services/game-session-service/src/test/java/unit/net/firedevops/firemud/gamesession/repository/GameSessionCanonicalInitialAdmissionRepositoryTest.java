package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameSessionCanonicalInitialAdmissionRepositoryTest {
  @Test
  void retainedDatabaseIdentifiersCompareByNumericValuePastBoxCache() {
    Record readback = mock(Record.class);
    Record ownerAttempt = mock(Record.class);
    Long readbackId = Long.valueOf(128L);
    Long ownerId = Long.valueOf(128L);
    when(readback.get("pointer_id", Long.class)).thenReturn(readbackId);
    when(ownerAttempt.get("pointer_id", Long.class)).thenReturn(ownerId);

    assertThat(readbackId).isNotSameAs(ownerId);
    assertThat(
            GameSessionCanonicalInitialAdmissionRepository.sameRequiredLongValue(
                readback, "pointer_id", ownerAttempt, "pointer_id"))
        .isTrue();

    when(ownerAttempt.get("pointer_id", Long.class)).thenReturn(Long.valueOf(129L));
    assertThat(
            GameSessionCanonicalInitialAdmissionRepository.sameRequiredLongValue(
                readback, "pointer_id", ownerAttempt, "pointer_id"))
        .isFalse();
  }

  @Test
  void publicOwnerReadRejectsAmbientTransactionBeforeStorage() {
    DSLContext dsl = mock(DSLContext.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    var repository =
        new GameSessionCanonicalInitialAdmissionRepository(
            dsl,
            transactionManager,
            "test",
            mock(GameSessionStartSessionOperatorAttemptRepository.class),
            mock(GameSessionCanonicalRealmCatalogRepository.class));
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> repository.readTerminal(null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("own their independent transactions");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    verifyNoInteractions(dsl, transactionManager);
  }
}
