package net.firedevops.firemud.config;

import net.firedevops.firemud.client.StripeClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentConfig {
  @Bean
  public StripeClient stripeClient(PaymentProperties props) {
    return new StripeClient(props.getStripeApiKey(), props.getPlatformFeePercent());
  }
}
