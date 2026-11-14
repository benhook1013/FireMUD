package net.firedevops.firemud.accountservice.config;

import java.time.Clock;
import java.time.Duration;
import net.firedevops.firemud.accountservice.client.StripeFirstInvoiceHttpClient;
import net.firedevops.firemud.accountservice.client.StripeHostingFirstInvoicePaymentClient;
import net.firedevops.firemud.accountservice.client.StripeHostingWebhookVerifier;

/**
 * Explicit held composition helpers, with no Spring annotation, bean, worker or route activation.
 */
public final class AccountHostingFirstInvoiceConfiguration {
  private AccountHostingFirstInvoiceConfiguration() {}

  public static StripeHostingFirstInvoicePaymentClient provider(
      PaymentProperties properties, Clock clock) {
    Duration connect = Duration.ofSeconds(2);
    Duration exchange = Duration.ofSeconds(5);
    var client =
        com.stripe.StripeClient.builder()
            .setApiKey(properties.getStripeApiKey())
            .setMaxNetworkRetries(0)
            .setHttpClient(new StripeFirstInvoiceHttpClient(connect, exchange))
            .build();
    return new StripeHostingFirstInvoicePaymentClient(client, clock, connect, exchange);
  }

  public static StripeHostingWebhookVerifier verifier(PaymentProperties properties, Clock clock) {
    return new StripeHostingWebhookVerifier(properties.getHostingWebhookSecret(), clock);
  }
}
