package net.firedevops.firemud.gamesession.config;

import net.firedevops.firemud.common.security.GatewayFirstPartyConnectContextEnvelope.GatewayVerificationKeySource;
import net.firedevops.firemud.common.security.GatewayFirstPartyConnectContextEnvelope.KeySourceUnavailableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FirstPartyConnectContextConfig {
  /** Production key delivery is not implemented. Missing dedicated keys deny every context. */
  @Bean
  @ConditionalOnMissingBean(GatewayVerificationKeySource.class)
  public GatewayVerificationKeySource gatewayVerificationKeySource() {
    return keyId -> {
      throw new KeySourceUnavailableException();
    };
  }
}
