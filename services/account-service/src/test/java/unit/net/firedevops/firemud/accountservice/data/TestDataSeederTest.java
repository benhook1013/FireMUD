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
import org.mockito.ArgumentCaptor;
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
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(1L, 1L))
        .thenReturn(Optional.empty());
    when(profileRepository.findByAccountIdAndTenantId(1L, 1L)).thenReturn(Optional.empty());

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(accountRepository).save(any(Account.class));
    verify(accountTenantMembershipRepository).save(any(AccountTenantMembership.class));
    verify(profileRepository).save(any(Profile.class));
  }

  @Test
  void runReassertsExistingDemoAccountWithoutOverwritingExplicitJoin() throws Exception {
    Account account = new Account();
    account.setId(1L);
    account.setEmail("demo@example.com");
    AccountTenantMembership explicitJoin = membership(account, 1L, "ACTIVE", "EXPLICIT_JOIN", true);
    explicitJoin.setMembershipVersion(4L);
    explicitJoin.setMembershipAuthorityGeneration(8L);

    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));
    when(accountRepository.save(any(Account.class))).thenReturn(account);
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(1L, 1L))
        .thenReturn(Optional.of(explicitJoin));
    when(profileRepository.findByAccountIdAndTenantId(1L, 1L))
        .thenReturn(Optional.of(new Profile()));

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
    verify(accountRepository).save(accountCaptor.capture());
    Account saved = accountCaptor.getValue();
    org.junit.jupiter.api.Assertions.assertAll(
        () -> org.junit.jupiter.api.Assertions.assertEquals("demo", saved.getUsername()),
        () -> org.junit.jupiter.api.Assertions.assertEquals("demo@example.com", saved.getEmail()),
        () -> org.junit.jupiter.api.Assertions.assertEquals("player", saved.getRole()),
        () -> org.junit.jupiter.api.Assertions.assertTrue(saved.isEmailVerified()),
        () -> org.junit.jupiter.api.Assertions.assertNotNull(saved.getPasswordHash()),
        () -> org.junit.jupiter.api.Assertions.assertFalse(saved.getPasswordHash().isBlank()));
    verify(accountTenantMembershipRepository, never()).save(any(AccountTenantMembership.class));
    org.junit.jupiter.api.Assertions.assertAll(
        () ->
            org.junit.jupiter.api.Assertions.assertEquals(
                "ACTIVE", explicitJoin.getLifecycleState()),
        () ->
            org.junit.jupiter.api.Assertions.assertTrue(explicitJoin.isGameplayAdmissionAllowed()),
        () ->
            org.junit.jupiter.api.Assertions.assertEquals(4L, explicitJoin.getMembershipVersion()),
        () ->
            org.junit.jupiter.api.Assertions.assertEquals(
                8L, explicitJoin.getMembershipAuthorityGeneration()),
        () ->
            org.junit.jupiter.api.Assertions.assertEquals(
                "EXPLICIT_JOIN", explicitJoin.getAuthorityProvenance()));
    verify(profileRepository, never()).save(any(Profile.class));
  }

  @Test
  void runReconcilesOnlyTheExactQuarantinedDemoMembership() throws Exception {
    Account account = new Account();
    account.setId(1L);
    account.setEmail("demo@example.com");
    AccountTenantMembership quarantined =
        membership(account, 1L, "LEGACY_UNVERIFIED", "LEGACY_UNVERIFIED", false);
    quarantined.setId(42L);
    quarantined.setMembershipVersion(1L);
    quarantined.setMembershipAuthorityGeneration(1L);

    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));
    when(accountRepository.save(any(Account.class))).thenReturn(account);
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(1L, 1L))
        .thenReturn(Optional.of(quarantined));
    when(profileRepository.findByAccountIdAndTenantId(1L, 1L))
        .thenReturn(Optional.of(new Profile()));

    seeder.run(new DefaultApplicationArguments(new String[] {}));
    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(accountTenantMembershipRepository).save(quarantined);
    org.junit.jupiter.api.Assertions.assertAll(
        () -> org.junit.jupiter.api.Assertions.assertEquals(42L, quarantined.getId()),
        () -> org.junit.jupiter.api.Assertions.assertEquals(1L, quarantined.getTenantId()),
        () ->
            org.junit.jupiter.api.Assertions.assertEquals(
                "ACTIVE", quarantined.getLifecycleState()),
        () -> org.junit.jupiter.api.Assertions.assertTrue(quarantined.isGameplayAdmissionAllowed()),
        () -> org.junit.jupiter.api.Assertions.assertEquals(2L, quarantined.getMembershipVersion()),
        () ->
            org.junit.jupiter.api.Assertions.assertEquals(
                2L, quarantined.getMembershipAuthorityGeneration()),
        () ->
            org.junit.jupiter.api.Assertions.assertEquals(
                "SEEDED_DEMO", quarantined.getAuthorityProvenance()));
  }

  @Test
  void runDoesNotPromoteLegacyMembershipOutsideTheDemoIdentity() throws Exception {
    Account account = new Account();
    account.setId(1L);
    account.setEmail("demo@example.com");
    Account otherAccount = new Account();
    otherAccount.setId(2L);
    AccountTenantMembership unrelatedLegacyMembership =
        membership(otherAccount, 7L, "LEGACY_UNVERIFIED", "LEGACY_UNVERIFIED", false);
    unrelatedLegacyMembership.setId(93L);
    unrelatedLegacyMembership.setMembershipVersion(1L);
    unrelatedLegacyMembership.setMembershipAuthorityGeneration(1L);

    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));
    when(accountRepository.save(any(Account.class))).thenReturn(account);
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(1L, 1L))
        .thenReturn(Optional.of(unrelatedLegacyMembership));
    when(profileRepository.findByAccountIdAndTenantId(1L, 1L))
        .thenReturn(Optional.of(new Profile()));

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(accountTenantMembershipRepository, never()).save(any(AccountTenantMembership.class));
    org.junit.jupiter.api.Assertions.assertAll(
        () ->
            org.junit.jupiter.api.Assertions.assertEquals(
                "LEGACY_UNVERIFIED", unrelatedLegacyMembership.getLifecycleState()),
        () ->
            org.junit.jupiter.api.Assertions.assertFalse(
                unrelatedLegacyMembership.isGameplayAdmissionAllowed()),
        () ->
            org.junit.jupiter.api.Assertions.assertEquals(
                "LEGACY_UNVERIFIED", unrelatedLegacyMembership.getAuthorityProvenance()));
  }

  @Test
  void runFailsClosedBeforeRewritingMismatchedAccountReturnedForDemoEmail() throws Exception {
    Account mismatchedAccount = new Account();
    mismatchedAccount.setId(1L);
    mismatchedAccount.setEmail("real-player@example.com");
    when(accountRepository.findByEmail("demo@example.com"))
        .thenReturn(Optional.of(mismatchedAccount));

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class,
        () -> seeder.run(new DefaultApplicationArguments(new String[] {})));

    verify(accountRepository, never()).save(any(Account.class));
    org.mockito.Mockito.verifyNoInteractions(accountTenantMembershipRepository, profileRepository);
  }

  private static AccountTenantMembership membership(
      Account account,
      long tenantId,
      String lifecycleState,
      String authorityProvenance,
      boolean gameplayAdmissionAllowed) {
    AccountTenantMembership membership = new AccountTenantMembership();
    membership.setAccount(account);
    membership.setTenantId(tenantId);
    membership.setLifecycleState(lifecycleState);
    membership.setAuthorityProvenance(authorityProvenance);
    membership.setGameplayAdmissionAllowed(gameplayAdmissionAllowed);
    return membership;
  }
}
