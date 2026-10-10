package net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class VersionAssetPublicationRepositoryTest {
  private static final String TRANSACTION_REQUIRED =
      "Version asset publication writes require caller-owned writable READ_COMMITTED transaction";

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @ParameterizedTest
  @MethodSource("invalidTransactionContexts")
  void mappingAndSnapshotWritesRejectInvalidTransactionBeforeSql(
      boolean transactionActive, boolean readOnly, Integer isolationLevel) {
    TransactionSynchronizationManager.clear();
    TransactionSynchronizationManager.setActualTransactionActive(transactionActive);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(isolationLevel);
    DSLContext dsl = mock(DSLContext.class);
    VersionAssetPublicationRepository repository = new VersionAssetPublicationRepository(dsl);

    assertThatThrownBy(() -> repository.associateDraftAsset("tenant", 1, 1, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(TRANSACTION_REQUIRED);
    assertThatThrownBy(() -> repository.freezeOrReadSnapshot("tenant", 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(TRANSACTION_REQUIRED);

    verifyNoInteractions(dsl);
  }

  @Test
  void directAssociationCannotBypassSourceEvenInAnOtherwiseValidOwnerTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        TransactionDefinition.ISOLATION_READ_COMMITTED);
    DSLContext dsl = mock(DSLContext.class);
    assertThatThrownBy(
            () ->
                new VersionAssetPublicationRepository(dsl)
                    .associateDraftAsset("tenant", 1, 1, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("synchronized ASSET_REFERENCE");
    verifyNoInteractions(dsl);
  }

  private static Stream<Arguments> invalidTransactionContexts() {
    return Stream.of(
        Arguments.of(false, false, null),
        Arguments.of(true, true, TransactionDefinition.ISOLATION_READ_COMMITTED),
        Arguments.of(true, false, TransactionDefinition.ISOLATION_SERIALIZABLE));
  }
}
