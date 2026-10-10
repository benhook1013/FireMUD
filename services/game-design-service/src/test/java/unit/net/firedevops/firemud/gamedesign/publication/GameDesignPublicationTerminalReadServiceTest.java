package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.service.impl.PublishedWorldSelectorFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Owner-read units use stipulated workload context, upstream operation fixtures and mocked
 * repository/transactions. They do not prove authenticated transport, physical PostgreSQL backing
 * or source settlement. Complete terminal parsing uses the production evidence codec.
 */
class GameDesignPublicationTerminalReadServiceTest {
  private final GameDesignPublicationOperationRepository repository =
      mock(GameDesignPublicationOperationRepository.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
  private final GameDesignPublicationTerminalReadService service =
      new GameDesignPublicationTerminalReadService(repository, transactions, "test");

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void authenticatesBeforeDecodingOrStorage() {
    assertCode(Status.Code.UNAUTHENTICATED, () -> service.read(null, null));
    for (String workload : List.of("game-design-service", "game-session-service")) {
      assertCode(
          Status.Code.PERMISSION_DENIED,
          () -> asPeer("test", workload, () -> service.read(null, null)));
    }
    for (String workload : List.of("account-service", "world-management-service")) {
      assertCode(
          Status.Code.PERMISSION_DENIED,
          () -> asPeer("other", workload, () -> service.read(null, null)));
    }
    verifyNoInteractions(repository, transactions);
  }

  @Test
  void rejectsNamespaceAmbientTransactionSynchronizationAndMalformedOperationBeforeStorage() {
    assertCode(Status.Code.PERMISSION_DENIED, () -> authorized(() -> service.read("other", null)));
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertCode(Status.Code.FAILED_PRECONDITION, () -> authorized(() -> service.read("test", null)));
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.initSynchronization();
    assertCode(Status.Code.FAILED_PRECONDITION, () -> authorized(() -> service.read("test", null)));
    TransactionSynchronizationManager.clearSynchronization();
    assertCode(
        Status.Code.INVALID_ARGUMENT, () -> authorized(() -> service.read("test", new byte[] {1})));
    verifyNoInteractions(repository, transactions);
  }

  @Test
  void returnsExactCompleteTerminalBytesOnlyAfterDedicatedReadOnlyRepeatableReadSnapshot()
      throws Exception {
    var operation = operation();
    when(transactions.getTransaction(any())).thenReturn(transactionStatus);
    for (Outcome outcome : Outcome.values()) {
      var terminal = terminal(operation, outcome);
      when(repository.read(operation.workflowId()))
          .thenReturn(
              Optional.of(
                  new GameDesignPublicationOperationRepository.Readback(
                      operation, outcome.name(), new byte[] {1}, terminal.canonicalBytes())));
      for (String workload : List.of("account-service", "world-management-service")) {
        var returned =
            asPeer("test", workload, () -> service.read("test", operation.canonicalBytes()));
        assertThat(returned.canonicalBytes()).containsExactly(terminal.canonicalBytes());
        assertThat(returned.operationBytes()).containsExactly(operation.canonicalBytes());
        assertThat(returned.outcome()).isEqualTo(outcome);
      }
    }
    var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
    var order = inOrder(transactions, repository);
    for (int i = 0; i < 4; i++) {
      order.verify(transactions).getTransaction(definition.capture());
      order.verify(repository).read(operation.workflowId());
      order.verify(transactions).commit(transactionStatus);
    }
    for (var selected : definition.getAllValues()) {
      assertThat(selected.getPropagationBehavior())
          .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      assertThat(selected.getIsolationLevel())
          .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
      assertThat(selected.isReadOnly()).isTrue();
    }
  }

  @Test
  void missingPendingLegacyAndChangedOperationCannotProduceTerminalEvidence() throws Exception {
    var operation = operation();
    var changed = operation();
    var noPublication = terminal(operation, Outcome.NO_PUBLICATION);
    when(transactions.getTransaction(any())).thenReturn(transactionStatus);
    when(repository.read(operation.workflowId())).thenReturn(Optional.empty());
    assertCode(
        Status.Code.NOT_FOUND,
        () -> authorized(() -> service.read("test", operation.canonicalBytes())));
    for (var stored :
        List.of(
            new GameDesignPublicationOperationRepository.Readback(operation, "PENDING", null, null),
            new GameDesignPublicationOperationRepository.Readback(
                operation, "NO_PUBLICATION", new byte[] {1}),
            new GameDesignPublicationOperationRepository.Readback(
                operation, "PUBLISHED", new byte[] {1}),
            new GameDesignPublicationOperationRepository.Readback(
                changed,
                "NO_PUBLICATION",
                new byte[] {1},
                terminal(changed, Outcome.NO_PUBLICATION).canonicalBytes()),
            new GameDesignPublicationOperationRepository.Readback(
                operation, "PUBLISHED", new byte[] {1}, noPublication.canonicalBytes()),
            new GameDesignPublicationOperationRepository.Readback(
                operation,
                "NO_PUBLICATION",
                new byte[] {1},
                terminal(changed, Outcome.NO_PUBLICATION).canonicalBytes()))) {
      when(repository.read(operation.workflowId())).thenReturn(Optional.of(stored));
      assertCode(
          Status.Code.FAILED_PRECONDITION,
          () -> authorized(() -> service.read("test", operation.canonicalBytes())));
    }
  }

  @Test
  void corruptTerminalAndOwnerBackingFailuresFailClosed() throws Exception {
    var operation = operation();
    when(transactions.getTransaction(any())).thenReturn(transactionStatus);
    when(repository.read(operation.workflowId()))
        .thenReturn(
            Optional.of(
                new GameDesignPublicationOperationRepository.Readback(
                    operation, "PUBLISHED", new byte[] {1}, new byte[] {1, 2})));
    assertCode(
        Status.Code.UNAVAILABLE,
        () -> authorized(() -> service.read("test", operation.canonicalBytes())));
    // The real repository owns immutable complete release/attempt comparison.
    for (RuntimeException failure :
        List.of(
            new IllegalStateException("PUBLICATION_TERMINAL_BACKING_CONFLICT"),
            new IllegalStateException("PUBLICATION_RECEIPT_ATTEMPT_CONFLICT"),
            new org.jooq.exception.DataAccessException("storage unavailable"))) {
      doThrow(failure).when(repository).read(operation.workflowId());
      assertCode(
          Status.Code.UNAVAILABLE,
          () -> authorized(() -> service.read("test", operation.canonicalBytes())));
    }
  }

  private static GameDesignPublicationOperation operation() throws Exception {
    return IsolatedPublicationOperationFixtures.fresh(
        new TargetProof(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            19L,
            "tenant-key",
            42L,
            "tenant-key",
            "NEW_GAME_ROW"));
  }

  private static GameDesignPublicationTerminalEvidence terminal(
      GameDesignPublicationOperation operation, Outcome outcome) {
    if (outcome == Outcome.NO_PUBLICATION) {
      return new GameDesignPublicationTerminalEvidence(
          operation.canonicalBytes(), outcome, null, null);
    }
    var participants =
        PublishedWorldSelectorFixtures.participants(operation.versionId(), operation.world())
            .stream()
            .map(
                p ->
                    new GameDesignPublicationTerminalEvidence.Participant(
                        p.participantKey(),
                        p.scopeValue(),
                        p.baseVersionId(),
                        p.appliedCommitId(),
                        p.contentDigest(),
                        p.digestSchemaVersion(),
                        p.abilitySchemaDigest(),
                        p.errorCode(),
                        p.errorMessage()))
            .toList();
    var selection = operation.account().input().selection();
    var release =
        new GameDesignPublicationTerminalEvidence.ReleaseContent(
            selection.intent().canonicalTenantId(),
            selection.intent().canonicalVersionId(),
            "retained-bundle",
            1,
            "v2",
            operation.workflowId(),
            "sha256:" + "a".repeat(64),
            1,
            List.of(),
            List.of(),
            participants,
            List.of(),
            "generation-1",
            operation.world());
    return new GameDesignPublicationTerminalEvidence(
        operation.canonicalBytes(),
        outcome,
        release,
        Math.addExact(operation.world().request().versionStateEpoch(), 1L));
  }

  private static <T> T authorized(Supplier<T> action) {
    return asPeer("test", "account-service", action);
  }

  private static <T> T asPeer(String namespace, String workload, Supplier<T> action) {
    var peer =
        GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + workload)
            .orElseThrow();
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context prior = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(prior);
    }
  }

  private static void assertCode(Status.Code expected, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected));
  }
}
