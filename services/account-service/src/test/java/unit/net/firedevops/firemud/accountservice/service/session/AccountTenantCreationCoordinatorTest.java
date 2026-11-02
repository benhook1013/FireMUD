package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.GameDesignAuthorizedTenantCreationClient;
import net.firedevops.firemud.accountservice.client.GameDesignOriginalTenantCreationOutcomeClient;
import net.firedevops.firemud.accountservice.client.GameDesignOriginalTenantCreationOutcomeClient.VerifiedTerminal;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/** Controlled owner orchestration; no live Redis, GD or Account authority proof. */
class AccountTenantCreationCoordinatorTest {
  @Test
  void initialSendFollowsDurableClaimAndCompletesOnlyThroughIndependentPrivateTerminal() {
    var f = new Fixture();
    assertThat(f.start().state()).isEqualTo(AccountTenantCreationCoordinator.State.COMPLETED);
    var order = Mockito.inOrder(f.admission, f.dispatches, f.creation, f.outcomes, f.completion);
    order.verify(f.admission).start("jwt", f.request, f.recent, f.environment);
    order.verify(f.dispatches).claim(f.prepared, f.environment, f.recent);
    order.verify(f.creation).create(f.call, f.binding, "fresh", "Game", null, Fixture.DEADLINE);
    order.verify(f.outcomes).readOriginal(f.binding);
    order.verify(f.completion).complete(f.operation, f.terminal, f.environment, f.recent);
    verify(f.creation, never()).closeOriginal(any());
    verify(f.dispatches, never()).isDispatchWindowClosed(any());
  }

  @Test
  void lostCreateReplyIsReadBackWithoutAnotherSend() {
    var f = new Fixture();
    when(f.creation.create(any(), any(), any(), any(), any(), Mockito.anyLong()))
        .thenThrow(Status.UNAVAILABLE.asRuntimeException());
    assertThat(f.start().state()).isEqualTo(AccountTenantCreationCoordinator.State.COMPLETED);
    verify(f.creation).create(f.call, f.binding, "fresh", "Game", null, Fixture.DEADLINE);
    verify(f.outcomes).readOriginal(f.binding);
  }

  @Test
  void exactRetryWithCreatedTerminalResolvesImmediatelyWithoutWaitingForDispatchCutoff() {
    var f = new Fixture();
    when(f.dispatches.hasClaim(f.operation)).thenReturn(true);
    assertThat(f.start().state()).isEqualTo(AccountTenantCreationCoordinator.State.COMPLETED);
    assertThat(f.coordinator.recover(f.operation, f.recent, f.environment).state())
        .isEqualTo(AccountTenantCreationCoordinator.State.COMPLETED);
    verify(f.outcomes, Mockito.times(2)).readOriginal(f.binding);
    verify(f.completion, Mockito.times(2))
        .complete(f.operation, f.terminal, f.environment, f.recent);
    verify(f.dispatches, never()).isDispatchWindowClosed(any());
    verify(f.dispatches, never()).claim(any(), any(), any());
    verifyNoInteractions(f.creation, f.admission, f.orders);
  }

  @Test
  void exactCreatedHistoricalRecoveryIsDistinctFromAccountCompletionAndNeverResends() {
    var f = new Fixture();
    when(f.dispatches.hasClaim(f.operation)).thenReturn(true);
    when(f.completed.state())
        .thenReturn(AccountTenantCreationCompletionOwner.State.CREATED_WITHOUT_ACCOUNT_COMPLETION);
    byte[] history = f.terminal.evidence().canonicalBytes();
    String historyDigest = f.terminal.evidence().evidenceDigest();
    when(f.completed.payload()).thenReturn(history);
    when(f.completed.digest()).thenReturn(historyDigest);
    var first = f.start();
    var replay = f.coordinator.recover(f.operation, f.recent, f.environment);
    assertThat(first.state())
        .isEqualTo(AccountTenantCreationCoordinator.State.CREATED_WITHOUT_ACCOUNT_COMPLETION);
    assertThat(replay.state()).isEqualTo(first.state());
    assertThat(replay.payload()).isEqualTo(history);
    assertThat(replay.digest()).isEqualTo(historyDigest);
    verify(f.dispatches, never()).claim(any(), any(), any());
    verify(f.dispatches, never()).isDispatchWindowClosed(any());
    verifyNoInteractions(f.creation, f.admission, f.orders);
  }

  @Test
  void exactConcurrentRetryBeforeDispatchDeadlineNeverClosesReadmitsOrRedispatches() {
    var f = new Fixture();
    when(f.dispatches.hasClaim(f.operation)).thenReturn(true);
    when(f.outcomes.readOriginal(f.binding)).thenThrow(Status.NOT_FOUND.asRuntimeException());
    assertThat(f.start().state()).isEqualTo(AccountTenantCreationCoordinator.State.PENDING);
    verifyNoInteractions(f.admission, f.completion);
    verify(f.dispatches).isDispatchWindowClosed(f.original);
    verifyNoInteractions(f.creation, f.orders);
    verify(f.creation, never()).create(any(), any(), any(), any(), any(), Mockito.anyLong());
    verify(f.dispatches, never()).claim(any(), any(), any());
  }

  @Test
  void lostCreateReplyAndAbsentOrNonterminalHistoryBeforeDeadlineRetainsPendingOriginal() {
    for (Status status : new Status[] {Status.NOT_FOUND, Status.FAILED_PRECONDITION}) {
      var f = new Fixture();
      when(f.creation.create(any(), any(), any(), any(), any(), Mockito.anyLong()))
          .thenThrow(Status.DEADLINE_EXCEEDED.asRuntimeException());
      when(f.outcomes.readOriginal(f.binding)).thenThrow(status.asRuntimeException());
      assertThat(f.start().state()).isEqualTo(AccountTenantCreationCoordinator.State.PENDING);
      assertThat(f.coordinator.recover(f.operation, f.recent, f.environment).state())
          .isEqualTo(AccountTenantCreationCoordinator.State.PENDING);
      verify(f.creation).create(f.call, f.binding, "fresh", "Game", null, Fixture.DEADLINE);
      verify(f.creation, never()).closeOriginal(any());
      verifyNoInteractions(f.orders, f.completion);
    }
  }

  @Test
  void unknownContradictoryOrUnavailableDeadlineNeverClosesOrResends() {
    for (RuntimeException failure :
        new RuntimeException[] {
          new IllegalStateException("Unknown or contradictory retained claim"),
          new org.jooq.exception.DataAccessException("Clock read failed"),
          new org.springframework.transaction.CannotCreateTransactionException("SQL unavailable")
        }) {
      var f = new Fixture();
      when(f.outcomes.readOriginal(f.binding)).thenThrow(Status.NOT_FOUND.asRuntimeException());
      when(f.dispatches.isDispatchWindowClosed(f.original)).thenThrow(failure);
      assertThat(f.coordinator.recover(f.operation, f.recent, f.environment).state())
          .isEqualTo(AccountTenantCreationCoordinator.State.PENDING);
      verifyNoInteractions(f.creation, f.orders, f.completion, f.admission);
    }
  }

  @Test
  void concurrentClaimLoserOnlyReconcilesAndDoesNotInventNoCreate() {
    var f = new Fixture();
    when(f.dispatches.claim(f.prepared, f.environment, f.recent)).thenReturn(Optional.empty());
    when(f.outcomes.readOriginal(f.binding))
        .thenThrow(Status.DEADLINE_EXCEEDED.asRuntimeException());
    assertThat(f.start().state()).isEqualTo(AccountTenantCreationCoordinator.State.PENDING);
    verifyNoInteractions(f.creation, f.completion, f.orders);
  }

  @Test
  void definitiveNotCreatedSettlesOnlyExactOriginalWithCommittedReadback() {
    var f = new Fixture();
    var negative =
        new GameTenantCreationOriginalOutcomeEvidence(
            1,
            f.binding,
            GameTenantCreationOriginalOutcomeEvidence.Outcome.NOT_CREATED,
            Optional.empty());
    when(f.terminal.evidence()).thenReturn(negative);
    when(f.orders.readOriginal(f.operation)).thenReturn(Optional.of(f.holder));
    when(f.orders.readSettlement(f.operation)).thenReturn(Optional.of(negative));
    assertThat(f.coordinator.recover(f.operation, f.recent, f.environment).state())
        .isEqualTo(AccountTenantCreationCoordinator.State.NOT_CREATED);
    verify(f.orders).settleLocked(f.holder, f.terminal);
    verify(f.orders).readSettlement(f.operation);
    verifyNoInteractions(f.admission, f.creation, f.completion);
    verify(f.dispatches, never()).isDispatchWindowClosed(any());
  }

  @Test
  void lateCreatedCannotBypassFinalAuthorityAndDoesNotBecomeNegativeSettlement() {
    var f = new Fixture();
    when(f.completion.complete(f.operation, f.terminal, f.environment, f.recent))
        .thenThrow(new IllegalStateException("Final authority expired"));
    assertThatThrownBy(() -> f.coordinator.recover(f.operation, f.recent, f.environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Final authority expired");
    verifyNoInteractions(f.admission, f.creation, f.orders);
  }

  @Test
  void absentOriginalExplicitlyClosesThenSettlesOnlyPrivateTerminalReadback() {
    var f = new Fixture();
    var negative =
        new GameTenantCreationOriginalOutcomeEvidence(
            1,
            f.binding,
            GameTenantCreationOriginalOutcomeEvidence.Outcome.NOT_CREATED,
            Optional.empty());
    when(f.terminal.evidence()).thenReturn(negative);
    when(f.outcomes.readOriginal(f.binding))
        .thenThrow(Status.NOT_FOUND.asRuntimeException())
        .thenReturn(f.terminal);
    when(f.dispatches.isDispatchWindowClosed(f.original)).thenReturn(true);
    when(f.orders.readOriginal(f.operation)).thenReturn(Optional.of(f.holder));
    when(f.orders.readSettlement(f.operation)).thenReturn(Optional.of(negative));
    assertThat(f.coordinator.recover(f.operation, f.recent, f.environment).state())
        .isEqualTo(AccountTenantCreationCoordinator.State.NOT_CREATED);
    var order = Mockito.inOrder(f.outcomes, f.dispatches, f.creation, f.orders);
    order.verify(f.outcomes).readOriginal(f.binding);
    order.verify(f.dispatches).isDispatchWindowClosed(f.original);
    order.verify(f.creation).closeOriginal(f.binding);
    order.verify(f.outcomes).readOriginal(f.binding);
    order.verify(f.orders).settleLocked(f.holder, f.terminal);
    verifyNoInteractions(f.admission, f.completion);
  }

  @Test
  void closureLostReplyAndCreationWinningBothUseIndependentTerminalInsteadOfAck() {
    for (Status failure : new Status[] {Status.DEADLINE_EXCEEDED, Status.FAILED_PRECONDITION}) {
      var f = new Fixture();
      when(f.outcomes.readOriginal(f.binding))
          .thenThrow(Status.FAILED_PRECONDITION.asRuntimeException())
          .thenReturn(f.terminal);
      when(f.dispatches.isDispatchWindowClosed(f.original)).thenReturn(true);
      Mockito.doThrow(failure.asRuntimeException()).when(f.creation).closeOriginal(f.binding);
      assertThat(f.coordinator.recover(f.operation, f.recent, f.environment).state())
          .isEqualTo(AccountTenantCreationCoordinator.State.COMPLETED);
      verify(f.creation).closeOriginal(f.binding);
      verify(f.completion).complete(f.operation, f.terminal, f.environment, f.recent);
      verifyNoInteractions(f.orders, f.admission);
    }
  }

  @Test
  void closureAckOrFailureWithUncertainReadbackCannotBecomeNegative() {
    for (boolean lostAck : new boolean[] {false, true}) {
      var f = new Fixture();
      when(f.outcomes.readOriginal(f.binding))
          .thenThrow(
              Status.NOT_FOUND.asRuntimeException(), Status.UNAVAILABLE.asRuntimeException());
      when(f.dispatches.isDispatchWindowClosed(f.original)).thenReturn(true);
      if (lostAck)
        Mockito.doThrow(Status.UNAVAILABLE.asRuntimeException())
            .when(f.creation)
            .closeOriginal(f.binding);
      assertThat(f.coordinator.recover(f.operation, f.recent, f.environment).state())
          .isEqualTo(AccountTenantCreationCoordinator.State.PENDING);
      verify(f.creation).closeOriginal(f.binding);
      verifyNoInteractions(f.orders, f.completion, f.admission);
    }
  }

  private static final class Fixture {
    final UUID operation = UUID.randomUUID(),
        call = UUID.randomUUID(),
        requestId = UUID.randomUUID(),
        gdRequest = UUID.randomUUID();
    final UUID account = UUID.randomUUID();
    static final long DEADLINE = 1900000000000L;
    final AccountTenantCreationIntentRepository.Request request =
        new AccountTenantCreationIntentRepository.Request(
            requestId,
            operation,
            gdRequest,
            account,
            "test",
            "fresh",
            "Game",
            null,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            true);
    final GameTenantCreationOriginalOutcomeEvidence.Binding binding;
    final AccountTenantCreationSourceOrderBinding holder =
        mock(AccountTenantCreationSourceOrderBinding.class);
    final AccountTenantCreationAdmissionOwner admission =
        mock(AccountTenantCreationAdmissionOwner.class);
    final AccountTenantCreationDispatchRepository dispatches =
        mock(AccountTenantCreationDispatchRepository.class);
    final GameDesignAuthorizedTenantCreationClient creation =
        mock(GameDesignAuthorizedTenantCreationClient.class);
    final GameDesignOriginalTenantCreationOutcomeClient outcomes =
        mock(GameDesignOriginalTenantCreationOutcomeClient.class);
    final AccountTenantCreationCompletionOwner completion =
        mock(AccountTenantCreationCompletionOwner.class);
    final AccountTenantCreationSourceOrderRepository orders =
        mock(AccountTenantCreationSourceOrderRepository.class);
    final AccountTenantCreationAdmissionOwner.PreparedResult prepared =
        mock(AccountTenantCreationAdmissionOwner.PreparedResult.class);
    final AccountRecentReauthenticationEvidence recent =
        mock(AccountRecentReauthenticationEvidence.class);
    final CapturedEnvironmentBoundary environment = mock(CapturedEnvironmentBoundary.class);
    final VerifiedTerminal terminal = mock(VerifiedTerminal.class);
    final AccountTenantCreationCompletionOwner.Result completed =
        mock(AccountTenantCreationCompletionOwner.Result.class);
    final AccountTenantCreationDispatchRepository.Original original;
    final AccountTenantCreationCoordinator coordinator;

    Fixture() {
      String digest =
          GameTenantCreationDigest.requestDigest("test", gdRequest, "fresh", "Game", null);
      binding =
          new GameTenantCreationOriginalOutcomeEvidence.Binding(
              "test", gdRequest, operation, account, digest, "sha256:" + "a".repeat(64));
      UUID ownerOperation = UUID.randomUUID(), tenant = UUID.randomUUID();
      var receipt =
          new FreshTenantCreationEvidence(
              1,
              "test",
              gdRequest,
              ownerOperation,
              digest,
              tenant,
              7L,
              "fresh",
              "NEW_GAME_ROW",
              GameTenantCreationDigest.evidenceDigest(
                  "test", gdRequest, ownerOperation, digest, tenant, 7L, "fresh", "NEW_GAME_ROW"));
      var evidence =
          new GameTenantCreationOriginalOutcomeEvidence(
              1,
              binding,
              GameTenantCreationOriginalOutcomeEvidence.Outcome.CREATED,
              Optional.of(receipt));
      var intent =
          new AccountTenantCreationIntentRepository.OriginalIntent(
              requestId,
              operation,
              gdRequest,
              account,
              "test",
              "fresh",
              "Game",
              null,
              request.recentReauthenticationEvidenceId(),
              request.creatorPartyId(),
              request.consentOperationId(),
              1,
              true,
              digest,
              new byte[] {1},
              "sha256:" + "b".repeat(64),
              Instant.ofEpochMilli(DEADLINE - 60000));
      original =
          new AccountTenantCreationDispatchRepository.Original(
              intent, mock(AccountTenantCreationAdmissionRepository.Admitted.class), holder);
      when(holder.outcomeBinding()).thenReturn(binding);
      when(holder.canonicalBytes()).thenReturn(new byte[] {2});
      when(dispatches.readOriginal(operation)).thenReturn(original);
      var initial = mock(AccountTenantCreationDispatchRepository.InitialDispatch.class);
      when(initial.original()).thenReturn(original);
      when(initial.callId()).thenReturn(call);
      when(initial.validBefore()).thenReturn(DEADLINE);
      when(admission.start("jwt", request, recent, environment)).thenReturn(prepared);
      when(dispatches.claim(prepared, environment, recent)).thenReturn(Optional.of(initial));
      when(creation.create(call, binding, "fresh", "Game", null, DEADLINE)).thenReturn(receipt);
      when(terminal.binding()).thenReturn(binding);
      when(terminal.evidence()).thenReturn(evidence);
      when(outcomes.readOriginal(binding)).thenReturn(terminal);
      when(completed.state()).thenReturn(AccountTenantCreationCompletionOwner.State.COMPLETED);
      when(completed.payload()).thenReturn(new byte[] {3});
      when(completed.digest()).thenReturn("digest");
      when(completion.complete(operation, terminal, environment, recent)).thenReturn(completed);
      var manager = mock(PlatformTransactionManager.class);
      when(manager.getTransaction(any())).thenAnswer(invocation -> mock(TransactionStatus.class));
      coordinator =
          new AccountTenantCreationCoordinator(
              admission, dispatches, creation, outcomes, completion, orders, manager);
    }

    AccountTenantCreationCoordinator.Result start() {
      return coordinator.start("jwt", request, recent, environment);
    }
  }
}
