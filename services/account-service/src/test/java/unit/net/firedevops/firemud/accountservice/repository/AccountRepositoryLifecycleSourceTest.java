package unit.net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.entity.AccountLoginAuthModes;
import net.firedevops.firemud.accountservice.jooq.tables.records.AccountsRecord;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AccountRepositoryLifecycleSourceTest {
  private static final long ACCOUNT_NUMERIC_ID = 42L;
  private static final UUID ACCOUNT_UUID = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
  private static final String USERNAME = "lifecycle-source";
  private static final String EMAIL = "lifecycle-source@example.test";
  private static final String PASSWORD_HASH = "not-a-real-password-hash";

  @Test
  void updatePreservesLockedLifecycleStateAndPassesExactReadbackIntoSourceEvidence() {
    DSLContext dsl = mock(DSLContext.class, RETURNS_DEEP_STUBS);
    AccountAuthoritySourceEvidenceRepository sourceEvidence =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    AccountRepository repository = new AccountRepository(dsl, sourceEvidence);
    Account account = account();
    AccountsRecord before = persisted(AccountLifecycleState.SECURITY_LOCKED);
    AccountsRecord updated = persisted(AccountLifecycleState.SECURITY_LOCKED);
    var selectForUpdate =
        dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(ACCOUNT_NUMERIC_ID)).forUpdate();
    var updateBeforeLifecycle =
        dsl.update(ACCOUNTS)
            .set(ACCOUNTS.USERNAME, USERNAME)
            .set(ACCOUNTS.EMAIL, EMAIL)
            .set(ACCOUNTS.PASSWORD_HASH, PASSWORD_HASH)
            .set(ACCOUNTS.ROLE, "player")
            .set(ACCOUNTS.EMAIL_VERIFIED, false)
            .set(ACCOUNTS.LOGIN_AUTH_MODES, AccountLoginAuthModes.DEFAULT_SERIALIZED);
    var updateReturning =
        updateBeforeLifecycle
            .set(ACCOUNTS.LIFECYCLE_STATE, AccountLifecycleState.SECURITY_LOCKED.storageValue())
            .where(ACCOUNTS.ID.eq(ACCOUNT_NUMERIC_ID))
            .and(ACCOUNTS.ACCOUNT_UUID.eq(ACCOUNT_UUID))
            .and(
                ACCOUNTS.ACCOUNT_UUID_PROVENANCE.eq(
                    AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT.name()))
            .and(ACCOUNTS.ACCOUNT_UUID_SOURCE_NUMERIC_ID.eq(ACCOUNT_NUMERIC_ID))
            .returning();

    doReturn(before).when(selectForUpdate).fetchOne();
    doReturn(updated).when(updateReturning).fetchOne();
    clearInvocations(updateBeforeLifecycle);

    repository.save(account);

    ArgumentCaptor<AccountRepository.AccountUpdateEvidence> evidence =
        ArgumentCaptor.forClass(AccountRepository.AccountUpdateEvidence.class);
    verify(sourceEvidence).recordAccountUpdate(evidence.capture());
    assertThat(evidence.getValue().before().lifecycleState()).isEqualTo("SECURITY_LOCKED");
    assertThat(evidence.getValue().after().lifecycleState()).isEqualTo("SECURITY_LOCKED");
    assertThat(account.getLifecycleState()).isEqualTo(AccountLifecycleState.SECURITY_LOCKED);
    verify(updateBeforeLifecycle)
        .set(ACCOUNTS.LIFECYCLE_STATE, AccountLifecycleState.SECURITY_LOCKED.storageValue());
  }

  private static Account account() {
    Account account = new Account();
    account.setId(ACCOUNT_NUMERIC_ID);
    account.setAccountUuid(ACCOUNT_UUID);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    account.setAccountUuidSourceNumericId(ACCOUNT_NUMERIC_ID);
    account.setUsername(USERNAME);
    account.setEmail(EMAIL);
    account.setPasswordHash(PASSWORD_HASH);
    account.setRole("player");
    account.setLifecycleState(AccountLifecycleState.ACTIVE);
    return account;
  }

  private static AccountsRecord persisted(AccountLifecycleState lifecycleState) {
    return new AccountsRecord(
        ACCOUNT_NUMERIC_ID,
        USERNAME,
        EMAIL,
        PASSWORD_HASH,
        null,
        "player",
        false,
        AccountLoginAuthModes.DEFAULT_SERIALIZED,
        lifecycleState.storageValue(),
        ACCOUNT_UUID,
        AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT.name(),
        ACCOUNT_NUMERIC_ID,
        10L);
  }
}
