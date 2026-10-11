package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.CommittedIssuanceReadback;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationAuthorityProjection;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationIssuanceCommitService;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationSigner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationSignerTestFixtureFactory;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationSignerTestFixtureFactory.Fixture;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationTokenRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountGameplayDelegationIssuanceCommitServiceTest {
  private static final UUID OPERATION_ID = UUID.fromString("5414e55d-0393-4561-ac3d-cb916a08d3f0");
  private static final UUID REQUEST_ID = UUID.fromString("1ee95a1e-83f2-4a63-a7ba-6288e246ac76");
  private static final UUID ACCOUNT_ID = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
  private static final Clock SIGNING_CLOCK =
      Clock.fixed(Instant.parse("2026-10-05T00:00:30Z"), ZoneOffset.UTC);

  @TempDir Path temporaryDirectory;

  @AfterEach
  void clearTransactionContext() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactCommittedRetryReturnsOnlyOwnerMetadataWithoutRemintOrRedisWrite() {
    AccountGameplayDelegationSigner signer = mock(AccountGameplayDelegationSigner.class);
    AccountGameplayDelegationTokenRegistry registry =
        mock(AccountGameplayDelegationTokenRegistry.class);
    AccountGameplayDelegationAuthorityProjection projection =
        mock(AccountGameplayDelegationAuthorityProjection.class);
    AccountGameplayDelegationIssuanceRepository repository =
        mock(AccountGameplayDelegationIssuanceRepository.class);
    CommittedIssuanceReadback readback =
        new CommittedIssuanceReadback(
            OPERATION_ID,
            REQUEST_ID,
            ACCOUNT_ID,
            "a".repeat(64),
            "b".repeat(64),
            "account-kid-1",
            "7",
            "c".repeat(64));
    when(repository.readCommittedProof(REQUEST_ID)).thenReturn(Optional.of(readback));

    AccountGameplayDelegationIssuanceCommitService service =
        service(signer, registry, projection, repository);
    AccountGameplayDelegationIssuanceCommitService.CommitResult result =
        service.commitPendingCandidate(REQUEST_ID);

    assertThat(result.outcome())
        .isEqualTo(AccountGameplayDelegationIssuanceCommitService.Outcome.EXACT_RETRY);
    assertThat(result.operationId()).isEqualTo(OPERATION_ID);
    assertThat(result.requestId()).isEqualTo(REQUEST_ID);
    assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
    assertThat(result.proofSha256()).isEqualTo("c".repeat(64));
    assertThat(result.toString()).doesNotContain("b".repeat(64));
    verify(repository).readCommittedProof(REQUEST_ID);
    verifyNoInteractions(signer, registry, projection);
  }

  @Test
  void refusesToPerformNetworkWorkInsideAnAmbientAccountTransaction() {
    AccountGameplayDelegationSigner signer = mock(AccountGameplayDelegationSigner.class);
    AccountGameplayDelegationTokenRegistry registry =
        mock(AccountGameplayDelegationTokenRegistry.class);
    AccountGameplayDelegationAuthorityProjection projection =
        mock(AccountGameplayDelegationAuthorityProjection.class);
    AccountGameplayDelegationIssuanceRepository repository =
        mock(AccountGameplayDelegationIssuanceRepository.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    AccountGameplayDelegationIssuanceCommitService service =
        service(signer, registry, projection, repository);

    assertThatThrownBy(() -> service.commitPendingCandidate(REQUEST_ID))
        .isInstanceOf(
            AccountGameplayDelegationIssuanceCommitService.IssuanceCommitUnavailableException
                .class);
    verifyNoInteractions(signer, registry, projection, repository);
  }

  @Test
  void genuineRsaSignerCompositionCannotCommitWithoutOwnerRegistrationReceipt() throws Exception {
    Fixture signerFixture =
        AccountGameplayDelegationSignerTestFixtureFactory.create(temporaryDirectory);
    AccountGameplayDelegationTokenRegistry registry =
        mock(AccountGameplayDelegationTokenRegistry.class);
    AccountGameplayDelegationAuthorityProjection projection =
        mock(AccountGameplayDelegationAuthorityProjection.class);
    AccountGameplayDelegationIssuanceRepository repository = signerFixture.issuanceRepository();
    when(repository.readCommittedProof(signerFixture.requestId())).thenReturn(Optional.empty());
    when(registry.registerPending(signerFixture.requestId())).thenReturn(null);

    AccountGameplayDelegationIssuanceCommitService service =
        new AccountGameplayDelegationIssuanceCommitService(
            signerFixture.signer(),
            registry,
            projection,
            repository,
            signerFixture.transactionManager(),
            SIGNING_CLOCK);

    assertThatThrownBy(() -> service.commitPendingCandidate(signerFixture.requestId()))
        .isInstanceOf(
            AccountGameplayDelegationIssuanceCommitService.IssuanceCommitUnavailableException.class)
        .hasNoCause();

    verify(repository).bindSignedCandidate(eq(signerFixture.requestId()), anyString(), eq("42"));
    verify(signerFixture.responseEnvelopeService())
        .sealPendingCandidate(eq(signerFixture.requestId()), any(), anyString());
    verify(registry).registerPending(signerFixture.requestId());
    verify(projection).observeCurrent(signerFixture.accountId());
    verify(repository, never()).commitPendingCandidate(any(), any(), any(), any(), any());
    verify(repository).readCommittedProof(signerFixture.requestId());
  }

  private static AccountGameplayDelegationIssuanceCommitService service(
      AccountGameplayDelegationSigner signer,
      AccountGameplayDelegationTokenRegistry registry,
      AccountGameplayDelegationAuthorityProjection projection,
      AccountGameplayDelegationIssuanceRepository repository) {
    return new AccountGameplayDelegationIssuanceCommitService(
        signer,
        registry,
        projection,
        repository,
        mock(PlatformTransactionManager.class),
        Clock.fixed(Instant.parse("2027-01-15T12:00:00Z"), ZoneOffset.UTC));
  }
}
