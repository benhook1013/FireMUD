package net.firedevops.firemud.service.impl;

import io.micrometer.core.annotation.Timed;
import java.time.LocalDateTime;
import net.firedevops.firemud.client.StripeClient;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.dto.PaymentIntentDto;
import net.firedevops.firemud.dto.SubscriptionDto;
import net.firedevops.firemud.entity.Account;
import net.firedevops.firemud.entity.PaymentTransaction;
import net.firedevops.firemud.entity.Subscription;
import net.firedevops.firemud.repository.AccountRepository;
import net.firedevops.firemud.repository.PaymentTransactionRepository;
import net.firedevops.firemud.repository.SubscriptionRepository;
import net.firedevops.firemud.service.PaymentService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentServiceImpl implements PaymentService {
  private static final Logger logger = LoggingUtil.getLogger(PaymentServiceImpl.class);

  private final AccountRepository accountRepository;
  private final PaymentTransactionRepository paymentTransactionRepository;
  private final SubscriptionRepository subscriptionRepository;
  private final StripeClient stripeClient;

  public PaymentServiceImpl(
      AccountRepository accountRepository,
      PaymentTransactionRepository paymentTransactionRepository,
      SubscriptionRepository subscriptionRepository,
      StripeClient stripeClient) {
    this.accountRepository = accountRepository;
    this.paymentTransactionRepository = paymentTransactionRepository;
    this.subscriptionRepository = subscriptionRepository;
    this.stripeClient = stripeClient;
  }

  @Override
  @Transactional
  @Timed(value = "payment.create_intent")
  public PaymentIntentDto createPaymentIntent(Long tenantId, Long accountId, Long amountCents) {
    logger.info("Create payment intent {} cents for account {}", amountCents, accountId);
    Account account =
        accountRepository
            .findById(accountId)
            .filter(a -> a.getTenantId().equals(tenantId))
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));
    StripeClient.IntentResult intent;
    try {
      intent = stripeClient.createPaymentIntent(amountCents, "usd");
    } catch (com.stripe.exception.StripeException e) {
      throw new IllegalStateException("Stripe error", e);
    }

    PaymentTransaction tx = new PaymentTransaction();
    tx.setAccount(account);
    tx.setAmountCents(amountCents);
    tx.setCurrency("USD");
    tx.setStatus(intent.status());
    tx.setTenantId(tenantId);
    tx = paymentTransactionRepository.save(tx);
    return new PaymentIntentDto(
        tx.getId(),
        tenantId,
        accountId,
        tx.getAmountCents(),
        tx.getCurrency(),
        intent.clientSecret());
  }

  @Override
  @Transactional
  @Timed(value = "payment.create_subscription")
  public SubscriptionDto createSubscription(Long tenantId, Long accountId, String planId) {
    logger.info("Create subscription {} for account {}", planId, accountId);
    Account account =
        accountRepository
            .findById(accountId)
            .filter(a -> a.getTenantId().equals(tenantId))
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));
    Subscription sub = new Subscription();
    sub.setAccount(account);
    sub.setPlanId(planId);
    sub.setStatus("active");
    sub.setStartedAt(LocalDateTime.now());
    sub.setTenantId(tenantId);
    sub = subscriptionRepository.save(sub);
    return new SubscriptionDto(
        sub.getId(),
        tenantId,
        accountId,
        sub.getPlanId(),
        sub.getStatus(),
        sub.getStartedAt(),
        sub.getEndedAt());
  }
}
