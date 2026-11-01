package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
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

  private static final class Fixture {
    final UUID requestId = UUID.randomUUID(),
        account = UUID.randomUUID(),
        operation = UUID.randomUUID();
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

    Fixture() {
      var transactions = mock(PlatformTransactionManager.class);
      when(transactions.getTransaction(any()))
          .thenAnswer(invocation -> mock(TransactionStatus.class));
      when(evidence.requestId()).thenReturn(requestId);
      when(evidence.accountId()).thenReturn(account);
      when(evidence.purpose()).thenReturn(AccountRecentReauthenticationPolicy.PURPOSE);
      when(evidence.creationOperationId()).thenReturn(operation);
      String requestDigest =
          AccountRecentReauthenticationRepository.digest(
              AccountRecentReauthenticationRepository.requestPayload(
                  requestId, account, AccountRecentReauthenticationPolicy.PURPOSE, operation));
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
          AccountRecentReauthenticationPolicy.PURPOSE,
          creationOperation,
          "person@example.test",
          "test-only-secret");
    }
  }
}
