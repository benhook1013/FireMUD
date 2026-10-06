package integration.net.firedevops.firemud.accountservice.authorpublication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.accountservice.authorpublication.AccountPublicationTerminalReconciliationService;
import net.firedevops.firemud.accountservice.authorpublication.PublicationAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authorpublication.PublicationAuthorizationFenceRepository.Owner;
import net.firedevops.firemud.accountservice.authorpublication.PublicationAuthorizationFenceRepository.OwnerResultSnapshot;
import net.firedevops.firemud.accountservice.authorpublication.PublicationAuthorizationFenceRepository.Settlement;
import net.firedevops.firemud.accountservice.authorpublication.PublicationAuthorizationFenceRepository.TerminalSnapshot;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalClient;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.world.WorldPublicationTerminalClient;
import net.firedevops.firemud.common.world.WorldPublicationTerminalReadEvidence;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Synthetic concrete-client transport doubles test transaction placement, never actual mTLS proof.
 */
class AccountPublicationTerminalReconciliationServiceTest {
  private static final String NAMESPACE = "test";

  @Test
  void concreteOwnerClientsRunOutsideReadCommittedAccountTransactionsAndExactPairIsRetained()
      throws Exception {
    var fixture = AccountPublicationTerminalFixtures.scenario();
    var repository = mock(PublicationAuthorizationFenceRepository.class);
    var gameDesignClient = mock(GameDesignPublicationTerminalClient.class);
    var worldClient = mock(WorldPublicationTerminalClient.class);
    var manager = new TestTransactionManager();
    var stored = new EnumMap<Owner, OwnerResultSnapshot>(Owner.class);
    when(repository.readOriginalOperation(any(GameDesignPublicationOperationBinding.class)))
        .thenAnswer(
            invocation -> {
              assertInsideOwnerTransaction();
              var candidate = (GameDesignPublicationOperationBinding) invocation.getArgument(0);
              assertThat(candidate.canonicalBytes())
                  .containsExactly(fixture.operation().canonicalBytes());
              return Optional.of(snapshot(stored));
            });
    when(repository.readSettlement(any(GameDesignPublicationOperationBinding.class)))
        .thenAnswer(
            invocation -> {
              assertInsideOwnerTransaction();
              var gameDesign = stored.get(Owner.GAME_DESIGN);
              var world = stored.get(Owner.WORLD);
              return gameDesign != null
                      && world != null
                      && java.util.Arrays.equals(gameDesign.terminalBytes(), world.terminalBytes())
                  ? Settlement.NO_PUBLICATION
                  : Settlement.PENDING;
            });
    when(repository.recordOwnerResult(
            any(GameDesignPublicationOperationBinding.class), any(), any()))
        .thenAnswer(
            invocation -> {
              assertInsideOwnerTransaction();
              Owner owner = invocation.getArgument(1);
              GameDesignPublicationTerminalEvidence terminal = invocation.getArgument(2);
              var result =
                  new OwnerResultSnapshot(
                      terminal.outcome(),
                      terminal.operationBytes(),
                      terminal.canonicalBytes(),
                      OffsetDateTime.parse("2026-10-07T00:00:00Z"));
              OwnerResultSnapshot prior = stored.putIfAbsent(owner, result);
              return prior == null ? result : prior;
            });
    when(gameDesignClient.read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideOwnerTransaction(manager);
              var request =
                  (GameDesignPublicationTerminalReadEvidence.ReadRequest) invocation.getArgument(0);
              assertThat(request.targetNamespace()).isEqualTo(NAMESPACE);
              assertThat(request.operationBytes())
                  .containsExactly(fixture.operation().canonicalBytes());
              return new GameDesignPublicationTerminalReadEvidence.ReadResult(
                  request,
                  GameDesignPublicationTerminalReadEvidence.Status.NO_PUBLICATION,
                  Optional.of(fixture.noPublicationTerminal()));
            });
    when(worldClient.read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideOwnerTransaction(manager);
              var request =
                  (WorldPublicationTerminalReadEvidence.Request) invocation.getArgument(0);
              assertThat(request.targetNamespace()).isEqualTo(NAMESPACE);
              assertThat(request.expectedTerminalEvidenceBytes())
                  .containsExactly(fixture.noPublicationTerminal().canonicalBytes());
              return new WorldPublicationTerminalReadEvidence.ReadResult(
                  request,
                  WorldPublicationTerminalReadEvidence.Status.ABORTED,
                  Optional.of(fixture.noPublicationTerminal()));
            });

    var service =
        new AccountPublicationTerminalReconciliationService(
            repository, manager, gameDesignClient, worldClient, NAMESPACE);
    assertThat(service.reconcile(fixture.operation().canonicalBytes()))
        .contains(Settlement.NO_PUBLICATION);
    assertThat(stored).containsKeys(Owner.GAME_DESIGN, Owner.WORLD);
    assertThat(stored.get(Owner.GAME_DESIGN).terminalBytes())
        .containsExactly(fixture.noPublicationTerminal().canonicalBytes());
    assertThat(stored.get(Owner.WORLD).terminalBytes())
        .containsExactly(fixture.noPublicationTerminal().canonicalBytes());
    assertThat(manager.commits).isEqualTo(2);
    verify(gameDesignClient).read(any());
    verify(worldClient).read(any());
    verify(repository)
        .recordOwnerResult(any(), org.mockito.ArgumentMatchers.eq(Owner.GAME_DESIGN), any());
    verify(repository)
        .recordOwnerResult(any(), org.mockito.ArgumentMatchers.eq(Owner.WORLD), any());
  }

  @Test
  void gameDesignUnknownNeverFabricatesWorldRequestOrOwnerResult() throws Exception {
    var fixture = AccountPublicationTerminalFixtures.scenario();
    var repository = mock(PublicationAuthorizationFenceRepository.class);
    var gameDesignClient = mock(GameDesignPublicationTerminalClient.class);
    var worldClient = mock(WorldPublicationTerminalClient.class);
    var manager = new TestTransactionManager();
    when(repository.readOriginalOperation(any(GameDesignPublicationOperationBinding.class)))
        .thenAnswer(
            invocation -> {
              assertInsideOwnerTransaction();
              return Optional.of(
                  new TerminalSnapshot(
                      PublicationAuthorizationFenceRepository.Ordering.PUBLICATION_ORDER,
                      Settlement.PENDING,
                      Optional.empty(),
                      Optional.empty()));
            });
    when(repository.readSettlement(any(GameDesignPublicationOperationBinding.class)))
        .thenReturn(Settlement.PENDING);
    when(gameDesignClient.read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideOwnerTransaction(manager);
              var request =
                  (GameDesignPublicationTerminalReadEvidence.ReadRequest) invocation.getArgument(0);
              return new GameDesignPublicationTerminalReadEvidence.ReadResult(
                  request,
                  GameDesignPublicationTerminalReadEvidence.Status.UNKNOWN,
                  Optional.empty());
            });
    var service =
        new AccountPublicationTerminalReconciliationService(
            repository, manager, gameDesignClient, worldClient, NAMESPACE);

    assertThat(service.reconcile(fixture.operation().canonicalBytes()))
        .contains(Settlement.PENDING);
    verify(gameDesignClient).read(any());
    verifyNoInteractions(worldClient);
    verify(repository, never()).recordOwnerResult(any(), any(), any());
    assertThat(manager.commits).isEqualTo(2);
  }

  @Test
  void ambientOwnerTransactionRejectsRecoveryBeforeRepositoryOrClientUse() throws Exception {
    var fixture = AccountPublicationTerminalFixtures.scenario();
    var repository = mock(PublicationAuthorizationFenceRepository.class);
    var gameDesignClient = mock(GameDesignPublicationTerminalClient.class);
    var worldClient = mock(WorldPublicationTerminalClient.class);
    var manager = new TestTransactionManager();
    var service =
        new AccountPublicationTerminalReconciliationService(
            repository, manager, gameDesignClient, worldClient, NAMESPACE);
    var ambient = new TransactionTemplate(manager);
    ambient.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ambient.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    ambient.execute(
        status -> {
          assertThatThrownBy(() -> service.reconcile(fixture.operation().canonicalBytes()))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("ambient transaction");
          verifyNoInteractions(repository, gameDesignClient, worldClient);
          return null;
        });
  }

  private static TerminalSnapshot snapshot(Map<Owner, OwnerResultSnapshot> stored) {
    return new TerminalSnapshot(
        PublicationAuthorizationFenceRepository.Ordering.PUBLICATION_ORDER,
        Settlement.PENDING,
        Optional.ofNullable(stored.get(Owner.GAME_DESIGN)),
        Optional.ofNullable(stored.get(Owner.WORLD)));
  }

  private static void assertInsideOwnerTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
  }

  private static void assertOutsideOwnerTransaction(TestTransactionManager manager) {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    assertThat(manager.commits).isGreaterThanOrEqualTo(1);
  }

  private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
    private int commits;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
      return false;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      assertThat(definition.getIsolationLevel())
          .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
      assertThat(definition.getPropagationBehavior())
          .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      assertThat(definition.isReadOnly()).isFalse();
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commits++;
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
