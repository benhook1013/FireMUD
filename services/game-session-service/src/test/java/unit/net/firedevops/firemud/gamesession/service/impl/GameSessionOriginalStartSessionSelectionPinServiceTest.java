package unit.net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidence;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionOriginalStartSessionSelectionPinRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.service.impl.GameSessionOriginalStartSessionSelectionPinService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameSessionOriginalStartSessionSelectionPinServiceTest {
  private static final String NAMESPACE = "gameplay";
  private final GameSessionOriginalStartSessionSelectionPinRepository pins =
      mock(GameSessionOriginalStartSessionSelectionPinRepository.class);
  private final GameSessionStartSessionOperatorAttemptRepository attempts =
      mock(GameSessionStartSessionOperatorAttemptRepository.class);
  private final GameSessionCanonicalInstanceLaunchRepository launches =
      mock(GameSessionCanonicalInstanceLaunchRepository.class);

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void missingPinIsNotReconstructedAndLookupUsesOneReadOnlyRepeatableReadSnapshot() {
    var txManager = new ObservingTransactionManager();
    var selector = selector(NAMESPACE);
    var request =
        new HistoricalOriginalStartSessionOwnerEvidence.Request(selector, UUID.randomUUID(), 7);
    when(pins.readByOriginalOperationInOwnerTransaction(
            NAMESPACE, selector.controlPlaneRequestId()))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
              assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                  .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
              return Optional.empty();
            });
    var service =
        new GameSessionOriginalStartSessionSelectionPinService(
            pins, attempts, launches, txManager, NAMESPACE);

    assertThat(service.readHistorical(request)).isEmpty();
    assertThat(txManager.lastReadOnly).isTrue();
    assertThat(txManager.lastIsolation).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    verify(pins)
        .readByOriginalOperationInOwnerTransaction(NAMESPACE, selector.controlPlaneRequestId());
    verifyNoInteractions(attempts, launches);
  }

  @Test
  void crossNamespaceRequestCannotReachThePinOrOwnerReaders() {
    var txManager = new ObservingTransactionManager();
    var selector = selector("other");
    var service =
        new GameSessionOriginalStartSessionSelectionPinService(
            pins, attempts, launches, txManager, NAMESPACE);

    assertThatThrownBy(
            () ->
                service.readHistorical(
                    new HistoricalOriginalStartSessionOwnerEvidence.Request(
                        selector, UUID.randomUUID(), 7)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("configured Game Session owner");
    verifyNoInteractions(pins, attempts, launches);
    assertThat(txManager.lastIsolation).isZero();
  }

  private static CanonicalGameInstanceLaunchAssociationReadEvidence.Request selector(
      String namespace) {
    return new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
        UUID.randomUUID(),
        namespace,
        UUID.randomUUID(),
        "test-world",
        UUID.randomUUID(),
        "original-operation",
        "descriptor",
        "sha256:" + "1".repeat(64),
        "sha256:" + "2".repeat(64),
        "sha256:" + "3".repeat(64));
  }

  private static final class ObservingTransactionManager implements PlatformTransactionManager {
    private boolean lastReadOnly;
    private int lastIsolation;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      TransactionDefinition effectiveDefinition =
          definition == null ? new DefaultTransactionDefinition() : definition;
      lastReadOnly = effectiveDefinition.isReadOnly();
      lastIsolation = effectiveDefinition.getIsolationLevel();
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(lastReadOnly);
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(lastIsolation);
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      clear();
    }

    @Override
    public void rollback(TransactionStatus status) {
      clear();
    }

    private static void clear() {
      TransactionSynchronizationManager.setActualTransactionActive(false);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
    }
  }
}
