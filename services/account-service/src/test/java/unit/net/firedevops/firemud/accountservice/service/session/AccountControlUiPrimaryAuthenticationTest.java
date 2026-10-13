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
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.mapper.AccountMapper;
import net.firedevops.firemud.accountservice.repository.AccountEmailLoginChallengeRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
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
    when(accounts.findByEmail("creator@example.test")).thenReturn(Optional.of(account));
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
    when(accounts.findByEmail(account.getEmail())).thenReturn(Optional.of(account));
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
