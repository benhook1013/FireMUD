package net.firedevops.firemud.client;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;

/** Simple wrapper around Stripe API calls. */
public class StripeClient {
  private final String apiKey;
  private final double platformFeePercent;

  public StripeClient(String apiKey, double platformFeePercent) {
    this.apiKey = apiKey;
    this.platformFeePercent = platformFeePercent;
    // Set once during construction to avoid repeated writes to static field
    Stripe.apiKey = apiKey;
  }

  public record IntentResult(String id, String clientSecret, String status) {}

  public IntentResult createPaymentIntent(long amountCents, String currency)
      throws StripeException {
    long fee = calculatePlatformFee(amountCents);
    PaymentIntentCreateParams params =
        PaymentIntentCreateParams.builder()
            .setAmount(amountCents)
            .setCurrency(currency)
            .setAutomaticPaymentMethods(
                PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                    .setEnabled(true)
                    .build())
            .setApplicationFeeAmount(fee)
            .build();
    com.stripe.model.PaymentIntent intent = com.stripe.model.PaymentIntent.create(params);
    return new IntentResult(intent.getId(), intent.getClientSecret(), intent.getStatus());
  }

  public long calculatePlatformFee(long amountCents) {
    return Math.round(amountCents * platformFeePercent / 100.0);
  }

  public void createRefund(String paymentIntentId) throws StripeException {
    RefundCreateParams params =
        RefundCreateParams.builder().setPaymentIntent(paymentIntentId).build();
    com.stripe.model.Refund.create(params);
  }
}
