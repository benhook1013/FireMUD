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
import java.util.function.Consumer;
import java.util.function.Function;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled replay orchestration; real MAC/custody and physical storage have separate proofs. */
class AccountRecentReauthenticationReplayTest {
  @Test
  void exactReplayUsesRetainedKeyWithoutPrimaryAuthenticationAndClearsTransientBytes() {
    var f = new Fixture();
    AtomicReference<byte[]> transientRequest = new AtomicReference<>();
    when(f.crypto.recentReauthenticationRequestMac(eq("prior-key"), any()))
        .thenAnswer(invocation -> {
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
  void genericEntryRejectsTypedBillingBeforeReadingOrRetainingEvidence() {
    var f = new Fixture();
    var binding =
        new AccountRecentReauthenticationEvidence.BillingContext(
            f.account,
            UUID.randomUUID(),
            f.operation,
            "sha256:" + "d".repeat(64),
            1L,
            Instant.parse("2027-01-01T00:00:00Z"),
            f.operation,
            0L,
            AccountRecentReauthenticationEvidence.BillingAuthorizationPurpose.INITIAL_CONFIRMATION);
    var billing =
        new AccountRecentReauthenticationService.Request(
            f.requestId,
            f.account,
            AccountRecentReauthenticationPolicy.BILLING_PURPOSE,
            f.operation,
            binding,
            "controlled@example.invalid",
            "controlled-fixture");
    assertThatThrownBy(() -> f.service.reauthenticate(billing))
        .isInstanceOf(AccountRecentReauthenticationService.IdempotencyConflictException.class);
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
  void legacyUntypedBillingCannotReplayAtGenericEntryOrAsGameCreation() {
    var f = new Fixture(AccountRecentReauthenticationPolicy.BILLING_PURPOSE);
    assertThatThrownBy(() -> f.service.reauthenticate(f.request(f.operation)))
        .isInstanceOf(AccountRecentReauthenticationService.IdempotencyConflictException.class);
    verifyNoInteractions(f.repository, f.primary, f.accounts, f.generations, f.policies, f.crypto);
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
    var actor = mock(AccountControlUiActorService.AccountAuthenticatedActor.class);
    when(actor.accountId()).thenReturn(bound.account);
    var actors = mock(AccountControlUiActorService.class);
    when(actors.withCurrentAccount(eq("controlled-bearer"), any()))
        .thenAnswer(call -> {
          Function<AccountControlUiActorService.AccountAuthenticatedActor, ?> action =
              call.getArgument(1);
          TransactionSynchronizationManager.setActualTransactionActive(true);
          try {
            return action.apply(actor);
          } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
          }
        });
    var currentAccount = mock(Account.class);
    when(currentAccount.getId()).thenReturn(1L);
    when(currentAccount.getAccountUuid()).thenReturn(bound.account);
    when(currentAccount.getAccountUuidSourceNumericId()).thenReturn(1L);
    when(currentAccount.getAccountUuidProvenance())
        .thenReturn(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    when(currentAccount.getLifecycleState()).thenReturn(AccountLifecycleState.ACTIVE);
    when(bound.accounts.findByAccountUuidForUpdate(bound.account))
        .thenReturn(Optional.of(currentAccount));
    var authority = mock(ScopeState.class);
    var fence = new IssuanceFence(bound.account, 1L, 1L);
    when(authority.scope()).thenReturn(AuthorityScope.account(bound.account));
    when(authority.generation()).thenReturn(1L);
    when(authority.sourceVersion()).thenReturn(1L);
    when(authority.issuanceFence()).thenReturn(fence);
    when(bound.generations.read(AuthorityScope.account(bound.account))).thenReturn(authority);
    when(bound.evidence.identityProvenance())
        .thenReturn(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    when(bound.evidence.identitySourceNumericId()).thenReturn(1L);
    when(bound.evidence.lifecycleState()).thenReturn(AccountLifecycleState.ACTIVE);
    when(bound.evidence.accountAuthorityGeneration()).thenReturn(1L);
    when(bound.evidence.accountAuthoritySourceVersion()).thenReturn(1L);
    when(bound.evidence.issuanceFence()).thenReturn(1L);
    when(bound.evidence.issuanceFenceSourceVersion()).thenReturn(1L);
    when(bound.evidence.evidenceId()).thenReturn(bound.requestId);
    when(bound.evidence.effectivePolicyVersion()).thenReturn("controlled-policy");
    when(bound.evidence.issuedAt()).thenReturn(expiry);
    var policy = mock(AccountRecentReauthenticationPolicy.Effective.class);
    when(policy.effectivePolicyVersion()).thenReturn("controlled-policy");
    when(policy.isRecent(eq(expiry), any())).thenReturn(true);
    when(bound.policies.resolveCurrentForPurpose(bound.purpose)).thenReturn(policy);
    when(bound.repository.findByEvidenceId(bound.requestId))
        .thenReturn(Optional.of(bound.evidence));
    when(bound.repository.lockExact(bound.evidence)).thenReturn(bound.evidence);
    @SuppressWarnings("unchecked")
    Consumer<AccountRecentReauthenticationEvidence> bind = mock(Consumer.class);
    assertThat(
            bound.service.reauthenticateAndBindBilling(
                exactRequest, actors, "controlled-bearer", bind))
        .isSameAs(bound.evidence);
    verify(bind).accept(bound.evidence);

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
    assertThatThrownBy(() ->
            bound.service.reauthenticateAndBindBilling(
                changedRequest, actors, "controlled-bearer", bind))
        .isInstanceOf(AccountRecentReauthenticationService.IdempotencyConflictException.class);
    verify(bind, times(1)).accept(any());
    verifyNoInteractions(bound.primary);
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
