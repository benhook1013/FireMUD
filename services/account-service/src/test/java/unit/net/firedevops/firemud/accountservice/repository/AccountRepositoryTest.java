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

  @Test
  void freshNonActiveAccountIsRejectedBeforeInsertOrSourceInitialization() {
    DSLContext dsl = mock(DSLContext.class);
    AccountAuthoritySourceEvidenceRepository sourceEvidence =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    AccountRepository repository = new AccountRepository(dsl, sourceEvidence);
    Account account = new Account();
    account.setEmail("inactive-fresh@example.test");
    account.setLifecycleState(AccountLifecycleState.DEACTIVATED_PENDING_DELETE);

    assertThatThrownBy(() -> repository.save(account))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Fresh Accounts must be created in the ACTIVE lifecycle state");

    verifyNoInteractions(dsl, sourceEvidence);
  }

  @Test
  void findByIdForUpdateRequiresAnActiveWritableOwnerTransaction()
      throws ReflectiveOperationException {
    DSLContext dsl = mock(DSLContext.class);
    AccountAuthoritySourceEvidenceRepository sourceEvidence =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    AccountRepository repository = new AccountRepository(dsl, sourceEvidence);

    assertThatThrownBy(() -> repository.findByIdForUpdate(42L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active writable owner transaction");

    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    try {
      assertThatThrownBy(() -> repository.findByIdForUpdate(42L))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("active writable owner transaction");
    } finally {
      TransactionSynchronizationManager.clear();
    }

    Transactional transactional =
        AccountRepository.class
            .getMethod("findByIdForUpdate", Long.class)
            .getAnnotation(Transactional.class);
    assertThat(transactional).isNotNull();
    assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    verifyNoInteractions(dsl, sourceEvidence);
  }
}
