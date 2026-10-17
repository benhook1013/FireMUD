package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionActivationProtectionRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionActivationProtectionRepository.StoredProtection;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionActivationProtectionRepository.StoredSettlement;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionActivationProtectionSettlementService;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionRequest;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionSettlementInput;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalEvidence;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalReadClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Historical Account owner lookup stays separate from World terminal authority. */
class AccountStartSessionActivationProtectionSettlementServiceTest {
  private static final String NAMESPACE = "world-runtime";

  @AfterEach
  void clearThreadState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void requiresExactWorldPeerAndNoEndUserBeforeInspectingCoordinates() {
    var repository = mock(AccountStartSessionActivationProtectionRepository.class);
    var world = mock(WorldOriginalStartSessionActivationTerminalReadClient.class);
    var service =
        new AccountStartSessionActivationProtectionSettlementService(
            repository, world, new OwnerTransactions(), NAMESPACE);

    assertCode(Status.Code.UNAUTHENTICATED, () -> service.settle(null));
    withPeer(
        "game-session-service",
        () -> assertCode(Status.Code.PERMISSION_DENIED, () -> service.settle(null)));
    SessionContext.setContext("123", java.util.List.of(), java.util.Map.of());
    withWorldPeer(() -> assertCode(Status.Code.PERMISSION_DENIED, () -> service.settle(null)));

    verifyNoInteractions(repository, world);
  }

  @Test
  void deniesUnavailableHistoricalProtectionBeforeRemoteWorldRead() {
    var repository = mock(AccountStartSessionActivationProtectionRepository.class);
    var world = mock(WorldOriginalStartSessionActivationTerminalReadClient.class);
    var transactions = new OwnerTransactions();
    var service =
        new AccountStartSessionActivationProtectionSettlementService(
            repository, world, transactions, NAMESPACE);
    var input =
        new AccountStartSessionActivationProtectionSettlementInput(
            NAMESPACE,
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            7L);
    when(repository.findHistoricalExact(
            input.accountProtectionId(), input.accountProtectionFence()))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                  .isFalse();
              return Optional.empty();
            });

    withWorldPeer(() -> assertCode(Status.Code.FAILED_PRECONDITION, () -> service.settle(input)));

    assertThat(transactions.begins).isEqualTo(1);
    assertThat(transactions.commits).isZero();
    assertThat(transactions.rollbacks).isEqualTo(1);
    verify(repository)
        .findHistoricalExact(input.accountProtectionId(), input.accountProtectionFence());
    verifyNoInteractions(world);
  }

  @Test
  void rejectsAmbientTransactionBeforeOwnerLookup() {
    var repository = mock(AccountStartSessionActivationProtectionRepository.class);
    var world = mock(WorldOriginalStartSessionActivationTerminalReadClient.class);
    var service =
        new AccountStartSessionActivationProtectionSettlementService(
            repository, world, new OwnerTransactions(), NAMESPACE);
    var input =
        new AccountStartSessionActivationProtectionSettlementInput(
            NAMESPACE, UUID.randomUUID(), UUID.randomUUID(), 1L);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    withWorldPeer(() -> assertCode(Status.Code.FAILED_PRECONDITION, () -> service.settle(input)));

    verifyNoInteractions(repository, world);
  }

  @Test
  void exactHistoricalSettlementRetryReturnsStoredReceiptWithoutRecheckingExpiryOrWorld() {
    var repository = mock(AccountStartSessionActivationProtectionRepository.class);
    var world = mock(WorldOriginalStartSessionActivationTerminalReadClient.class);
    var transactions = new OwnerTransactions();
    var service =
        new AccountStartSessionActivationProtectionSettlementService(
            repository, world, transactions, NAMESPACE);
    UUID protectionId = UUID.fromString("33333333-3333-4333-8333-333333333333");
    UUID retryRequestId = UUID.fromString("44444444-4444-4444-8444-444444444444");
    var input =
        new AccountStartSessionActivationProtectionSettlementInput(
            NAMESPACE, retryRequestId, protectionId, 19L);
    var protectionEvidence = mock(AccountStartSessionActivationProtectionEvidence.class);
    var protectionRequest = mock(AccountStartSessionActivationProtectionRequest.class);
    var tuple = mock(StartSessionPostAuthorizationExecutionTuple.class);
    var preTuple = mock(StartSessionPreAuthorizationReservationTuple.class);
    var action = mock(StartSessionOperatorAction.class);
    var scope = mock(StartSessionOperatorAction.Scope.class);
    when(protectionEvidence.accountProtectionId()).thenReturn(protectionId);
    when(protectionEvidence.accountProtectionFence()).thenReturn(19L);
    when(protectionEvidence.request()).thenReturn(protectionRequest);
    when(protectionRequest.originalTuple()).thenReturn(tuple);
    when(tuple.preAuthorizationTuple()).thenReturn(preTuple);
    when(preTuple.action()).thenReturn(action);
    when(action.scope()).thenReturn(scope);
    when(scope.targetNamespace()).thenReturn(NAMESPACE);
    var terminal = mock(WorldOriginalStartSessionActivationTerminalEvidence.class);
    when(terminal.outcome()).thenReturn(WorldCanonicalInstanceActivation.Outcome.COMMITTED);
    var protection = new StoredProtection(protectionEvidence, 23L);
    var settledAt = java.time.Instant.parse("2026-10-15T01:02:03.123456Z");
    var settlement =
        new StoredSettlement(terminal, settledAt, new byte[] {1}, "sha256:" + "a".repeat(64));
    when(repository.findHistoricalExact(protectionId, 19L)).thenReturn(Optional.of(protection));
    when(repository.findSettlementExact(protectionId, 19L)).thenReturn(Optional.of(settlement));

    var response = withWorldPeerResult(() -> service.settle(input));

    assertThat(response.settlementRequestId()).isEqualTo(retryRequestId);
    assertThat(response.terminal()).isSameAs(terminal);
    assertThat(response.settledAt()).isEqualTo(settledAt);
    assertThat(transactions.begins).isEqualTo(1);
    verify(repository).findHistoricalExact(protectionId, 19L);
    verify(repository).findSettlementExact(protectionId, 19L);
    verify(repository, never()).settleExact(org.mockito.ArgumentMatchers.any());
    verifyNoInteractions(world);
  }

  @Test
  void foreignWorldTerminalStatusesAreClassifiedBeforeSettlementMutation() {
    var preserved = infrastructureCodes();
    for (Status.Code sourceCode : Status.Code.values()) {
      var repository = mock(AccountStartSessionActivationProtectionRepository.class);
      var world = mock(WorldOriginalStartSessionActivationTerminalReadClient.class);
      var transactions = new OwnerTransactions();
      var service =
          new AccountStartSessionActivationProtectionSettlementService(
              repository, world, transactions, NAMESPACE);
      var input =
          new AccountStartSessionActivationProtectionSettlementInput(
              NAMESPACE,
              UUID.fromString("11111111-1111-4111-8111-111111111111"),
              UUID.fromString("22222222-2222-4222-8222-222222222222"),
              7L);
      var protectionEvidence = mock(AccountStartSessionActivationProtectionEvidence.class);
      var protectionRequest = mock(AccountStartSessionActivationProtectionRequest.class);
      var tuple = mock(StartSessionPostAuthorizationExecutionTuple.class);
      var preTuple = mock(StartSessionPreAuthorizationReservationTuple.class);
      var action = mock(StartSessionOperatorAction.class);
      var scope = mock(StartSessionOperatorAction.Scope.class);
      when(protectionEvidence.accountProtectionId()).thenReturn(input.accountProtectionId());
      when(protectionEvidence.accountProtectionFence()).thenReturn(input.accountProtectionFence());
      when(protectionEvidence.request()).thenReturn(protectionRequest);
      when(protectionRequest.originalTuple()).thenReturn(tuple);
      when(tuple.preAuthorizationTuple()).thenReturn(preTuple);
      when(preTuple.action()).thenReturn(action);
      when(action.scope()).thenReturn(scope);
      when(scope.targetNamespace()).thenReturn(NAMESPACE);
      when(protectionRequest.worldActivationRequest())
          .thenReturn(mock(WorldCanonicalInstanceActivation.Request.class));
      var protection = new StoredProtection(protectionEvidence, 1L);
      when(repository.findHistoricalExact(
              input.accountProtectionId(), input.accountProtectionFence()))
          .thenReturn(Optional.of(protection));
      when(repository.findSettlementExact(
              input.accountProtectionId(), input.accountProtectionFence()))
          .thenReturn(Optional.empty());
      org.mockito.Mockito.doThrow(foreignFailure(sourceCode)).when(world).read(any());

      var failure = statusFailure(() -> withWorldPeer(() -> service.settle(input)));

      assertDependencyFailure(
          sourceCode, preserved, "Account World activation-terminal dependency failed", failure);
      verify(world).read(any());
      verify(repository, never()).settleExact(any());
      assertThat(transactions.begins).isEqualTo(1);
      assertThat(transactions.commits).isEqualTo(1);
      assertThat(transactions.rollbacks).isZero();
    }
  }

  private static Set<Status.Code> infrastructureCodes() {
    return Set.of(
        Status.Code.UNAVAILABLE,
        Status.Code.DEADLINE_EXCEEDED,
        Status.Code.CANCELLED,
        Status.Code.RESOURCE_EXHAUSTED,
        Status.Code.INTERNAL,
        Status.Code.UNKNOWN);
  }

  private static StatusRuntimeException foreignFailure(Status.Code code) {
    var trailers = new Metadata();
    trailers.put(
        Metadata.Key.of("private-owner-detail", Metadata.ASCII_STRING_MARSHALLER),
        "private downstream trailer");
    return Status.fromCode(code)
        .withDescription("private downstream description")
        .withCause(new IllegalStateException("private downstream cause"))
        .asRuntimeException(trailers);
  }

  private static StatusRuntimeException statusFailure(Runnable work) {
    var thrown = org.assertj.core.api.Assertions.catchThrowable(work::run);
    assertThat(thrown).isInstanceOf(StatusRuntimeException.class);
    return (StatusRuntimeException) thrown;
  }

  private static void assertDependencyFailure(
      Status.Code sourceCode,
      Set<Status.Code> preserved,
      String expectedDescription,
      StatusRuntimeException failure) {
    Status.Code expectedCode =
        preserved.contains(sourceCode) ? sourceCode : Status.Code.FAILED_PRECONDITION;
    assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expectedCode);
    assertThat(Status.fromThrowable(failure).getDescription()).isEqualTo(expectedDescription);
    assertThat(failure.getCause()).isNull();
    assertThat(failure.getTrailers()).isNull();
  }

  private static void assertCode(Status.Code expected, Runnable work) {
    assertThatThrownBy(work::run)
        .isInstanceOf(io.grpc.StatusRuntimeException.class)
        .satisfies(
            failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected));
  }

  private static void withWorldPeer(Runnable work) {
    withPeer("world-management-service", work);
  }

  private static <T> T withWorldPeerResult(java.util.function.Supplier<T> work) {
    var identity =
        GrpcPeerIdentity.parseUri("spiffe://firemud/ns/world-runtime/sa/world-management-service")
            .orElseThrow();
    var next = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, identity);
    var prior = next.attach();
    try {
      return work.get();
    } finally {
      next.detach(prior);
    }
  }

  private static void withPeer(String service, Runnable work) {
    var identity =
        GrpcPeerIdentity.parseUri("spiffe://firemud/ns/world-runtime/sa/" + service).orElseThrow();
    var next = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, identity);
    var prior = next.attach();
    try {
      work.run();
    } finally {
      next.detach(prior);
    }
  }

  private static final class OwnerTransactions extends AbstractPlatformTransactionManager {
    private static final long serialVersionUID = 1L;
    int begins;
    int commits;
    int rollbacks;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      assertThat(definition.getPropagationBehavior())
          .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
    protected void doRollback(DefaultTransactionStatus status) {
      rollbacks++;
    }
  }
}
