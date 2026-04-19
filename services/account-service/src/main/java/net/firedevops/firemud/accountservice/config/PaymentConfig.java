package net.firedevops.firemud.accountservice.config;

import net.firedevops.firemud.accountservice.client.StripeClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentConfig {
  @Bean
  public StripeClient stripeClient(PaymentProperties props) {
    return new StripeClient(props.getStripeApiKey(), props.getPlatformFeePercent());
  }
}
