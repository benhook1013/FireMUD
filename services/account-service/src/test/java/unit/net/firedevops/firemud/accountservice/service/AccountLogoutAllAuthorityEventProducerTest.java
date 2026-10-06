package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountLogoutRequestDigest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceEventReadback;
import net.firedevops.firemud.accountservice.service.AccountLogoutAllAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountLogoutAllDraftSourceChangeRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountLogoutAllAuthorityEventProducerTest {
  private static final UUID ACCOUNT_UUID = UUID.fromString("7439d275-a8bd-4ad2-8993-3c9d30e86472");
  private static final String TOKEN_PROFILE = "control-ui";
  private static final String TOKEN_HASH = "b".repeat(64);
  private static final String REQUEST_DIGEST =
      AccountLogoutRequestDigest.accountLogoutAll(ACCOUNT_UUID, TOKEN_PROFILE, TOKEN_HASH);

  @Test
  void pendingSourceChangeExposesOnlyItsStableRecoveryIdentity() {
    UUID changeId = UUID.randomUUID();

    var pending =
        new AccountLogoutAllDraftSourceChangeRepository.PendingSourceChangeException(changeId);

    assertThat(pending.sourceChangeId()).isEqualTo(changeId);
    assertThat(pending.getMessage()).contains(changeId.toString()).doesNotContain(TOKEN_HASH);
  }

  @Test
  void rejectsMalformedRequestEvidenceBeforeDatabaseOrTransactionInteraction() {
    Collaborators collaborators = new Collaborators();
    AccountLogoutAllAuthorityEventProducer producer = newProducer(collaborators);
    Account account = account();
    var expected =
        new AccountAuthorityGenerationRepository.ScopeState(
            AccountAuthorityGenerationRepository.AuthorityScope.account(ACCOUNT_UUID),
            1L,
            1L,
            new AccountAuthorityGenerationRepository.IssuanceFence(ACCOUNT_UUID, 1L, 1L));

    assertThatThrownBy(
            () ->
                producer.commit(
                    new UUID(0L, 0L),
                    1,
                    REQUEST_DIGEST,
                    TOKEN_PROFILE,
                    TOKEN_HASH,
                    account,
                    expected))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-nil");
    assertThatThrownBy(
            () ->
                producer.commit(
                    UUID.randomUUID(),
                    0,
                    REQUEST_DIGEST,
                    TOKEN_PROFILE,
                    TOKEN_HASH,
                    account,
                    expected))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
    assertThatThrownBy(
            () ->
                producer.commit(
                    UUID.randomUUID(),
                    1,
                    "A".repeat(64),
                    TOKEN_PROFILE,
                    TOKEN_HASH,
                    account,
                    expected))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lowercase SHA-256");
    assertThatThrownBy(
            () ->
                producer.commit(
                    UUID.randomUUID(),
                    1,
                    REQUEST_DIGEST,
                    TOKEN_PROFILE,
                    "b".repeat(63),
                    account,
                    expected))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("token hash");
    assertThatThrownBy(
            () ->
                producer.commit(
                    UUID.randomUUID(),
                    1,
                    REQUEST_DIGEST,
                    "private-player-delegation",
                    TOKEN_HASH,
                    account,
                    expected))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("token profile");

    collaborators.verifyUnused();
  }

  @Test
  void rejectsAmbientTransactionAndDoesNotUseExpectedStateAsCallerAuthorization() {
    Collaborators collaborators = new Collaborators();
    AccountLogoutAllAuthorityEventProducer producer = newProducer(collaborators);
    Account account = account();
    var expected =
        new AccountAuthorityGenerationRepository.ScopeState(
            AccountAuthorityGenerationRepository.AuthorityScope.account(ACCOUNT_UUID),
            1L,
            1L,
            new AccountAuthorityGenerationRepository.IssuanceFence(ACCOUNT_UUID, 1L, 1L));
    boolean previouslyActive = TransactionSynchronizationManager.isActualTransactionActive();
    TransactionSynchronizationManager.setActualTransactionActive(true);

    try {
      assertThatThrownBy(
              () ->
                  producer.commit(
                      UUID.randomUUID(),
                      1,
                      REQUEST_DIGEST,
                      TOKEN_PROFILE,
                      TOKEN_HASH,
                      account,
                      expected))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("without an ambient transaction");
      collaborators.verifyUnused();
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(previouslyActive);
    }
  }

  @Test
  void rejectsMismatchedExpectedSourceScopeBeforeDatabaseInteraction() {
    Collaborators collaborators = new Collaborators();
    AccountLogoutAllAuthorityEventProducer producer = newProducer(collaborators);
    Account account = account();
    var wrongScope =
        new AccountAuthorityGenerationRepository.ScopeState(
            AccountAuthorityGenerationRepository.AuthorityScope.account(UUID.randomUUID()),
            1L,
            1L,
            new AccountAuthorityGenerationRepository.IssuanceFence(ACCOUNT_UUID, 1L, 1L));

    assertThatThrownBy(
            () ->
                producer.commit(
                    UUID.randomUUID(),
                    1,
                    REQUEST_DIGEST,
                    TOKEN_PROFILE,
                    TOKEN_HASH,
                    account,
                    wrongScope))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected Account authority scope");

    collaborators.verifyUnused();
  }

  private AccountLogoutAllAuthorityEventProducer newProducer(Collaborators collaborators) {
    AccountAuthoritySourceEventReadback readback =
        new AccountAuthoritySourceEventReadback(
            collaborators.outboxRepository(),
            collaborators.passwordResetRepository(),
            collaborators.logoutAllRepository(),
            org.mockito.Mockito.mock(
                net.firedevops.firemud.accountservice.repository
                    .AccountSecurityStateOperationRepository.class));
    return new AccountLogoutAllAuthorityEventProducer(
        collaborators.accountRepository(),
        collaborators.generationRepository(),
        collaborators.outboxRepository(),
        collaborators.logoutAllRepository(),
        readback,
        collaborators.dsl(),
        collaborators.transactionManager());
  }

  private Account account() {
    Account account = new Account();
    account.setId(7L);
    account.setAccountUuid(ACCOUNT_UUID);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT);
    account.setAccountUuidSourceNumericId(7L);
    return account;
  }

  private record Collaborators(
      AccountRepository accountRepository,
      AccountAuthorityGenerationRepository generationRepository,
      AccountAuthorityOutboxRepository outboxRepository,
      AccountPasswordResetOperationRepository passwordResetRepository,
      AccountLogoutAllOperationRepository logoutAllRepository,
      DSLContext dsl,
      PlatformTransactionManager transactionManager) {
    private Collaborators() {
      this(
          mock(AccountRepository.class),
          mock(AccountAuthorityGenerationRepository.class),
          mock(AccountAuthorityOutboxRepository.class),
          mock(AccountPasswordResetOperationRepository.class),
          mock(AccountLogoutAllOperationRepository.class),
          mock(DSLContext.class),
          mock(PlatformTransactionManager.class));
    }

    private void verifyUnused() {
      verifyNoInteractions(
          accountRepository,
          generationRepository,
          outboxRepository,
          passwordResetRepository,
          logoutAllRepository,
          dsl,
          transactionManager);
    }
  }
}
