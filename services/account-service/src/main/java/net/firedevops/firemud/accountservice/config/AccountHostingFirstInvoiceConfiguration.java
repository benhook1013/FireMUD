package net.firedevops.firemud.accountservice.config;

import java.time.Clock;
import java.time.Duration;
import net.firedevops.firemud.accountservice.client.StripeHostingFirstInvoicePaymentClient;
import net.firedevops.firemud.accountservice.client.StripeHostingWebhookVerifier;

/**
 * Explicit held composition helpers, with no Spring annotation, bean, worker or route activation.
 */
public final class AccountHostingFirstInvoiceConfiguration {
  private AccountHostingFirstInvoiceConfiguration() {}

  public static StripeHostingFirstInvoicePaymentClient provider(
      com.stripe.StripeClient client, Clock clock) {
    return new StripeHostingFirstInvoicePaymentClient(
        client, clock, Duration.ofSeconds(2), Duration.ofSeconds(5));
  }

  public static StripeHostingWebhookVerifier verifier(PaymentProperties properties, Clock clock) {
    return new StripeHostingWebhookVerifier(properties.getHostingWebhookSecret(), clock);
  }
}
