package net.firedevops.firemud.accountservice.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.time.LocalDateTime;
import net.firedevops.firemud.accountservice.client.StripeClient;
import net.firedevops.firemud.accountservice.dto.PaymentIntentDto;
import net.firedevops.firemud.accountservice.dto.SubscriptionDto;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.PaymentTransaction;
import net.firedevops.firemud.accountservice.entity.Subscription;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.PaymentTransactionRepository;
import net.firedevops.firemud.accountservice.repository.SubscriptionRepository;
import net.firedevops.firemud.accountservice.service.PaymentService;
import net.firedevops.firemud.common.LoggingUtil;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

@Service
public class PaymentServiceImpl implements PaymentService {
  private static final Logger logger = LoggingUtil.getLogger(PaymentServiceImpl.class);

  private final AccountRepository accountRepository;
  private final PaymentTransactionRepository paymentTransactionRepository;
  private final SubscriptionRepository subscriptionRepository;
  private final StripeClient stripeClient;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Repositories and client are injected and not exposed")
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
  @Timed(value = "payment.create_intent")
  public PaymentIntentDto createPaymentIntent(Long tenantId, Long accountId, Long amountCents) {
    logger.info("Create payment intent {} cents for account {}", amountCents, accountId);
    Account account =
        accountRepository
            .findById(accountId)
            .filter(a -> a.getTenantId().equals(tenantId))
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));
    long platformFee = stripeClient.calculatePlatformFee(amountCents);
    long creatorShare = amountCents - platformFee;
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
    tx.setPlatformFeeCents(platformFee);
    tx.setCreatorShareCents(creatorShare);
    tx.setProviderId(intent.id());
    tx.setStatus(intent.status());
    tx.setDonation(false);
    tx.setTenantId(tenantId);
    tx = paymentTransactionRepository.save(tx);
    return new PaymentIntentDto(
        tx.getId(),
        tenantId,
        accountId,
        tx.getAmountCents(),
        tx.getPlatformFeeCents(),
        tx.getCreatorShareCents(),
        tx.getCurrency(),
        intent.clientSecret(),
        false);
  }

  @Override
  @Timed(value = "payment.create_donation")
  public PaymentIntentDto createDonation(Long tenantId, Long accountId, Long amountCents) {
    logger.info("Create donation {} cents for account {}", amountCents, accountId);
    Account account =
        accountRepository
            .findById(accountId)
            .filter(a -> a.getTenantId().equals(tenantId))
            .orElseThrow(() -> new IllegalArgumentException("Account not found"));
    long platformFee = stripeClient.calculatePlatformFee(amountCents);
    long creatorShare = amountCents - platformFee;
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
    tx.setPlatformFeeCents(platformFee);
    tx.setProviderId(intent.id());
    tx.setCreatorShareCents(creatorShare);
    tx.setStatus(intent.status());
    tx.setDonation(true);
    tx.setTenantId(tenantId);
    tx = paymentTransactionRepository.save(tx);
    return new PaymentIntentDto(
        tx.getId(),
        tenantId,
        accountId,
        tx.getAmountCents(),
        tx.getPlatformFeeCents(),
        tx.getCreatorShareCents(),
        tx.getCurrency(),
        intent.clientSecret(),
        true);
  }

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

  @Override
  @Timed(value = "payment.refund")
  public void refundPayment(Long tenantId, Long paymentId) {
    PaymentTransaction tx =
        paymentTransactionRepository
            .findById(paymentId)
            .filter(t -> t.getTenantId().equals(tenantId))
            .orElseThrow(() -> new IllegalArgumentException("Payment not found"));

    try {
      stripeClient.createRefund(tx.getProviderId());
    } catch (com.stripe.exception.StripeException e) {
      throw new IllegalStateException("Stripe error", e);
    }

    tx.setStatus("refunded");
    paymentTransactionRepository.save(tx);
  }
}
