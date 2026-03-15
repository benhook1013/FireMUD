package net.firedevops.firemud.accountservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "firemud.payment")
public class PaymentProperties {
  private String stripeApiKey;
  private double platformFeePercent = 0.0;
}
