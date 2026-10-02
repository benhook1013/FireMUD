package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountAuthoritySourceEventReadbackTest {
  private static final UUID ACCOUNT_UUID =
      UUID.fromString("c980fa44-619e-4ca4-8ad6-75b0538a66a3");

  @Test
  void sequenceZeroRequiresTheOriginalPositiveBaselineAndNoCheckpoint() {
    AccountAuthorityOutboxRepository outbox = mock(AccountAuthorityOutboxRepository.class);
    AccountPasswordResetOperationRepository resets =
        mock(AccountPasswordResetOperationRepository.class);
    AccountLogoutAllOperationRepository logouts =
        mock(AccountLogoutAllOperationRepository.class);
    AccountAuthoritySourceEventReadback readback =
        new AccountAuthoritySourceEventReadback(outbox, resets, logouts);
    when(outbox.readCheckpoint("account:auth-authority:v1:account/" + ACCOUNT_UUID))
        .thenReturn(Optional.empty());
    boolean previouslyActive = TransactionSynchronizationManager.isActualTransactionActive();
    TransactionSynchronizationManager.setActualTransactionActive(true);

    try {
      var snapshot =
          readback.requireCurrentLatest(
              account(),
              new AccountAuthorityGenerationRepository.ScopeState(
                  AccountAuthorityGenerationRepository.AuthorityScope.account(ACCOUNT_UUID),
                  1L,
                  1L,
                  new AccountAuthorityGenerationRepository.IssuanceFence(ACCOUNT_UUID, 4L, 3L)));

      assertThat(snapshot.outboxSequence()).isZero();
      assertThat(snapshot.latestEvent()).isEmpty();
      verifyNoInteractions(resets, logouts);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(previouslyActive);
    }
  }

  @Test
  void sequenceZeroNeverRepairsAProgressedSourceWithoutHistory() {
    AccountAuthorityOutboxRepository outbox = mock(AccountAuthorityOutboxRepository.class);
    AccountAuthoritySourceEventReadback readback =
        new AccountAuthoritySourceEventReadback(
            outbox,
            mock(AccountPasswordResetOperationRepository.class),
            mock(AccountLogoutAllOperationRepository.class));
    when(outbox.readCheckpoint("account:auth-authority:v1:account/" + ACCOUNT_UUID))
        .thenReturn(Optional.empty());
    boolean previouslyActive = TransactionSynchronizationManager.isActualTransactionActive();
    TransactionSynchronizationManager.setActualTransactionActive(true);

    try {
      assertThatThrownBy(
              () ->
                  readback.requireCurrentLatest(
                      account(),
                      new AccountAuthorityGenerationRepository.ScopeState(
                          AccountAuthorityGenerationRepository.AuthorityScope.account(ACCOUNT_UUID),
                          2L,
                          2L,
                          new AccountAuthorityGenerationRepository.IssuanceFence(
                              ACCOUNT_UUID, 5L, 4L))))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("original positive 1/1 baseline");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(previouslyActive);
    }
  }

  @Test
  void presentCheckpointIsContradictoryAtPristineBaselineAndSingleCounterProgression() {
    AccountAuthorityOutboxRepository outbox = mock(AccountAuthorityOutboxRepository.class);
    AccountPasswordResetOperationRepository resets =
        mock(AccountPasswordResetOperationRepository.class);
    AccountLogoutAllOperationRepository logouts =
        mock(AccountLogoutAllOperationRepository.class);
    AccountAuthoritySourceEventReadback readback =
        new AccountAuthoritySourceEventReadback(outbox, resets, logouts);
    String streamKey = "account:auth-authority:v1:account/" + ACCOUNT_UUID;
    when(outbox.readCheckpoint(streamKey))
        .thenReturn(
            Optional.of(
                new Checkpoint(streamKey, 1L, "event-id", "sha256:" + "a".repeat(64))));
    boolean previouslyActive = TransactionSynchronizationManager.isActualTransactionActive();
    TransactionSynchronizationManager.setActualTransactionActive(true);

    try {
      assertRejectedCheckpoint(readback, 1L, 1L, "contradictory event history");
      assertRejectedCheckpoint(readback, 2L, 1L, "history is not proven");
      assertRejectedCheckpoint(readback, 1L, 2L, "history is not proven");

      verify(outbox, never()).findEvent(streamKey, 1L);
      verifyNoInteractions(resets, logouts);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(previouslyActive);
    }
  }

  private void assertRejectedCheckpoint(
      AccountAuthoritySourceEventReadback readback,
      long generation,
      long sourceVersion,
      String expectedMessage) {
    assertThatThrownBy(
            () ->
                readback.requireCurrentLatest(
                    account(),
                    new AccountAuthorityGenerationRepository.ScopeState(
                        AccountAuthorityGenerationRepository.AuthorityScope.account(ACCOUNT_UUID),
                        generation,
                        sourceVersion,
                        new AccountAuthorityGenerationRepository.IssuanceFence(
                            ACCOUNT_UUID, 4L, 3L))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(expectedMessage);
  }

  private Account account() {
    Account account = new Account();
    account.setId(11L);
    account.setAccountUuid(ACCOUNT_UUID);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT);
    account.setAccountUuidSourceNumericId(11L);
    account.setPasswordHash("unused-on-sequence-zero");
    return account;
  }
}
