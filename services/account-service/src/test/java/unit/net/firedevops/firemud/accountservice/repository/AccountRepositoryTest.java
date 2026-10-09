package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.jooq.Tables;
import net.firedevops.firemud.accountservice.jooq.tables.records.AccountsRecord;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountRepositoryTest {
  @Test
  void numericLockRejectsNonPositiveIdentityBeforeSql() {
    DSLContext dsl = mock(DSLContext.class);
    AccountAuthoritySourceEvidenceRepository sourceEvidence =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    AccountRepository repository = new AccountRepository(dsl, sourceEvidence);

    assertThatThrownBy(() -> repository.findByIdForUpdate(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("A positive persisted Account ID is required");
    assertThatThrownBy(() -> repository.findByIdForUpdate(0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("A positive persisted Account ID is required");
    assertThatThrownBy(() -> repository.findByIdForUpdate(-1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("A positive persisted Account ID is required");

    verifyNoInteractions(dsl, sourceEvidence);
  }

  @Test
  void uuidLockRejectsNullAndNilIdentityBeforeSql() {
    DSLContext dsl = mock(DSLContext.class);
    AccountAuthoritySourceEvidenceRepository sourceEvidence =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    AccountRepository repository = new AccountRepository(dsl, sourceEvidence);

    assertThatThrownBy(() -> repository.findByAccountUuidForUpdate(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("A non-nil Account UUID is required");
    assertThatThrownBy(() -> repository.findByAccountUuidForUpdate(new UUID(0L, 0L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("A non-nil Account UUID is required");

    verifyNoInteractions(dsl, sourceEvidence);
  }

  @Test
  void uuidLockRequiresWritableOwnerTransactionBeforeSql() {
    DSLContext dsl = mock(DSLContext.class);
    AccountAuthoritySourceEvidenceRepository sourceEvidence =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    AccountRepository repository = new AccountRepository(dsl, sourceEvidence);
    UUID accountUuid = UUID.randomUUID();
    boolean priorActive = TransactionSynchronizationManager.isActualTransactionActive();
    boolean priorReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    try {
      TransactionSynchronizationManager.setActualTransactionActive(false);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
      assertThatThrownBy(() -> repository.findByAccountUuidForUpdate(accountUuid))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Account UUID lock requires an active writable owner transaction");

      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
      assertThatThrownBy(() -> repository.findByAccountUuidForUpdate(accountUuid))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Account UUID lock requires an active writable owner transaction");
    } finally {
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(priorReadOnly);
      TransactionSynchronizationManager.setActualTransactionActive(priorActive);
    }

    verifyNoInteractions(dsl, sourceEvidence);
  }

  @Test
  void uuidLockRequiresMandatoryOwnerTransactionPropagation() throws NoSuchMethodException {
    Transactional transactional =
        AccountRepository.class
            .getMethod("findByAccountUuidForUpdate", UUID.class)
            .getAnnotation(Transactional.class);

    assertThat(transactional).isNotNull();
    assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
  }

  @Test
  void validLockReadsUseForUpdateOnTheExactAccountSelector() {
    DSLContext dsl = mock(DSLContext.class, RETURNS_DEEP_STUBS);
    AccountAuthoritySourceEvidenceRepository sourceEvidence =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    AccountRepository repository = new AccountRepository(dsl, sourceEvidence);
    UUID accountUuid = UUID.randomUUID();
    boolean priorActive = TransactionSynchronizationManager.isActualTransactionActive();
    boolean priorReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    try {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

      assertThat(repository.findByIdForUpdate(42L)).isEmpty();
      assertThat(repository.findByAccountUuidForUpdate(accountUuid)).isEmpty();

      verify(dsl.selectFrom(Tables.ACCOUNTS).where(Tables.ACCOUNTS.ID.eq(42L))).forUpdate();
      verify(dsl.selectFrom(Tables.ACCOUNTS).where(Tables.ACCOUNTS.ACCOUNT_UUID.eq(accountUuid)))
          .forUpdate();
    } finally {
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(priorReadOnly);
      TransactionSynchronizationManager.setActualTransactionActive(priorActive);
    }
  }

  @Test
  void hardDeletionFailsClosedWithoutSqlOrSourceEvidenceMutation() {
    DSLContext dsl = mock(DSLContext.class);
    AccountAuthoritySourceEvidenceRepository sourceEvidence =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    AccountRepository repository = new AccountRepository(dsl, sourceEvidence);
    Account account = new Account();
    account.setId(42L);

    assertThatThrownBy(() -> repository.delete(account))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "Account hard deletion is unavailable until the pending-deletion retention workflow exists");

    verifyNoInteractions(dsl, sourceEvidence);
  }

  @Test
  void genericSaveRejectsLifecycleChangeBeforeUpdateOrSourceEvidenceMutation() {
    DSLContext dsl = mock(DSLContext.class, RETURNS_DEEP_STUBS);
    AccountAuthoritySourceEvidenceRepository sourceEvidence =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    AccountRepository repository = new AccountRepository(dsl, sourceEvidence);
    Account account = new Account();
    account.setId(42L);
    account.setEmail("lifecycle@example.test");
    account.setLifecycleState(AccountLifecycleState.SECURITY_LOCKED);
    AccountsRecord before = mock(AccountsRecord.class);
    when(before.getLifecycleState()).thenReturn("ACTIVE");
    var selectForUpdate =
        dsl.selectFrom(Tables.ACCOUNTS).where(Tables.ACCOUNTS.ID.eq(42L)).forUpdate();
    doReturn(before).when(selectForUpdate).fetchOne();

    assertThatThrownBy(() -> repository.save(account))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lifecycle changes are unavailable");

    verify(dsl, never()).update(Tables.ACCOUNTS);
    verifyNoInteractions(sourceEvidence);
  }
}
