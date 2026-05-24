package net.firedevops.firemud.accountservice.data;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.entity.ProfilePresenceVisibilityPolicy;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.ProfileRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Seeds a deterministic demo account when local smoke explicitly enables it. */
@Component
@ConditionalOnProperty(
    prefix = "firemud.smoke.seed-demo-account",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
@RequiredArgsConstructor
public class TestDataSeeder implements ApplicationRunner {
  private static final long DEMO_TENANT_ID = 1L;
  private static final String DEMO_USERNAME = "demo";
  private static final String DEMO_EMAIL = "demo@example.com";
  private static final String DEMO_PASSWORD = "swordfish";

  private final AccountRepository accountRepository;
  private final AccountTenantMembershipRepository accountTenantMembershipRepository;
  private final ProfileRepository profileRepository;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    Account account =
        accountRepository
            .findByEmail(DEMO_EMAIL)
            .orElseGet(
                () -> {
                  Account seeded = new Account();
                  seeded.setUsername(DEMO_USERNAME);
                  seeded.setEmail(DEMO_EMAIL);
                  return seeded;
                });

    account.setUsername(DEMO_USERNAME);
    account.setEmail(DEMO_EMAIL);
    account.setPasswordHash(hashPassword(DEMO_PASSWORD));
    account.setRole("player");
    account.setEmailVerified(true);
    account = accountRepository.save(account);

    if (!accountTenantMembershipRepository.existsByAccountIdAndTenantId(
        account.getId(), DEMO_TENANT_ID)) {
      AccountTenantMembership membership = new AccountTenantMembership();
      membership.setAccount(account);
      membership.setTenantId(DEMO_TENANT_ID);
      membership.setGameplayAdmissionAllowed(true);
      accountTenantMembershipRepository.save(membership);
    }

    if (profileRepository.findByAccountIdAndTenantId(account.getId(), DEMO_TENANT_ID).isEmpty()) {
      net.firedevops.firemud.accountservice.entity.Profile profile =
          new net.firedevops.firemud.accountservice.entity.Profile();
      profile.setAccount(account);
      profile.setTenantId(DEMO_TENANT_ID);
      profile.setDisplayName("Demo");
      profile.setPresenceVisibilityPolicy(ProfilePresenceVisibilityPolicy.FRIENDS_ONLY);
      profileRepository.save(profile);
    }
  }

  private String hashPassword(String password) {
    Argon2 argon2 = Argon2Factory.create();
    char[] chars = password.toCharArray();
    try {
      return argon2.hash(2, 65536, 1, chars);
    } finally {
      argon2.wipeArray(chars);
    }
  }
}
