package net.firedevops.firemud.service.impl;

import io.micrometer.core.annotation.Timed;
import java.time.LocalDateTime;
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

  public PaymentServiceImpl(
      AccountRepository accountRepository,
      PaymentTransactionRepository paymentTransactionRepository,
      SubscriptionRepository subscriptionRepository) {
    this.accountRepository = accountRepository;
    this.paymentTransactionRepository = paymentTransactionRepository;
    this.subscriptionRepository = subscriptionRepository;
  }

  @Override
  @Transactional
  @Timed(value = "payment.create_intent")
  public PaymentIntentDto createPaymentIntent(Long tenantId, Long accountId, Long amountCents) {
    logger.info("Create payment intent {} cents for account {}", amountCents, accountId);
    Account account = accountRepository.findById(accountId).orElseThrow();
    PaymentTransaction tx = new PaymentTransaction();
    tx.setAccount(account);
    tx.setAmountCents(amountCents);
    tx.setCurrency("USD");
    tx.setStatus("pending");
    tx = paymentTransactionRepository.save(tx);
    return new PaymentIntentDto(
        tx.getId(), tenantId, accountId, tx.getAmountCents(), tx.getCurrency());
  }

  @Override
  @Transactional
  @Timed(value = "payment.create_subscription")
  public SubscriptionDto createSubscription(Long tenantId, Long accountId, String planId) {
    logger.info("Create subscription {} for account {}", planId, accountId);
    Account account = accountRepository.findById(accountId).orElseThrow();
    Subscription sub = new Subscription();
    sub.setAccount(account);
    sub.setPlanId(planId);
    sub.setStatus("active");
    sub.setStartedAt(LocalDateTime.now());
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
