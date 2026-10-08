package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationTerminalReadClient;
import net.firedevops.firemud.common.publication.GameDesignPublicationTerminalReadEvidence;
import net.firedevops.firemud.common.publication.WorldPublicationTerminalReadClient;
import net.firedevops.firemud.common.publication.WorldPublicationTerminalReadEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Composition seams only: canonical codec outputs, owner results and peer identity are stipulated
 * here. Actual PostgreSQL, codec integrity and mounted-certificate mTLS are separate proof.
 */
class AccountSelectedDraftPublicationSettlementServiceTest {
  private final AccountPublicationAuthorizationRepository repository =
      mock(AccountPublicationAuthorizationRepository.class);
  private final GameDesignPublicationTerminalReadClient gameDesign =
      mock(GameDesignPublicationTerminalReadClient.class);
  private final WorldPublicationTerminalReadClient world =
      mock(WorldPublicationTerminalReadClient.class);
  private final OwnerTransactions transactions = new OwnerTransactions();
  private final AccountSelectedDraftPublicationSettlementService service =
      new AccountSelectedDraftPublicationSettlementService(
          repository, gameDesign, world, transactions, "test");

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void authenticatesBeforeMalformedDecodeOrOwnerReads() {
    assertCode(Status.Code.UNAUTHENTICATED, () -> service.settle(null));
    for (String workload :
        List.of("account-service", "world-management-service", "game-session-service")) {
      assertCode(
          Status.Code.PERMISSION_DENIED,
          () -> peer("test", workload, () -> service.settle(new byte[] {0})));
    }
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () -> peer("other", "game-design-service", () -> service.settle(null)));
    assertThatThrownBy(() -> authorized(() -> service.settle(new byte[] {0})))
        .isInstanceOf(RuntimeException.class);
    verifyNoInteractions(repository, gameDesign, world);
    assertThat(transactions.begins).isZero();
  }

  @Test
  void deniesAmbientTransactionAndSynchronizationBeforeDecode() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertCode(Status.Code.FAILED_PRECONDITION, () -> authorized(() -> service.settle(null)));
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.initSynchronization();
    assertCode(Status.Code.FAILED_PRECONDITION, () -> authorized(() -> service.settle(null)));
    verifyNoInteractions(repository, gameDesign, world);
  }

  @Test
  void agreeingOwnersCommitThenSeparatelyReadBackAndRetriesUseFreshReadCorrelations() {
    for (var outcome : GameDesignPublicationTerminalEvidence.Outcome.values()) {
      try (var fixture = new CodecOutputs(outcome)) {
        var gameDesignReads = new ArrayList<UUID>();
        var worldReads = new ArrayList<UUID>();
        doAnswer(
                call -> {
                  assertNoSql();
                  var request =
                      call.getArgument(0, GameDesignPublicationTerminalReadEvidence.Request.class);
                  gameDesignReads.add(request.readRequestId());
                  return gameDesignEvidence(request, fixture.terminal);
                })
            .when(gameDesign)
            .read(any());
        doAnswer(
                call -> {
                  assertNoSql();
                  var request =
                      call.getArgument(0, WorldPublicationTerminalReadEvidence.Request.class);
                  worldReads.add(request.readRequestId());
                  return worldEvidence(request, fixture.terminal, request.expectedWorldOutcome());
                })
            .when(world)
            .read(any());
        var calls = new AtomicInteger();
        int priorCommits = transactions.commits;
        byte[] receipt = new byte[] {9, 8};
        doAnswer(
                call -> {
                  assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                      .isTrue();
                  assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
                  assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                      .isFalse();
                  assertThat(
                          TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                      .isEqualTo(java.sql.Connection.TRANSACTION_READ_COMMITTED);
                  assertThat(transactions.commits - priorCommits)
                      .isEqualTo(calls.getAndIncrement());
                  assertThat(call.getArgument(0, GameDesignPublicationOperationBinding.class))
                      .isSameAs(fixture.operation);
                  assertThat(call.getArgument(1, GameDesignPublicationTerminalEvidence.class))
                      .isSameAs(fixture.terminal);
                  assertThat(call.getArgument(2, String.class)).isEqualTo(fixture.phase.name());
                  assertThat(call.getArgument(3, byte[].class))
                      .containsExactly(fixture.terminalBytes);
                  return receipt.clone();
                })
            .when(repository)
            .settle(any(), any(), anyString(), any());
        assertThat(authorized(() -> service.settle(fixture.operationBytes)))
            .containsExactly(receipt);
        assertThat(authorized(() -> service.settle(fixture.operationBytes)))
            .containsExactly(receipt);
        assertThat(calls).hasValue(4);
        assertThat(transactions.commits - priorCommits).isEqualTo(4);
        assertThat(gameDesignReads).hasSize(2).doesNotHaveDuplicates();
        assertThat(worldReads).hasSize(2).doesNotHaveDuplicates();
        assertNoSql();
      }
    }
  }

  @Test
  void missingSubstitutedOrUnavailableGameDesignResultNeverReadsWorldOrWritesAccount() {
    try (var fixture =
        new CodecOutputs(GameDesignPublicationTerminalEvidence.Outcome.NO_PUBLICATION)) {
      when(gameDesign.read(any())).thenReturn(null);
      assertFailure(fixture);
      doAnswer(
              call -> {
                var request =
                    call.getArgument(0, GameDesignPublicationTerminalReadEvidence.Request.class);
                return gameDesignEvidence(
                    new GameDesignPublicationTerminalReadEvidence.Request(
                        1, "test", UUID.randomUUID(), request.originalOperation()),
                    fixture.terminal);
              })
          .when(gameDesign)
          .read(any());
      assertFailure(fixture);
      var substituted = mock(GameDesignPublicationTerminalEvidence.class);
      when(substituted.canonicalBytes()).thenReturn(new byte[] {99});
      doAnswer(call -> gameDesignEvidence(call.getArgument(0), substituted))
          .when(gameDesign)
          .read(any());
      assertFailure(fixture);
      doThrow(Status.UNAVAILABLE.asRuntimeException()).when(gameDesign).read(any());
      assertCode(
          Status.Code.UNAVAILABLE, () -> authorized(() -> service.settle(fixture.operationBytes)));
      verifyNoInteractions(world, repository);
      assertThat(transactions.begins).isZero();
    }
  }

  @Test
  void
      worldNamespaceMismatchMissingChangedRequestTerminalPhaseAndUnavailableKeepAccountUntouched() {
    try (var fixture =
        new CodecOutputs(GameDesignPublicationTerminalEvidence.Outcome.NO_PUBLICATION)) {
      when(fixture.worldRequest.targetNamespace()).thenReturn("other");
      assertThatThrownBy(() -> authorized(() -> service.settle(fixture.operationBytes)))
          .isInstanceOf(IllegalArgumentException.class);
      verifyNoInteractions(gameDesign, world, repository);
      when(fixture.worldRequest.targetNamespace()).thenReturn("test");
      when(gameDesign.read(any()))
          .thenAnswer(call -> gameDesignEvidence(call.getArgument(0), fixture.terminal));
      for (String changed :
          List.of("missing", "correlation", "namespace", "phase", "terminal", "operation")) {
        doAnswer(
                call -> {
                  var request =
                      call.getArgument(0, WorldPublicationTerminalReadEvidence.Request.class);
                  if (changed.equals("missing")) return null;
                  var response = worldEvidence(request, fixture.terminal, fixture.phase);
                  if (changed.equals("correlation") || changed.equals("namespace")) {
                    // Mock the returned request itself to exercise a substituted owner echo without
                    // treating a structurally invalid carrier as a genuine codec result.
                    when(response.request())
                        .thenReturn(mock(WorldPublicationTerminalReadEvidence.Request.class));
                  }
                  if (changed.equals("phase"))
                    when(response.worldOutcome())
                        .thenReturn(WorldPublicationTerminalReadEvidence.WorldOutcome.PUBLISHED);
                  if (changed.equals("terminal"))
                    when(response.worldTerminalEvidence()).thenReturn(new byte[] {99});
                  if (changed.equals("operation")) {
                    var other = mock(GameDesignPublicationTerminalEvidence.class);
                    when(other.operationBytes()).thenReturn(new byte[] {99});
                    when(response.terminalEvidence()).thenReturn(other);
                  }
                  return response;
                })
            .when(world)
            .read(any());
        assertFailure(fixture);
      }
      doThrow(Status.UNAVAILABLE.asRuntimeException()).when(world).read(any());
      assertCode(
          Status.Code.UNAVAILABLE, () -> authorized(() -> service.settle(fixture.operationBytes)));
      verifyNoInteractions(repository);
      assertThat(transactions.begins).isZero();
    }
  }

  @Test
  void changedPostCommitReadbackCannotReturnSuccess() {
    try (var fixture =
        new CodecOutputs(GameDesignPublicationTerminalEvidence.Outcome.NO_PUBLICATION)) {
      when(gameDesign.read(any()))
          .thenAnswer(call -> gameDesignEvidence(call.getArgument(0), fixture.terminal));
      when(world.read(any()))
          .thenAnswer(call -> worldEvidence(call.getArgument(0), fixture.terminal, fixture.phase));
      when(repository.settle(any(), any(), anyString(), any()))
          .thenReturn(new byte[] {1}, new byte[] {2});
      assertThatThrownBy(() -> authorized(() -> service.settle(fixture.operationBytes)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("readback differs");
      assertThat(transactions.commits).isEqualTo(2);
      assertNoSql();
    }
  }

  private void assertFailure(CodecOutputs fixture) {
    assertThatThrownBy(() -> authorized(() -> service.settle(fixture.operationBytes)))
        .isInstanceOf(RuntimeException.class);
  }

  private static GameDesignPublicationTerminalReadEvidence gameDesignEvidence(
      GameDesignPublicationTerminalReadEvidence.Request request,
      GameDesignPublicationTerminalEvidence terminal) {
    var result = mock(GameDesignPublicationTerminalReadEvidence.class);
    when(result.request()).thenReturn(request);
    when(result.terminalEvidence()).thenReturn(terminal);
    return result;
  }

  private static WorldPublicationTerminalReadEvidence worldEvidence(
      WorldPublicationTerminalReadEvidence.Request request,
      GameDesignPublicationTerminalEvidence terminal,
      WorldPublicationTerminalReadEvidence.WorldOutcome phase) {
    byte[] terminalBytes = terminal.canonicalBytes();
    var result = mock(WorldPublicationTerminalReadEvidence.class);
    when(result.request()).thenReturn(request);
    when(result.terminalEvidence()).thenReturn(terminal);
    when(result.worldTerminalEvidence()).thenReturn(terminalBytes);
    when(result.worldOutcome()).thenReturn(phase);
    return result;
  }

  private static void assertNoSql() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
  }

  private static <T> T authorized(Supplier<T> action) {
    return peer("test", "game-design-service", action);
  }

  private static <T> T peer(String namespace, String workload, Supplier<T> action) {
    var context =
        Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + workload)
                    .orElseThrow());
    var prior = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(prior);
    }
  }

  private static void assertCode(Status.Code code, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(io.grpc.StatusRuntimeException.class)
        .satisfies(failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(code));
  }

  private static final class CodecOutputs implements AutoCloseable {
    final byte[] operationBytes = new byte[] {11};
    final byte[] terminalBytes = new byte[] {22};
    final GameDesignPublicationOperationBinding operation =
        mock(GameDesignPublicationOperationBinding.class);
    final GameDesignPublicationTerminalEvidence terminal =
        mock(GameDesignPublicationTerminalEvidence.class);
    final WorldPublishedStartLocationEvidence.Request worldRequest =
        mock(WorldPublishedStartLocationEvidence.Request.class);
    final WorldPublicationTerminalReadEvidence.WorldOutcome phase;
    final org.mockito.MockedStatic<GameDesignPublicationOperationBinding> operations =
        mockStatic(GameDesignPublicationOperationBinding.class);
    final org.mockito.MockedStatic<GameDesignPublicationTerminalEvidence> terminals =
        mockStatic(GameDesignPublicationTerminalEvidence.class);

    CodecOutputs(GameDesignPublicationTerminalEvidence.Outcome outcome) {
      var world = mock(WorldPublishedStartLocationEvidence.class);
      when(operation.canonicalBytes()).thenReturn(operationBytes);
      when(operation.world()).thenReturn(world);
      when(world.request()).thenReturn(worldRequest);
      when(worldRequest.targetNamespace()).thenReturn("test");
      when(terminal.canonicalBytes()).thenReturn(terminalBytes);
      when(terminal.operationBytes()).thenReturn(operationBytes);
      when(terminal.outcome()).thenReturn(outcome);
      operations
          .when(() -> GameDesignPublicationOperationBinding.fromStored(operationBytes))
          .thenReturn(operation);
      terminals
          .when(() -> GameDesignPublicationTerminalEvidence.fromStored(terminalBytes))
          .thenReturn(terminal);
      phase =
          outcome == GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
              ? WorldPublicationTerminalReadEvidence.WorldOutcome.PUBLISHED
              : WorldPublicationTerminalReadEvidence.WorldOutcome.ABORTED;
    }

    @Override
    public void close() {
      terminals.close();
      operations.close();
    }
  }

  private static final class OwnerTransactions extends AbstractPlatformTransactionManager {
    private static final long serialVersionUID = 1L;
    int begins;
    int commits;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      assertThat(definition.getIsolationLevel())
          .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
      assertThat(definition.isReadOnly()).isFalse();
      begins++;
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commits++;
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
