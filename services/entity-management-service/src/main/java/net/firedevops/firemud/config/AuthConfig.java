package net.firedevops.firemud.config;

import net.firedevops.firemud.common.security.JwtUtil;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides JwtUtil bean configured from AuthProperties. */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfig {
  @Bean
  public JwtUtil jwtUtil(AuthProperties props) {
    return new JwtUtil(props.getJwtSecret(), props.getJwtExpirationMs());
  }
}
