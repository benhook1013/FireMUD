package net.firedevops.firemud.gamesession.config;

import net.firedevops.firemud.common.security.JwtUtil;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class FirstPartyConnectContextConfig {

  @Bean
  @Qualifier("firstPartyConnectContextJwtUtil")
  public JwtUtil firstPartyConnectContextJwtUtil(FirstPartyConnectContextProperties properties) {
    String secret = properties.getJwtSecret();
    if (!StringUtils.hasText(secret)) {
      secret = "missing-first-party-connect-context-secret";
    }
    return new JwtUtil(secret, properties.getTtlMs());
  }
}
