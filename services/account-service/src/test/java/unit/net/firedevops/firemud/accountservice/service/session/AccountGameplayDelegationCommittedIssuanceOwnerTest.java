package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountGameplayCredentialRequestBindingFixture;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationPendingIdentity;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationCommittedIssuanceOwner.OwnerUnavailableException;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.EvidenceBundleReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountGameplayDelegationCommittedIssuanceOwnerTest {
  private static final UUID REQUEST_ID = UUID.fromString("1ee95a1e-83f2-4a63-a7ba-6288e246ac76");
  private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
  private static final long TOKEN_EXPIRY_MILLIS = NOW.plusSeconds(120L).toEpochMilli();

  @AfterEach
  void clearTransactionContext() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void malformedRequestIsDeniedBeforeStartingOwnerReads() {
    AccountGameplayDelegationIssuanceRepository repository =
        mock(AccountGameplayDelegationIssuanceRepository.class);
    AccountGameplayDelegationSigner signer = mock(AccountGameplayDelegationSigner.class);
    AccountGameplayDelegationTokenRegistry registry =
        mock(AccountGameplayDelegationTokenRegistry.class);
    AccountGameplayDelegationCommittedIssuanceOwner owner =
        owner(repository, signer, registry, mock(PlatformTransactionManager.class));

    assertThatThrownBy(
            () ->
                owner.inspectCurrentCommittedIssuance(
                    UUID.fromString("00000000-0000-0000-0000-000000000000")))
        .isInstanceOf(OwnerUnavailableException.class)
        .hasNoCause();

    verifyNoInteractions(repository, signer, registry);
  }

  @Test
  void refusesToJoinAnAmbientAccountTransaction() {
    AccountGameplayDelegationIssuanceRepository repository =
        mock(AccountGameplayDelegationIssuanceRepository.class);
    AccountGameplayDelegationSigner signer = mock(AccountGameplayDelegationSigner.class);
    AccountGameplayDelegationTokenRegistry registry =
        mock(AccountGameplayDelegationTokenRegistry.class);
    AccountGameplayDelegationCommittedIssuanceOwner owner =
        owner(repository, signer, registry, mock(PlatformTransactionManager.class));
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> owner.inspectCurrentCommittedIssuance(REQUEST_ID))
        .isInstanceOf(OwnerUnavailableException.class)
        .hasNoCause();

    verifyNoInteractions(repository, signer, registry);
  }

  @Test
  void activationRefusesAmbientTransactionBeforeOwnerOrRedisReads() {
    AccountGameplayDelegationIssuanceRepository repository =
        mock(AccountGameplayDelegationIssuanceRepository.class);
    AccountGameplayDelegationSigner signer = mock(AccountGameplayDelegationSigner.class);
    AccountGameplayDelegationTokenRegistry registry =
        mock(AccountGameplayDelegationTokenRegistry.class);
    AccountGameplayDelegationCommittedIssuanceOwner owner =
        owner(repository, signer, registry, mock(PlatformTransactionManager.class));
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> owner.activateCommittedIssuance(REQUEST_ID))
        .isInstanceOf(OwnerUnavailableException.class)
        .hasNoCause();

    verifyNoInteractions(repository, signer, registry);
  }

  @Test
  void recoveryActivationChecksCurrentRetainedKeyOutsideSqlBeforeAndAfterActiveReadback() {
    AccountGameplayDelegationIssuanceRepository repository =
        mock(AccountGameplayDelegationIssuanceRepository.class);
    AccountGameplayDelegationSigner signer = mock(AccountGameplayDelegationSigner.class);
    AccountGameplayDelegationTokenRegistry registry =
        mock(AccountGameplayDelegationTokenRegistry.class);
    TestTransactionManager transactionManager = new TestTransactionManager();
    CommittedCandidateVerificationData committed = committedCandidate();
    AccountGameplayDelegationRedisClient.ActiveRegistrationReceipt receipt = activeReceipt();
    when(repository.readCurrentCommittedCandidate(REQUEST_ID)).thenReturn(committed, committed);
    when(registry.activateCommittedCandidate(committed)).thenReturn(receipt);
    doAnswer(
            invocation -> {
              org.assertj.core.api.Assertions.assertThat(
                      TransactionSynchronizationManager.isActualTransactionActive())
                  .isTrue();
              return null;
            })
        .when(signer)
        .requireCurrentCommittedRecoverySigner(committed);
    doAnswer(
            invocation -> {
              org.assertj.core.api.Assertions.assertThat(
                      TransactionSynchronizationManager.isActualTransactionActive())
                  .isFalse();
              return null;
            })
        .when(signer)
        .requireRetainedCommittedPublicKey(committed);
    AccountGameplayDelegationCommittedIssuanceOwner owner =
        owner(repository, signer, registry, transactionManager);

    var active = owner.activateCommittedIssuance(REQUEST_ID);

    org.assertj.core.api.Assertions.assertThat(active.tokenSha256()).isEqualTo("a".repeat(64));
    var sequence = inOrder(repository, signer, registry);
    sequence.verify(repository).readCurrentCommittedCandidate(REQUEST_ID);
    sequence.verify(signer).requireCurrentCommittedRecoverySigner(committed);
    sequence.verify(signer).requireRetainedCommittedPublicKey(committed);
    sequence.verify(registry).activateCommittedCandidate(committed);
    sequence.verify(repository).readCurrentCommittedCandidate(REQUEST_ID);
    sequence.verify(signer).requireCurrentCommittedRecoverySigner(committed);
    sequence.verify(signer).requireRetainedCommittedPublicKey(committed);
    verify(repository, times(2)).readCurrentCommittedCandidate(REQUEST_ID);
  }

  @Test
  void missingRetainedKeyDeniesBeforeRegistryActivation() {
    AccountGameplayDelegationIssuanceRepository repository =
        mock(AccountGameplayDelegationIssuanceRepository.class);
    AccountGameplayDelegationSigner signer = mock(AccountGameplayDelegationSigner.class);
    AccountGameplayDelegationTokenRegistry registry =
        mock(AccountGameplayDelegationTokenRegistry.class);
    CommittedCandidateVerificationData committed = committedCandidate();
    when(repository.readCurrentCommittedCandidate(REQUEST_ID)).thenReturn(committed);
    doAnswer(invocation -> null).when(signer).requireCurrentCommittedRecoverySigner(committed);
    doAnswer(
            invocation -> {
              throw new IllegalStateException("retained signing key is unavailable");
            })
        .when(signer)
        .requireRetainedCommittedPublicKey(committed);
    AccountGameplayDelegationCommittedIssuanceOwner owner =
        owner(repository, signer, registry, new TestTransactionManager());

    assertThatThrownBy(() -> owner.activateCommittedIssuance(REQUEST_ID))
        .isInstanceOf(OwnerUnavailableException.class)
        .hasNoCause();

    verify(registry, never()).activateCommittedCandidate(committed);
  }

  private static CommittedCandidateVerificationData committedCandidate() {
    UUID accountId = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
    UUID operationId = UUID.fromString("c5c31332-e560-41c8-a55a-97674e7a317c");
    long expiresAt = TOKEN_EXPIRY_MILLIS / 1_000L;
    var identity =
        new AccountGameplayDelegationPendingIdentity(
            operationId,
            REQUEST_ID,
            accountId,
            "spiffe://firemud/ns/test/sa/game-session-service",
            UUID.fromString("e16fdce5-96eb-49e1-a9bd-3f429816cbf0"),
            AccountGameplayCredentialRequestBindingFixture.binding(),
            "b".repeat(64),
            UUID.fromString("1a7c3d0c-ab15-4f86-b5af-9e29bc7d3543"),
            NOW.getEpochSecond(),
            NOW.getEpochSecond(),
            expiresAt);
    CommittedCandidateVerificationData committed = mock(CommittedCandidateVerificationData.class);
    when(committed.identity()).thenReturn(identity);
    when(committed.authoritySnapshot())
        .thenReturn(
            new AccountAuthoritySnapshot(accountId, 1L, 1L, 1L, 1L, 1L, 1L, Optional.empty()));
    when(committed.evidenceBundleReference())
        .thenReturn(new EvidenceBundleReference("1", "1", "2", "1", "c".repeat(64)));
    when(committed.tokenSha256()).thenReturn("a".repeat(64));
    when(committed.signerKid()).thenReturn("account-key-42");
    when(committed.signerGeneration()).thenReturn("42");
    when(committed.canonicalRegistryRecordSha256()).thenReturn("d".repeat(64));
    when(committed.commitProofSha256()).thenReturn("e".repeat(64));
    when(committed.signerCorrespondence())
        .thenReturn(mock(AccountGameplayDelegationSigner.AuthenticatedSignerCorrespondence.class));
    when(committed.envelopeKeyId()).thenReturn("response-key-1");
    when(committed.envelopeSha256()).thenReturn("f".repeat(64));
    when(committed.envelopeBytesLength()).thenReturn(64);
    when(committed.responseRecoveryExpiryEpochMillis()).thenReturn(expiresAt * 1_000L);
    when(committed.registryAbsoluteExpiryMillis()).thenReturn(TOKEN_EXPIRY_MILLIS + 30_000L);
    when(committed.registrationLocalAofCount()).thenReturn(1L);
    when(committed.registrationReplicaAofCount()).thenReturn(1L);
    when(committed.registrationOutcome())
        .thenReturn(AccountGameplayDelegationRedisClient.PendingRegistrationOutcome.CREATED);
    return committed;
  }

  private static AccountGameplayDelegationRedisClient.ActiveRegistrationReceipt activeReceipt() {
    AccountGameplayDelegationRedisClient.ActiveRegistrationReceipt receipt =
        mock(AccountGameplayDelegationRedisClient.ActiveRegistrationReceipt.class);
    long expiry = TOKEN_EXPIRY_MILLIS + 30_000L;
    when(receipt.tokenKey())
        .thenReturn(AccountGameplayDelegationRedisClient.TOKEN_KEY_PREFIX + "a".repeat(64));
    when(receipt.tokenHash()).thenReturn("a".repeat(64));
    when(receipt.operationId()).thenReturn("c5c31332-e560-41c8-a55a-97674e7a317c");
    when(receipt.requestId()).thenReturn(REQUEST_ID.toString());
    when(receipt.accountId()).thenReturn("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
    when(receipt.canonicalActiveRecordSha256()).thenReturn("1".repeat(64));
    when(receipt.commitProofSha256()).thenReturn("e".repeat(64));
    when(receipt.registryVersion()).thenReturn(2L);
    when(receipt.absoluteExpiryMillis()).thenReturn(expiry);
    when(receipt.localAofCount()).thenReturn(1L);
    when(receipt.replicaAofCount()).thenReturn(1L);
    when(receipt.outcome())
        .thenReturn(AccountGameplayDelegationRedisClient.ActiveRegistrationOutcome.ACTIVATED);
    return receipt;
  }

  private static AccountGameplayDelegationCommittedIssuanceOwner owner(
      AccountGameplayDelegationIssuanceRepository repository,
      AccountGameplayDelegationSigner signer,
      AccountGameplayDelegationTokenRegistry registry,
      PlatformTransactionManager transactionManager) {
    return new AccountGameplayDelegationCommittedIssuanceOwner(
        repository, signer, registry, transactionManager, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
      return false;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
