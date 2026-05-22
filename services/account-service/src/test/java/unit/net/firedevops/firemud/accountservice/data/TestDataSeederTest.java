package net.firedevops.firemud.accountservice.data;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.entity.Profile;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.ProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.DefaultApplicationArguments;

class TestDataSeederTest {
  @Mock AccountRepository accountRepository;
  @Mock AccountTenantMembershipRepository accountTenantMembershipRepository;
  @Mock ProfileRepository profileRepository;

  private TestDataSeeder seeder;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    seeder =
        new TestDataSeeder(accountRepository, accountTenantMembershipRepository, profileRepository);
  }

  @Test
  void runSeedsDemoAccountMembershipAndProfileWhenMissing() throws Exception {
    Account account = new Account();
    account.setId(1L);
    account.setEmail("demo@example.com");

    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.empty());
    when(accountRepository.save(any(Account.class))).thenReturn(account);
    when(accountTenantMembershipRepository.existsByAccountIdAndTenantId(1L, 1L)).thenReturn(false);
    when(profileRepository.findByAccountIdAndTenantId(1L, 1L)).thenReturn(Optional.empty());

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(accountRepository).save(any(Account.class));
    verify(accountTenantMembershipRepository).save(any(AccountTenantMembership.class));
    verify(profileRepository).save(any(Profile.class));
  }

  @Test
  void runReassertsExistingDemoBootstrapAccountState() throws Exception {
    Account account = new Account();
    account.setId(1L);
    account.setEmail("demo@example.com");

    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));
    when(accountRepository.save(any(Account.class))).thenReturn(account);
    when(accountTenantMembershipRepository.existsByAccountIdAndTenantId(1L, 1L)).thenReturn(true);
    when(profileRepository.findByAccountIdAndTenantId(1L, 1L))
        .thenReturn(Optional.of(new Profile()));

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(accountRepository).save(any(Account.class));
    verify(accountTenantMembershipRepository, never()).save(any(AccountTenantMembership.class));
    verify(profileRepository, never()).save(any(Profile.class));
  }
}
