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
    order.verify(f.creation).create(f.call, f.binding, "fresh", "Game", null, f.deadline);
    order.verify(f.outcomes).readOriginal(f.binding);
    order.verify(f.completion).complete(f.operation, f.terminal, f.environment, f.recent);
  }

  @Test
  void lostCreateReplyIsReadBackWithoutAnotherSend() {
    var f = new Fixture();
    when(f.creation.create(any(), any(), any(), any(), any(), Mockito.anyLong()))
        .thenThrow(Status.UNAVAILABLE.asRuntimeException());
    assertThat(f.start().state()).isEqualTo(AccountTenantCreationCoordinator.State.COMPLETED);
    verify(f.creation).create(f.call, f.binding, "fresh", "Game", null, f.deadline);
    verify(f.outcomes).readOriginal(f.binding);
  }

  @Test
  void existingClaimAndUnknownTerminalNeverReauthenticateReadmitOrRedispatch() {
    var f = new Fixture();
    when(f.dispatches.hasClaim(f.operation)).thenReturn(true);
    when(f.outcomes.readOriginal(f.binding)).thenThrow(Status.NOT_FOUND.asRuntimeException());
    assertThat(f.start().state()).isEqualTo(AccountTenantCreationCoordinator.State.PENDING);
    verifyNoInteractions(f.admission, f.creation, f.completion);
    verify(f.dispatches, never()).claim(any(), any(), any());
  }

  @Test
  void concurrentClaimLoserOnlyReconcilesAndDoesNotInventNoCreate() {
    var f = new Fixture();
    when(f.dispatches.claim(f.prepared, f.environment, f.recent)).thenReturn(Optional.empty());
    when(f.outcomes.readOriginal(f.binding)).thenThrow(Status.DEADLINE_EXCEEDED.asRuntimeException());
    assertThat(f.start().state()).isEqualTo(AccountTenantCreationCoordinator.State.PENDING);
    verifyNoInteractions(f.creation, f.completion, f.orders);
  }

  @Test
  void definitiveNotCreatedSettlesOnlyExactOriginalWithCommittedReadback() {
    var f = new Fixture();
    var negative = new GameTenantCreationOriginalOutcomeEvidence(1, f.binding,
        GameTenantCreationOriginalOutcomeEvidence.Outcome.NOT_CREATED, Optional.empty());
    when(f.terminal.evidence()).thenReturn(negative);
    when(f.orders.readOriginal(f.operation)).thenReturn(Optional.of(f.holder));
    when(f.orders.readSettlement(f.operation)).thenReturn(Optional.of(negative));
    assertThat(f.coordinator.recover(f.operation, f.recent, f.environment).state())
        .isEqualTo(AccountTenantCreationCoordinator.State.NOT_CREATED);
    verify(f.orders).settleLocked(f.holder, f.terminal);
    verify(f.orders).readSettlement(f.operation);
    verifyNoInteractions(f.admission, f.creation, f.completion);
  }

  @Test
  void lateCreatedCannotBypassFinalAuthorityAndDoesNotBecomeNegativeSettlement() {
    var f = new Fixture();
    when(f.completion.complete(f.operation, f.terminal, f.environment, f.recent))
        .thenThrow(new IllegalStateException("Final authority expired"));
    assertThatThrownBy(() -> f.coordinator.recover(f.operation, f.recent, f.environment))
        .isInstanceOf(IllegalStateException.class).hasMessage("Final authority expired");
    verifyNoInteractions(f.admission, f.creation, f.orders);
  }

  private static final class Fixture {
    final UUID operation = UUID.randomUUID(), call = UUID.randomUUID(), requestId = UUID.randomUUID(), gdRequest = UUID.randomUUID();
    final UUID account = UUID.randomUUID();
    final long deadline = System.currentTimeMillis() + 60000;
    final AccountTenantCreationIntentRepository.Request request = new AccountTenantCreationIntentRepository.Request(
        requestId, operation, gdRequest, account, "test", "fresh", "Game", null,
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, true);
    final GameTenantCreationOriginalOutcomeEvidence.Binding binding;
    final AccountTenantCreationSourceOrderBinding holder = mock(AccountTenantCreationSourceOrderBinding.class);
    final AccountTenantCreationAdmissionOwner admission = mock(AccountTenantCreationAdmissionOwner.class);
    final AccountTenantCreationDispatchRepository dispatches = mock(AccountTenantCreationDispatchRepository.class);
    final GameDesignAuthorizedTenantCreationClient creation = mock(GameDesignAuthorizedTenantCreationClient.class);
    final GameDesignOriginalTenantCreationOutcomeClient outcomes = mock(GameDesignOriginalTenantCreationOutcomeClient.class);
    final AccountTenantCreationCompletionOwner completion = mock(AccountTenantCreationCompletionOwner.class);
    final AccountTenantCreationSourceOrderRepository orders = mock(AccountTenantCreationSourceOrderRepository.class);
    final AccountTenantCreationAdmissionOwner.PreparedResult prepared = mock(AccountTenantCreationAdmissionOwner.PreparedResult.class);
    final AccountRecentReauthenticationEvidence recent = mock(AccountRecentReauthenticationEvidence.class);
    final CapturedEnvironmentBoundary environment = mock(CapturedEnvironmentBoundary.class);
    final VerifiedTerminal terminal = mock(VerifiedTerminal.class);
    final AccountTenantCreationCoordinator coordinator;
    Fixture() {
      String digest = GameTenantCreationDigest.requestDigest("test", gdRequest, "fresh", "Game", null);
      binding = new GameTenantCreationOriginalOutcomeEvidence.Binding("test", gdRequest, operation, account, digest, "sha256:" + "a".repeat(64));
      UUID ownerOperation = UUID.randomUUID(), tenant = UUID.randomUUID();
      var receipt = new FreshTenantCreationEvidence(1, "test", gdRequest, ownerOperation, digest, tenant, 7L,
          "fresh", "NEW_GAME_ROW", GameTenantCreationDigest.evidenceDigest("test", gdRequest, ownerOperation, digest, tenant, 7L, "fresh", "NEW_GAME_ROW"));
      var evidence = new GameTenantCreationOriginalOutcomeEvidence(1, binding, GameTenantCreationOriginalOutcomeEvidence.Outcome.CREATED, Optional.of(receipt));
      var intent = new AccountTenantCreationIntentRepository.OriginalIntent(requestId, operation, gdRequest, account,
          "test", "fresh", "Game", null, request.recentReauthenticationEvidenceId(), request.creatorPartyId(),
          request.consentOperationId(), 1, true, digest, new byte[] {1}, "sha256:" + "b".repeat(64), Instant.now());
      var original = new AccountTenantCreationDispatchRepository.Original(intent,
          mock(AccountTenantCreationAdmissionRepository.Admitted.class), holder);
      when(holder.outcomeBinding()).thenReturn(binding);
      when(holder.canonicalBytes()).thenReturn(new byte[] {2});
      when(dispatches.readOriginal(operation)).thenReturn(original);
      var initial = mock(AccountTenantCreationDispatchRepository.InitialDispatch.class);
      when(initial.original()).thenReturn(original); when(initial.callId()).thenReturn(call); when(initial.validBefore()).thenReturn(deadline);
      when(admission.start("jwt", request, recent, environment)).thenReturn(prepared);
      when(dispatches.claim(prepared, environment, recent)).thenReturn(Optional.of(initial));
      when(creation.create(call, binding, "fresh", "Game", null, deadline)).thenReturn(receipt);
      when(terminal.binding()).thenReturn(binding); when(terminal.evidence()).thenReturn(evidence);
      when(outcomes.readOriginal(binding)).thenReturn(terminal);
      var completed = mock(AccountTenantCreationCompletionOwner.Result.class);
      when(completed.payload()).thenReturn(new byte[] {3}); when(completed.digest()).thenReturn("digest");
      when(completion.complete(operation, terminal, environment, recent)).thenReturn(completed);
      var manager = mock(PlatformTransactionManager.class);
      when(manager.getTransaction(any())).thenAnswer(invocation -> mock(TransactionStatus.class));
      coordinator = new AccountTenantCreationCoordinator(admission, dispatches, creation, outcomes, completion, orders, manager);
    }
    AccountTenantCreationCoordinator.Result start() { return coordinator.start("jwt", request, recent, environment); }
  }
}
