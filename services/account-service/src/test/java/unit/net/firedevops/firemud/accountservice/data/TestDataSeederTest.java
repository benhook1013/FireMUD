package net.firedevops.firemud.accountservice.data;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.entity.Profile;
import net.firedevops.firemud.accountservice.entity.Subscription;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.ProfileRepository;
import net.firedevops.firemud.accountservice.repository.SubscriptionRepository;
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
  @Mock SubscriptionRepository subscriptionRepository;

  private TestDataSeeder seeder;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    seeder =
        new TestDataSeeder(
            accountRepository,
            accountTenantMembershipRepository,
            profileRepository,
            subscriptionRepository);
  }

  @Test
  void runSeedsDemoAccountMembershipProfileAndSubscriptionWhenMissing() {
    Account account = new Account();
    account.setId(1L);
    account.setEmail("demo@example.com");

    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.empty());
    when(accountRepository.save(any(Account.class))).thenReturn(account);
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(1L, 1L))
        .thenReturn(Optional.empty());
    when(profileRepository.findByAccountIdAndTenantId(1L, 1L)).thenReturn(Optional.empty());
    when(subscriptionRepository.findByTenantId(1L)).thenReturn(List.of());

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(accountRepository).save(any(Account.class));
    verify(accountTenantMembershipRepository).save(any(AccountTenantMembership.class));
    verify(profileRepository).save(any(Profile.class));
    ArgumentCaptor<Subscription> subscriptionCaptor = ArgumentCaptor.forClass(Subscription.class);
    verify(subscriptionRepository).save(subscriptionCaptor.capture());
    Subscription seededSubscription = subscriptionCaptor.getValue();
    assertAll(
        () -> assertSame(account, seededSubscription.getAccount()),
        () -> assertEquals("local-smoke", seededSubscription.getPlanId()),
        () -> assertEquals("active", seededSubscription.getStatus()),
        () -> assertNotNull(seededSubscription.getStartedAt()),
        () -> assertNull(seededSubscription.getEndedAt()),
        () -> assertEquals(1L, seededSubscription.getTenantId()));
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
    when(subscriptionRepository.findByTenantId(1L)).thenReturn(List.of());

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
    verify(accountRepository).save(accountCaptor.capture());
    Account saved = accountCaptor.getValue();
    assertAll(
        () -> assertEquals("demo", saved.getUsername()),
        () -> assertEquals("demo@example.com", saved.getEmail()),
        () -> assertEquals("player", saved.getRole()),
        () -> assertTrue(saved.isEmailVerified()),
        () -> assertNotNull(saved.getPasswordHash()),
        () -> assertFalse(saved.getPasswordHash().isBlank()));
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
    verify(subscriptionRepository).save(any(Subscription.class));
  }

  @Test
  void runIsIdempotentWhenInvokedTwice() {
    Account account = new Account();
    account.setId(1L);
    account.setEmail("demo@example.com");
    AccountTenantMembership seededMembership =
        membership(account, 1L, "ACTIVE", "SEEDED_DEMO", true);
    Subscription seededSubscription = new Subscription();
    seededSubscription.setAccount(account);
    seededSubscription.setPlanId("local-smoke");
    seededSubscription.setStatus("active");
    seededSubscription.setTenantId(1L);

    when(accountRepository.findByEmail("demo@example.com"))
        .thenReturn(Optional.empty(), Optional.of(account));
    when(accountRepository.save(any(Account.class))).thenReturn(account);
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(1L, 1L))
        .thenReturn(Optional.empty(), Optional.of(seededMembership));
    when(profileRepository.findByAccountIdAndTenantId(1L, 1L))
        .thenReturn(Optional.empty(), Optional.of(new Profile()));
    when(subscriptionRepository.findByTenantId(1L))
        .thenReturn(List.of(), List.of(seededSubscription));

    seeder.run(new DefaultApplicationArguments(new String[] {}));
    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(accountTenantMembershipRepository, times(1)).save(any(AccountTenantMembership.class));
    verify(profileRepository, times(1)).save(any(Profile.class));
    verify(subscriptionRepository, times(1)).save(any(Subscription.class));
  }

  @Test
  void runRetainsExistingSubscriptionWithoutOverwritingItsStatus() {
    Account account = new Account();
    account.setId(1L);
    account.setEmail("demo@example.com");
    Account billingOwner = new Account();
    billingOwner.setId(2L);
    AccountTenantMembership explicitJoin = membership(account, 1L, "ACTIVE", "EXPLICIT_JOIN", true);
    explicitJoin.setMembershipVersion(4L);
    explicitJoin.setMembershipAuthorityGeneration(8L);
    Subscription existingSubscription = new Subscription();
    existingSubscription.setAccount(billingOwner);
    existingSubscription.setPlanId("billing-owned-plan");
    existingSubscription.setStatus("canceled");
    existingSubscription.setEndedAt(LocalDateTime.of(2025, 1, 2, 3, 4));
    existingSubscription.setTenantId(1L);

    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));
    when(accountRepository.save(any(Account.class))).thenReturn(account);
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(1L, 1L))
        .thenReturn(Optional.of(explicitJoin));
    when(profileRepository.findByAccountIdAndTenantId(1L, 1L))
        .thenReturn(Optional.of(new Profile()));
    when(subscriptionRepository.findByTenantId(1L)).thenReturn(List.of(existingSubscription));

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    assertAll(
        () -> assertSame(billingOwner, existingSubscription.getAccount()),
        () -> assertEquals("canceled", existingSubscription.getStatus()),
        () -> assertEquals("billing-owned-plan", existingSubscription.getPlanId()),
        () -> assertEquals(LocalDateTime.of(2025, 1, 2, 3, 4), existingSubscription.getEndedAt()));
    verify(accountTenantMembershipRepository, never()).save(any(AccountTenantMembership.class));
    verify(subscriptionRepository, never()).save(any(Subscription.class));
  }

  @Test
  void runFailsClosedWhenExistingTenantSubscriptionsAreAmbiguous() {
    Account account = new Account();
    account.setId(1L);
    account.setEmail("demo@example.com");
    AccountTenantMembership explicitJoin = membership(account, 1L, "ACTIVE", "EXPLICIT_JOIN", true);
    Subscription active = new Subscription();
    active.setStatus("active");
    active.setTenantId(1L);
    Subscription canceled = new Subscription();
    canceled.setStatus("canceled");
    canceled.setTenantId(1L);

    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));
    when(accountRepository.save(any(Account.class))).thenReturn(account);
    when(accountTenantMembershipRepository.findByAccountIdAndTenantId(1L, 1L))
        .thenReturn(Optional.of(explicitJoin));
    when(profileRepository.findByAccountIdAndTenantId(1L, 1L))
        .thenReturn(Optional.of(new Profile()));
    when(subscriptionRepository.findByTenantId(1L)).thenReturn(List.of(active, canceled));

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () -> seeder.run(new DefaultApplicationArguments(new String[] {})));

    assertTrue(exception.getMessage().contains("ambiguous subscription authority"));
    verify(accountTenantMembershipRepository, never()).save(any(AccountTenantMembership.class));
    verify(subscriptionRepository, never()).save(any(Subscription.class));
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
