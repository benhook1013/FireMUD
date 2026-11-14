package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/** Controlled replay orchestration; real MAC/custody and physical storage have separate proofs. */
class AccountRecentReauthenticationReplayTest {
  @Test
  void exactReplayUsesRetainedKeyWithoutPrimaryAuthenticationAndClearsTransientBytes() {
    var f = new Fixture();
    AtomicReference<byte[]> transientRequest = new AtomicReference<>();
    when(f.crypto.recentReauthenticationRequestMac(eq("prior-key"), any()))
        .thenAnswer(
            invocation -> {
              transientRequest.set(invocation.getArgument(1));
              return "a".repeat(64);
            });
    assertThat(f.service.reauthenticate(f.request(f.operation))).isSameAs(f.evidence);
    verify(f.crypto, times(2)).recentReauthenticationRequestMac(eq("prior-key"), any());
    verify(f.crypto, never()).currentKeyId();
    verify(f.repository).readExactOriginal(f.evidence);
    verifyNoInteractions(f.primary, f.accounts, f.generations, f.policies);
    assertThat(transientRequest.get()).containsOnly((byte) 0);
    assertThat(f.proof.toString()).doesNotContain("prior-key", "a".repeat(64));
  }

  @Test
  void missingProofAndUnavailableOriginalKeyDenyWithoutReplacementOrAuthentication() {
    var f = new Fixture();
    when(f.repository.findReplayProof(f.evidence)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> f.service.reauthenticate(f.request(f.operation)))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(f.primary, f.crypto);
    var unavailable = new Fixture();
    when(unavailable.crypto.recentReauthenticationRequestMac(eq("prior-key"), any()))
        .thenThrow(new IllegalStateException("Original key unavailable"));
    assertThatThrownBy(
            () -> unavailable.service.reauthenticate(unavailable.request(unavailable.operation)))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(unavailable.primary);
    verify(unavailable.crypto, never()).currentKeyId();
  }

  @Test
  void equalRequestAndCreationIdsAreRejectedBeforeAnyOwnerOrCredentialAccess() {
    var f = new Fixture();
    assertThatThrownBy(() -> f.service.reauthenticate(f.request(f.requestId)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Distinct");
    verifyNoInteractions(f.repository, f.primary, f.accounts, f.generations, f.policies, f.crypto);
  }

  @Test
  void changedTupleConflictsBeforeMacOrPrimaryAuthentication() {
    var f = new Fixture();
    assertThatThrownBy(() -> f.service.reauthenticate(f.request(UUID.randomUUID())))
        .isInstanceOf(AccountRecentReauthenticationService.IdempotencyConflictException.class);
    verifyNoInteractions(f.primary, f.crypto);
  }

  @Test
  void mismatchCommitsFailureBeforeDenialAndDoesNotRunIndependentReadback() {
    var f = new Fixture();
    when(f.crypto.recentReauthenticationRequestMac(eq("prior-key"), any()))
        .thenReturn("b".repeat(64));
    assertThatThrownBy(() -> f.service.reauthenticate(f.request(f.operation)))
        .isInstanceOf(AuthenticationException.class);
    var order = inOrder(f.repository, f.transactions);
    order.verify(f.repository).lockRequest(f.requestId);
    order.verify(f.repository).recordReplayFailure(f.evidence);
    order.verify(f.transactions).commit(any());
    verify(f.transactions, never()).rollback(any());
    verify(f.repository, never()).readExactOriginal(any());
    verifyNoInteractions(f.primary, f.accounts, f.generations, f.policies);
  }

  @Test
  void exhaustedBudgetDeniesWithoutAnyCredentialAccessOrAdditionalFailure() {
    var f = new Fixture();
    when(f.repository.countReplayFailures(f.evidence)).thenReturn(5);
    assertThatThrownBy(() -> f.service.reauthenticate(f.request(f.operation)))
        .isInstanceOf(AuthenticationException.class);
    verify(f.repository, never()).findReplayProof(any());
    verify(f.repository, never()).recordReplayFailure(any());
    verify(f.transactions).commit(any());
    verifyNoInteractions(f.crypto, f.primary, f.accounts, f.generations, f.policies);
  }

  @Test
  void independentReadbackMismatchCommitsExactlyOneFailureBeforeDenial() {
    var f = new Fixture();
    when(f.crypto.recentReauthenticationRequestMac(eq("prior-key"), any()))
        .thenReturn("a".repeat(64), "b".repeat(64));
    assertThatThrownBy(() -> f.service.reauthenticate(f.request(f.operation)))
        .isInstanceOf(AuthenticationException.class);
    verify(f.repository, times(2)).lockRequest(f.requestId);
    verify(f.repository).recordReplayFailure(f.evidence);
    verify(f.transactions, times(2)).commit(any());
    verify(f.transactions, never()).rollback(any());
    var order = inOrder(f.repository, f.transactions);
    order.verify(f.repository).readExactOriginal(f.evidence);
    order.verify(f.repository).recordReplayFailure(f.evidence);
    order.verify(f.transactions).commit(any());
  }

  @Test
  void successfulReplayDoesNotResetPriorFailures() {
    var f = new Fixture();
    when(f.repository.countReplayFailures(f.evidence)).thenReturn(4);
    when(f.crypto.recentReauthenticationRequestMac(eq("prior-key"), any()))
        .thenReturn("a".repeat(64));
    assertThat(f.service.reauthenticate(f.request(f.operation))).isSameAs(f.evidence);
    verify(f.repository, never()).recordReplayFailure(any());
    verify(f.transactions, times(2)).commit(any());
  }

  @Test
  void independentReadbackExhaustionSkipsSecondCredentialCheck() {
    var f = new Fixture();
    when(f.repository.countReplayFailures(f.evidence)).thenReturn(4, 5);
    when(f.crypto.recentReauthenticationRequestMac(eq("prior-key"), any()))
        .thenReturn("a".repeat(64));
    assertThatThrownBy(() -> f.service.reauthenticate(f.request(f.operation)))
        .isInstanceOf(AuthenticationException.class);
    verify(f.crypto).recentReauthenticationRequestMac(eq("prior-key"), any());
    verify(f.repository, never()).recordReplayFailure(any());
    verify(f.transactions, times(2)).commit(any());
  }

  @Test
  void billingReplayRetainsOriginalProofAndCannotBeReplayedAsGameCreation() {
    var f = new Fixture(AccountRecentReauthenticationPolicy.BILLING_PURPOSE);
    when(f.crypto.recentReauthenticationRequestMac(eq("prior-key"), any()))
        .thenReturn("a".repeat(64));
    assertThat(f.service.reauthenticate(f.request(f.operation))).isSameAs(f.evidence);
    verifyNoInteractions(f.primary, f.accounts, f.generations, f.policies);
    var wrongPurpose = new Fixture(AccountRecentReauthenticationPolicy.BILLING_PURPOSE);
    var changed =
        new AccountRecentReauthenticationService.Request(
            wrongPurpose.requestId,
            wrongPurpose.account,
            AccountRecentReauthenticationPolicy.PURPOSE,
            wrongPurpose.operation,
            "person@example.test",
            "test-only-secret");
    assertThatThrownBy(() -> wrongPurpose.service.reauthenticate(changed))
        .isInstanceOf(AccountRecentReauthenticationService.IdempotencyConflictException.class);
    verifyNoInteractions(wrongPurpose.primary, wrongPurpose.crypto);
  }

  @Test
  void newBillingEvidenceRequiresTypedContextAndTypedReplayCannotChangeIt() {
    var unbound = new Fixture(AccountRecentReauthenticationPolicy.BILLING_PURPOSE);
    when(unbound.repository.findByRequest(unbound.requestId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> unbound.service.reauthenticate(unbound.request(unbound.operation)))
        .isInstanceOf(AccountRecentReauthenticationService.IdempotencyConflictException.class);
    verify(unbound.repository, never()).insert(any(), any(), any());
    verifyNoInteractions(unbound.primary, unbound.accounts, unbound.generations, unbound.crypto);

    var bound = new Fixture(AccountRecentReauthenticationPolicy.BILLING_PURPOSE);
    Instant expiry = Instant.parse("2026-11-02T01:02:03.123400Z");
    var context = billingContext(bound, expiry);
    when(bound.evidence.billingContext()).thenReturn(context);
    when(bound.crypto.recentReauthenticationRequestMac(eq("prior-key"), any()))
        .thenReturn("a".repeat(64));
    when(bound.evidence.requestDigest())
        .thenReturn(
            AccountRecentReauthenticationRepository.digest(
                AccountRecentReauthenticationRepository.requestPayload(
                    bound.requestId, bound.account, bound.purpose, bound.operation, context)));
    var exactRequest =
        new AccountRecentReauthenticationService.Request(
            bound.requestId,
            bound.account,
            bound.purpose,
            bound.operation,
            context,
            "person@example.test",
            "test-only-secret");
    assertThat(bound.service.reauthenticate(exactRequest)).isSameAs(bound.evidence);

    var changedContext =
        new AccountRecentReauthenticationEvidence.BillingContext(
            context.accountId(),
            context.subscriptionId(),
            context.originalRequestId(),
            context.tupleDigest(),
            context.expectedRowVersion(),
            Instant.parse("2026-11-02T01:02:03.123000Z"),
            context.authorizationRequestId(),
            context.expectedAuthorizationVersion(),
            context.authorizationPurpose());
    var changedRequest =
        new AccountRecentReauthenticationService.Request(
            bound.requestId,
            bound.account,
            bound.purpose,
            bound.operation,
            changedContext,
            "person@example.test",
            "test-only-secret");
    assertThatThrownBy(() -> bound.service.reauthenticate(changedRequest))
        .isInstanceOf(AccountRecentReauthenticationService.IdempotencyConflictException.class);
    verifyNoInteractions(bound.primary, bound.accounts, bound.generations);
  }

  private static AccountRecentReauthenticationEvidence.BillingContext billingContext(
      Fixture fixture, Instant expiry) {
    return new AccountRecentReauthenticationEvidence.BillingContext(
        fixture.account,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "sha256:" + "e".repeat(64),
        3L,
        expiry,
        fixture.operation,
        0L,
        AccountRecentReauthenticationEvidence.BillingAuthorizationPurpose.PRE_ROW_RENEWAL);
  }

  private static final class Fixture {
    final UUID requestId = UUID.randomUUID(),
        account = UUID.randomUUID(),
        operation = UUID.randomUUID();
    final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    final AccountServiceImpl primary = mock(AccountServiceImpl.class);
    final AccountRepository accounts = mock(AccountRepository.class);
    final AccountAuthorityGenerationRepository generations =
        mock(AccountAuthorityGenerationRepository.class);
    final AccountRecentReauthenticationRepository repository =
        mock(AccountRecentReauthenticationRepository.class);
    final AccountRecentReauthenticationPolicyRepository policies =
        mock(AccountRecentReauthenticationPolicyRepository.class);
    final AccountControlUiResponseCryptography crypto =
        mock(AccountControlUiResponseCryptography.class);
    final AccountRecentReauthenticationEvidence evidence =
        mock(AccountRecentReauthenticationEvidence.class);
    final AccountRecentReauthenticationRepository.ReplayProof proof =
        new AccountRecentReauthenticationRepository.ReplayProof("prior-key", "a".repeat(64));
    final AccountRecentReauthenticationService service;
    final String purpose;

    Fixture() {
      this(AccountRecentReauthenticationPolicy.PURPOSE);
    }

    Fixture(String purpose) {
      this.purpose = purpose;
      when(transactions.getTransaction(any()))
          .thenAnswer(invocation -> mock(TransactionStatus.class));
      when(evidence.requestId()).thenReturn(requestId);
      when(evidence.accountId()).thenReturn(account);
      when(evidence.purpose()).thenReturn(purpose);
      when(evidence.creationOperationId()).thenReturn(operation);
      String requestDigest =
          AccountRecentReauthenticationRepository.digest(
              AccountRecentReauthenticationRepository.requestPayload(
                  requestId, account, purpose, operation));
      when(evidence.requestDigest()).thenReturn(requestDigest);
      when(repository.findByRequest(requestId)).thenReturn(Optional.of(evidence));
      when(repository.findReplayProof(evidence)).thenReturn(Optional.of(proof));
      when(repository.readExactOriginal(evidence)).thenReturn(evidence);
      service =
          new AccountRecentReauthenticationService(
              primary,
              accounts,
              generations,
              repository,
              policies,
              transactions,
              Clock.systemUTC(),
              crypto);
    }

    AccountRecentReauthenticationService.Request request(UUID creationOperation) {
      return new AccountRecentReauthenticationService.Request(
          requestId,
          account,
          purpose,
          creationOperation,
          "person@example.test",
          "test-only-secret");
    }
  }
}
