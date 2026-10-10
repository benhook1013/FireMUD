package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.CallerIdentity;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.RecoveredCredential;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.RecoveryPreflight;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.ResponseRecoveryExpiredException;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationCommittedIssuanceOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationCommittedIssuanceOwner.ActiveCommittedIssuanceObservation;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationResponseRecoveryOwner;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AccountGameplayDelegationResponseRecoveryOwnerTest {
  private static final UUID REQUEST_ID = UUID.fromString("1ee95a1e-83f2-4a63-a7ba-6288e246ac76");
  private static final UUID OTHER_REQUEST_ID =
      UUID.fromString("58102ea4-9245-4ddc-a68c-3ff41dcf96af");
  private static final UUID ACCOUNT_ID = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
  private static final UUID OTHER_ACCOUNT_ID =
      UUID.fromString("674cb504-8f4c-4d06-86c6-7b72f79cae74");
  private static final UUID CALLER_CONTEXT_ID =
      UUID.fromString("91d13625-0e03-4e46-b82e-18f69f091436");
  private static final UUID OTHER_CONTEXT_ID =
      UUID.fromString("e46c59e8-4dd8-49a7-9d26-2db49f0d0ecb");
  private static final CallerIdentity CALLER =
      new CallerIdentity("spiffe://firemud/ns/test/sa/game-session-service", CALLER_CONTEXT_ID);
  private static final byte[] EXACT_JWT =
      "header.payload.signature".getBytes(StandardCharsets.US_ASCII);
  private static final long ORIGINAL_EXPIRY_EPOCH_SECOND = 1_896_602_520L;

  @Test
  void activatesBeforeEnvelopeRecoveryAndReturnsExactRepeatedCredentialWithoutRenewingExpiry() {
    AccountGameplayDelegationResponseEnvelopeRepository responseEnvelopes =
        mock(AccountGameplayDelegationResponseEnvelopeRepository.class);
    AccountGameplayDelegationCommittedIssuanceOwner committedOwner =
        mock(AccountGameplayDelegationCommittedIssuanceOwner.class);
    ActiveCommittedIssuanceObservation active = mock(ActiveCommittedIssuanceObservation.class);
    RecoveredCredential credential = credential();
    when(responseEnvelopes.preflightCommittedRecovery(REQUEST_ID, ACCOUNT_ID, CALLER))
        .thenReturn(preflight());
    when(committedOwner.activateCommittedIssuance(REQUEST_ID)).thenReturn(active);
    when(responseEnvelopes.recoverCommittedResponse(REQUEST_ID, ACCOUNT_ID, CALLER, active))
        .thenReturn(credential);
    AccountGameplayDelegationResponseRecoveryOwner owner =
        new AccountGameplayDelegationResponseRecoveryOwner(
            responseEnvelopes, committedOwner, new TestTransactionManager(0));

    RecoveredCredential first = owner.recoverInitialLoginResponse(REQUEST_ID, ACCOUNT_ID, CALLER);
    RecoveredCredential retry = owner.recoverInitialLoginResponse(REQUEST_ID, ACCOUNT_ID, CALLER);

    assertThat(first.compactJwtBytes()).containsExactly(EXACT_JWT);
    assertThat(retry.compactJwtBytes()).containsExactly(EXACT_JWT);
    assertThat(first.expiresAtEpochSecond()).isEqualTo(ORIGINAL_EXPIRY_EPOCH_SECOND);
    assertThat(retry.expiresAtEpochSecond()).isEqualTo(ORIGINAL_EXPIRY_EPOCH_SECOND);
    InOrder sequence = inOrder(responseEnvelopes, committedOwner);
    sequence.verify(responseEnvelopes).preflightCommittedRecovery(REQUEST_ID, ACCOUNT_ID, CALLER);
    sequence.verify(committedOwner).activateCommittedIssuance(REQUEST_ID);
    sequence
        .verify(responseEnvelopes)
        .recoverCommittedResponse(REQUEST_ID, ACCOUNT_ID, CALLER, active);
    sequence.verify(responseEnvelopes).preflightCommittedRecovery(REQUEST_ID, ACCOUNT_ID, CALLER);
    sequence.verify(committedOwner).activateCommittedIssuance(REQUEST_ID);
    sequence
        .verify(responseEnvelopes)
        .recoverCommittedResponse(REQUEST_ID, ACCOUNT_ID, CALLER, active);
    verify(committedOwner, times(2)).activateCommittedIssuance(REQUEST_ID);
  }

  @Test
  void requestWorkloadContextAndAccountMismatchNeverReachActivation() {
    assertRejectedBeforeActivation(OTHER_REQUEST_ID, ACCOUNT_ID, CALLER);
    assertRejectedBeforeActivation(REQUEST_ID, OTHER_ACCOUNT_ID, CALLER);
    assertRejectedBeforeActivation(
        REQUEST_ID,
        ACCOUNT_ID,
        new CallerIdentity("spiffe://firemud/ns/other/sa/game-session-service", CALLER_CONTEXT_ID));
    assertRejectedBeforeActivation(
        REQUEST_ID, ACCOUNT_ID, new CallerIdentity(CALLER.workload(), OTHER_CONTEXT_ID));
  }

  @Test
  void expiredOwnerHorizonNeverActivatesOrDecrypts() {
    AccountGameplayDelegationResponseEnvelopeRepository responseEnvelopes =
        mock(AccountGameplayDelegationResponseEnvelopeRepository.class);
    AccountGameplayDelegationCommittedIssuanceOwner committedOwner =
        mock(AccountGameplayDelegationCommittedIssuanceOwner.class);
    when(responseEnvelopes.preflightCommittedRecovery(REQUEST_ID, ACCOUNT_ID, CALLER))
        .thenThrow(new ResponseRecoveryExpiredException());
    AccountGameplayDelegationResponseRecoveryOwner owner =
        new AccountGameplayDelegationResponseRecoveryOwner(
            responseEnvelopes, committedOwner, new TestTransactionManager(0));

    assertThatThrownBy(() -> owner.recoverInitialLoginResponse(REQUEST_ID, ACCOUNT_ID, CALLER))
        .isInstanceOf(ResponseRecoveryExpiredException.class)
        .hasNoCause();

    verify(committedOwner, never()).activateCommittedIssuance(any());
    verify(responseEnvelopes, never()).recoverCommittedResponse(any(), any(), any(), any());
  }

  @Test
  void activationNetworkFailureReturnsNoCredentialAndSkipsEnvelopeRead() {
    AccountGameplayDelegationResponseEnvelopeRepository responseEnvelopes =
        mock(AccountGameplayDelegationResponseEnvelopeRepository.class);
    AccountGameplayDelegationCommittedIssuanceOwner committedOwner =
        mock(AccountGameplayDelegationCommittedIssuanceOwner.class);
    when(responseEnvelopes.preflightCommittedRecovery(REQUEST_ID, ACCOUNT_ID, CALLER))
        .thenReturn(preflight());
    when(committedOwner.activateCommittedIssuance(REQUEST_ID))
        .thenThrow(new AccountGameplayDelegationCommittedIssuanceOwner.OwnerUnavailableException());
    AccountGameplayDelegationResponseRecoveryOwner owner =
        new AccountGameplayDelegationResponseRecoveryOwner(
            responseEnvelopes, committedOwner, new TestTransactionManager(0));

    assertThatThrownBy(() -> owner.recoverInitialLoginResponse(REQUEST_ID, ACCOUNT_ID, CALLER))
        .isInstanceOf(
            AccountGameplayDelegationResponseRecoveryOwner.OwnerUnavailableException.class)
        .hasNoCause();

    verify(responseEnvelopes, never()).recoverCommittedResponse(any(), any(), any(), any());
  }

  @Test
  void sqlCommitFailureAfterDecryptionDoesNotReturnCredential() {
    AccountGameplayDelegationResponseEnvelopeRepository responseEnvelopes =
        mock(AccountGameplayDelegationResponseEnvelopeRepository.class);
    AccountGameplayDelegationCommittedIssuanceOwner committedOwner =
        mock(AccountGameplayDelegationCommittedIssuanceOwner.class);
    ActiveCommittedIssuanceObservation active = mock(ActiveCommittedIssuanceObservation.class);
    RecoveredCredential credential = credential();
    when(responseEnvelopes.preflightCommittedRecovery(REQUEST_ID, ACCOUNT_ID, CALLER))
        .thenReturn(preflight());
    when(committedOwner.activateCommittedIssuance(REQUEST_ID)).thenReturn(active);
    when(responseEnvelopes.recoverCommittedResponse(REQUEST_ID, ACCOUNT_ID, CALLER, active))
        .thenReturn(credential);
    AccountGameplayDelegationResponseRecoveryOwner owner =
        new AccountGameplayDelegationResponseRecoveryOwner(
            responseEnvelopes, committedOwner, new TestTransactionManager(2));

    assertThatThrownBy(() -> owner.recoverInitialLoginResponse(REQUEST_ID, ACCOUNT_ID, CALLER))
        .isInstanceOf(
            AccountGameplayDelegationResponseRecoveryOwner.OwnerUnavailableException.class)
        .hasNoCause();

    verify(responseEnvelopes).recoverCommittedResponse(REQUEST_ID, ACCOUNT_ID, CALLER, active);
  }

  private static void assertRejectedBeforeActivation(
      UUID requestId, UUID accountId, CallerIdentity caller) {
    AccountGameplayDelegationResponseEnvelopeRepository responseEnvelopes =
        mock(AccountGameplayDelegationResponseEnvelopeRepository.class);
    AccountGameplayDelegationCommittedIssuanceOwner committedOwner =
        mock(AccountGameplayDelegationCommittedIssuanceOwner.class);
    when(responseEnvelopes.preflightCommittedRecovery(REQUEST_ID, ACCOUNT_ID, CALLER))
        .thenReturn(preflight());
    AccountGameplayDelegationResponseRecoveryOwner owner =
        new AccountGameplayDelegationResponseRecoveryOwner(
            responseEnvelopes, committedOwner, new TestTransactionManager(0));

    assertThatThrownBy(() -> owner.recoverInitialLoginResponse(requestId, accountId, caller))
        .isInstanceOf(
            AccountGameplayDelegationResponseRecoveryOwner.OwnerUnavailableException.class)
        .hasNoCause();

    verify(committedOwner, never()).activateCommittedIssuance(any());
    verify(responseEnvelopes, never()).recoverCommittedResponse(any(), any(), any(), any());
  }

  private static RecoveryPreflight preflight() {
    return new RecoveryPreflight(
        UUID.fromString("5414e55d-0393-4561-ac3d-cb916a08d3f0"),
        REQUEST_ID,
        ACCOUNT_ID,
        CALLER.workload(),
        CALLER.contextId(),
        "a".repeat(64),
        UUID.fromString("2921ba03-bf74-49ac-b24b-a9255f5de308"),
        ORIGINAL_EXPIRY_EPOCH_SECOND,
        Math.multiplyExact(ORIGINAL_EXPIRY_EPOCH_SECOND, 1_000L));
  }

  private static RecoveredCredential credential() {
    RecoveredCredential value = mock(RecoveredCredential.class);
    when(value.compactJwtBytes()).thenReturn(EXACT_JWT.clone());
    when(value.expiresAtEpochSecond()).thenReturn(ORIGINAL_EXPIRY_EPOCH_SECOND);
    return value;
  }

  private static final class TestTransactionManager implements PlatformTransactionManager {
    private final int failAtCommit;
    private int commitCount;

    private TestTransactionManager(int failAtCommit) {
      this.failAtCommit = failAtCommit;
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      commitCount++;
      if (failAtCommit > 0 && commitCount == failAtCommit) {
        throw new IllegalStateException("SQL commit failed");
      }
    }

    @Override
    public void rollback(TransactionStatus status) {}
  }
}
