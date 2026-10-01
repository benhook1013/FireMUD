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
    when(accountTenantMembershipRepository.existsByAccountIdAndTenantId(1L, 1L)).thenReturn(false);
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
  void runIsIdempotentWhenInvokedTwice() {
    Account account = new Account();
    account.setId(1L);
    account.setEmail("demo@example.com");
    Subscription seededSubscription = new Subscription();
    seededSubscription.setAccount(account);
    seededSubscription.setPlanId("local-smoke");
    seededSubscription.setStatus("active");
    seededSubscription.setTenantId(1L);

    when(accountRepository.findByEmail("demo@example.com"))
        .thenReturn(Optional.empty(), Optional.of(account));
    when(accountRepository.save(any(Account.class))).thenReturn(account);
    when(accountTenantMembershipRepository.existsByAccountIdAndTenantId(1L, 1L))
        .thenReturn(false, true);
    when(profileRepository.findByAccountIdAndTenantId(1L, 1L))
        .thenReturn(Optional.empty(), Optional.of(new Profile()));
    when(subscriptionRepository.findByTenantId(1L))
        .thenReturn(List.of(), List.of(seededSubscription));

    seeder.run(new DefaultApplicationArguments(new String[] {}));
    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(subscriptionRepository, times(1)).save(any(Subscription.class));
  }

  @Test
  void runRetainsExistingSubscriptionWithoutOverwritingItsStatus() {
    Account account = new Account();
    account.setId(1L);
    account.setEmail("demo@example.com");
    Account billingOwner = new Account();
    billingOwner.setId(2L);
    Subscription existingSubscription = new Subscription();
    existingSubscription.setAccount(billingOwner);
    existingSubscription.setPlanId("billing-owned-plan");
    existingSubscription.setStatus("canceled");
    existingSubscription.setEndedAt(LocalDateTime.of(2025, 1, 2, 3, 4));
    existingSubscription.setTenantId(1L);

    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));
    when(accountRepository.save(any(Account.class))).thenReturn(account);
    when(accountTenantMembershipRepository.existsByAccountIdAndTenantId(1L, 1L)).thenReturn(true);
    when(profileRepository.findByAccountIdAndTenantId(1L, 1L))
        .thenReturn(Optional.of(new Profile()));
    when(subscriptionRepository.findByTenantId(1L)).thenReturn(List.of(existingSubscription));

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
        () -> assertFalse(saved.getPasswordHash().isBlank()),
        () -> assertSame(billingOwner, existingSubscription.getAccount()),
        () -> assertEquals("canceled", existingSubscription.getStatus()),
        () -> assertEquals("billing-owned-plan", existingSubscription.getPlanId()),
        () -> assertEquals(LocalDateTime.of(2025, 1, 2, 3, 4), existingSubscription.getEndedAt()));
    verify(accountTenantMembershipRepository, never()).save(any(AccountTenantMembership.class));
    verify(profileRepository, never()).save(any(Profile.class));
    verify(subscriptionRepository, never()).save(any(Subscription.class));
  }

  @Test
  void runFailsClosedWhenExistingTenantSubscriptionsAreAmbiguous() {
    Account account = new Account();
    account.setId(1L);
    account.setEmail("demo@example.com");
    Subscription active = new Subscription();
    active.setStatus("active");
    active.setTenantId(1L);
    Subscription canceled = new Subscription();
    canceled.setStatus("canceled");
    canceled.setTenantId(1L);

    when(accountRepository.findByEmail("demo@example.com")).thenReturn(Optional.of(account));
    when(accountRepository.save(any(Account.class))).thenReturn(account);
    when(accountTenantMembershipRepository.existsByAccountIdAndTenantId(1L, 1L)).thenReturn(true);
    when(profileRepository.findByAccountIdAndTenantId(1L, 1L))
        .thenReturn(Optional.of(new Profile()));
    when(subscriptionRepository.findByTenantId(1L)).thenReturn(List.of(active, canceled));

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () -> seeder.run(new DefaultApplicationArguments(new String[] {})));

    assertTrue(exception.getMessage().contains("ambiguous subscription authority"));
    verify(subscriptionRepository, never()).save(any(Subscription.class));
  }
}
