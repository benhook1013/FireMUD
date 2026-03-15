package net.firedevops.firemud.accountservice.service;

import net.firedevops.firemud.accountservice.dto.PaymentIntentDto;
import net.firedevops.firemud.accountservice.dto.SubscriptionDto;

public interface PaymentService {
  PaymentIntentDto createPaymentIntent(Long tenantId, Long accountId, Long amountCents);

  PaymentIntentDto createDonation(Long tenantId, Long accountId, Long amountCents);

  SubscriptionDto createSubscription(Long tenantId, Long accountId, String planId);

  void refundPayment(Long tenantId, Long paymentId);
}
