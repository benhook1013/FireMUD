package net.firedevops.firemud.accountservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "firemud.payment")
public class PaymentProperties {
  @lombok.ToString.Exclude private String stripeApiKey;
  @lombok.ToString.Exclude private String hostingWebhookSecret;
  private String stripePublishableKey;
  private double platformFeePercent = 0.0;
}
