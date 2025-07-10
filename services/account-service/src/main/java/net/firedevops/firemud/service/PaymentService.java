package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.PaymentIntentDto;
import net.firedevops.firemud.dto.SubscriptionDto;

public interface PaymentService {
  PaymentIntentDto createPaymentIntent(Long tenantId, Long accountId, Long amountCents);

  SubscriptionDto createSubscription(Long tenantId, Long accountId, String planId);
}
