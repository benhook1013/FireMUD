package net.firedevops.firemud.accountservice.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import net.firedevops.firemud.accountservice.client.StripeClient;
import net.firedevops.firemud.accountservice.dto.PaymentIntentDto;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.PaymentTransaction;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.PaymentTransactionRepository;
import net.firedevops.firemud.accountservice.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class PaymentServiceImplTest {
  @Mock private AccountRepository accountRepository;
  @Mock private PaymentTransactionRepository txRepo;
  @Mock private SubscriptionRepository subRepo;
  @Mock private StripeClient stripeClient;

  private PaymentServiceImpl service;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    service = new PaymentServiceImpl(accountRepository, txRepo, subRepo, stripeClient);
  }

  @Test
  void createPaymentIntentSetsTenantId() throws Exception {
    Account account = new Account();
    account.setId(1L);
    account.setTenantId(2L);
    when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
    when(txRepo.save(any()))
        .thenAnswer(
            invocation -> {
              PaymentTransaction tx = invocation.getArgument(0);
              tx.setId(10L);
              return tx;
            });
    when(stripeClient.calculatePlatformFee(500L)).thenReturn(25L);
    when(stripeClient.createPaymentIntent(500L, "usd"))
        .thenReturn(new StripeClient.IntentResult("pi", "secret", "pending"));

    PaymentIntentDto dto = service.createPaymentIntent(2L, 1L, 500L);

    assertEquals(10L, dto.id());
    assertEquals("secret", dto.clientSecret());
    assertEquals(25L, dto.platformFeeCents());
    ArgumentCaptor<PaymentTransaction> captor = ArgumentCaptor.forClass(PaymentTransaction.class);
    verify(txRepo).save(captor.capture());
    assertEquals(2L, captor.getValue().getTenantId());
    assertEquals(25L, captor.getValue().getPlatformFeeCents());
    assertEquals(475L, captor.getValue().getCreatorShareCents());
    assertFalse(dto.donation());
  }

  @Test
  void createSubscriptionFailsForWrongTenant() {
    Account account = new Account();
    account.setId(1L);
    account.setTenantId(5L); // different tenant
    when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

    assertThrows(IllegalArgumentException.class, () -> service.createSubscription(2L, 1L, "plan"));
  }

  @Test
  void refundPaymentUpdatesStatus() throws Exception {
    PaymentTransaction tx = new PaymentTransaction();
    tx.setId(5L);
    tx.setTenantId(2L);
    tx.setProviderId("pi_123");
    when(txRepo.findById(5L)).thenReturn(Optional.of(tx));

    service.refundPayment(2L, 5L);

    verify(stripeClient).createRefund("pi_123");
    verify(txRepo).save(tx);
    assertEquals("refunded", tx.getStatus());
  }

  @Test
  void createDonationSetsFlag() throws Exception {
    Account account = new Account();
    account.setId(1L);
    account.setTenantId(2L);
    when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
    when(txRepo.save(any()))
        .thenAnswer(
            invocation -> {
              PaymentTransaction tx = invocation.getArgument(0);
              tx.setId(11L);
              return tx;
            });
    when(stripeClient.calculatePlatformFee(100L)).thenReturn(5L);
    when(stripeClient.createPaymentIntent(100L, "usd"))
        .thenReturn(new StripeClient.IntentResult("pi2", "donate", "pending"));

    PaymentIntentDto dto = service.createDonation(2L, 1L, 100L);

    assertTrue(dto.donation());
    ArgumentCaptor<PaymentTransaction> captor = ArgumentCaptor.forClass(PaymentTransaction.class);
    verify(txRepo).save(captor.capture());
    assertTrue(captor.getValue().isDonation());
    assertEquals(95L, captor.getValue().getCreatorShareCents());
  }
}
