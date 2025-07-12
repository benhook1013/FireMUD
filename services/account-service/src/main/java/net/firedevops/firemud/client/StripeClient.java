package net.firedevops.firemud.client;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.param.PaymentIntentCreateParams;

/** Simple wrapper around Stripe API calls. */
public class StripeClient {
  private final String apiKey;
  private final double platformFeePercent;

  public StripeClient(String apiKey, double platformFeePercent) {
    this.apiKey = apiKey;
    this.platformFeePercent = platformFeePercent;
  }

  public record IntentResult(String id, String clientSecret, String status) {}

  public IntentResult createPaymentIntent(long amountCents, String currency)
      throws StripeException {
    Stripe.apiKey = apiKey;
    long fee = Math.round(amountCents * platformFeePercent / 100.0);
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
}
