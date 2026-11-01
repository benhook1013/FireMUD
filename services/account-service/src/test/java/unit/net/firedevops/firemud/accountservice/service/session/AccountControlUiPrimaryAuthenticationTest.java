package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mkammerer.argon2.Argon2Factory;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountDto;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.mapper.AccountMapper;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountEmailLoginChallengeRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Actual primary-auth secret verification; repositories/transaction markers are explicit doubles.
 */
@ExtendWith(MockitoExtension.class)
class AccountControlUiPrimaryAuthenticationTest {
  @Mock AccountRepository accounts;
  @Mock AccountAuthorityOutboxRepository authorityOutbox;
  @Mock AccountPasswordResetOperationRepository passwordResetOperations;
  @Mock AccountLogoutAllOperationRepository logoutAllOperations;
  @Mock AccountSecurityStateOperationRepository securityStateOperations;
  @Mock AccountEmailLoginChallengeRepository challenges;
  @Mock AccountMapper mapper;
  @Mock PlatformTransactionManager transactions;
  @InjectMocks AccountServiceImpl primary;
  Account account;

  @BeforeEach
  void setup() {
    account = new Account();
    account.setId(41L);
    account.setAccountUuid(UUID.randomUUID());
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    account.setAccountUuidSourceNumericId(account.getId());
    account.setEmail("creator@example.test");
    account.setUsername("test-creator");
    account.setLoginAuthModes("PASSWORD");
    account.setPasswordHash(hash("test-only-primary-secret"));
    TransactionSynchronizationManager.setActualTransactionActive(true);
  }

  @AfterEach
  void clear() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void actualPasswordProducesCanonicalActorOnlyForEligibleAccount() {
    select(account, account);
    when(mapper.toDto(account))
        .thenReturn(
            new AccountDto(
                account.getAccountUuid().toString(),
                account.getUsername(),
                account.getEmail(),
                "USER",
                false));
    var actor =
        primary.authenticateControlUiPrimaryIdentity(
            account.getEmail(), "test-only-primary-secret");
    assertThat(actor.accountId()).isEqualTo(account.getAccountUuid());
    assertThat(actor.toString()).doesNotContain("secret", account.getEmail());
    assertThatThrownBy(
            () -> primary.authenticateControlUiPrimaryIdentity(account.getEmail(), "wrong-secret"))
        .isInstanceOf(RuntimeException.class);
    account.setLifecycleState(AccountLifecycleState.SECURITY_LOCKED);
    assertThatThrownBy(
            () ->
                primary.authenticateControlUiPrimaryIdentity(
                    account.getEmail(), "test-only-primary-secret"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void exactOtpIsConsumedAndFailedPasswordCannotConsumeIt() {
    account.setLoginAuthModes("EMAIL_OTP");
    var otp = new AccountEmailLoginChallenge();
    otp.setId(3L);
    otp.setAccountId(account.getId());
    otp.setCodeHash(hash("test-only-otp"));
    otp.setExpiresAt(LocalDateTime.now().plusMinutes(1));
    select(account, account);
    when(challenges.findByAccountId(account.getId())).thenReturn(Optional.of(otp));
    assertThatThrownBy(
            () -> primary.authenticateControlUiPrimaryIdentity(account.getEmail(), "wrong-secret"))
        .isInstanceOf(RuntimeException.class);
    verify(challenges, never()).delete(otp);
    when(mapper.toDto(account))
        .thenReturn(
            new AccountDto(
                account.getAccountUuid().toString(),
                account.getUsername(),
                account.getEmail(),
                "USER",
                false));
    assertThat(
            primary
                .authenticateControlUiPrimaryIdentity(account.getEmail(), "test-only-otp")
                .accountId())
        .isEqualTo(account.getAccountUuid());
    verify(challenges).delete(otp);
  }

  @Test
  void passwordIsCheckedAgainstTheLockedCurrentRowAfterAConcurrentReset() {
    Account current = accountWithPassword("test-only-reset-password");
    select(account, current);
    when(mapper.toDto(current))
        .thenReturn(
            new AccountDto(
                current.getAccountUuid().toString(),
                current.getUsername(),
                current.getEmail(),
                "USER",
                false));

    assertThatThrownBy(
            () ->
                primary.authenticateControlUiPrimaryIdentity(
                    account.getEmail(), "test-only-primary-secret"))
        .isExactlyInstanceOf(AuthenticationException.class);
    assertThat(
            primary
                .authenticateControlUiPrimaryIdentity(
                    account.getEmail(), "test-only-reset-password")
                .accountId())
        .isEqualTo(current.getAccountUuid());
    verify(accounts, org.mockito.Mockito.times(2)).findByIdForUpdate(account.getId());
  }

  @Test
  void changedEmailSelectorIsRejectedAfterTheAccountRowIsLocked() {
    Account current = accountWithPassword("test-only-primary-secret");
    current.setEmail("reassigned@example.test");
    select(account, current);

    assertThatThrownBy(
            () ->
                primary.authenticateControlUiPrimaryIdentity(
                    account.getEmail(), "test-only-primary-secret"))
        .isExactlyInstanceOf(AuthenticationException.class);
    verify(mapper, never()).toDto(current);
  }

  @Test
  void existingUsernameFallbackStillResolvesAndLocksTheSelectedAccount() {
    when(accounts.findByEmail(account.getUsername())).thenReturn(Optional.empty());
    when(accounts.findByUsername(account.getUsername())).thenReturn(Optional.of(account));
    when(accounts.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
    when(mapper.toDto(account))
        .thenReturn(
            new AccountDto(
                account.getAccountUuid().toString(),
                account.getUsername(),
                account.getEmail(),
                "USER",
                false));

    assertThat(
            primary
                .authenticateControlUiPrimaryIdentity(
                    account.getUsername(), "test-only-primary-secret")
                .accountId())
        .isEqualTo(account.getAccountUuid());
    verify(accounts).findByIdForUpdate(account.getId());
  }

  @Test
  void missingAndReadOnlyTransactionDenyBeforeCredentialAccess() {
    TransactionSynchronizationManager.clear();
    assertThatThrownBy(
            () ->
                primary.authenticateControlUiPrimaryIdentity(
                    account.getEmail(), "test-only-primary-secret"))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertThatThrownBy(
            () ->
                primary.authenticateControlUiPrimaryIdentity(
                    account.getEmail(), "test-only-primary-secret"))
        .isInstanceOf(IllegalStateException.class);
  }

  private void select(Account selected, Account locked) {
    when(accounts.findByEmail(selected.getEmail())).thenReturn(Optional.of(selected));
    when(accounts.findByIdForUpdate(selected.getId())).thenReturn(Optional.of(locked));
  }

  private Account accountWithPassword(String password) {
    Account current = new Account();
    current.setId(account.getId());
    current.setAccountUuid(account.getAccountUuid());
    current.setAccountUuidProvenance(account.getAccountUuidProvenance());
    current.setAccountUuidSourceNumericId(account.getAccountUuidSourceNumericId());
    current.setEmail(account.getEmail());
    current.setUsername(account.getUsername());
    current.setLoginAuthModes(account.getLoginAuthModes());
    current.setPasswordHash(hash(password));
    return current;
  }

  private static String hash(String secret) {
    char[] chars = secret.toCharArray();
    var argon = Argon2Factory.create();
    try {
      return argon.hash(2, 4096, 1, chars);
    } finally {
      argon.wipeArray(chars);
    }
  }
}
