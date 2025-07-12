package net.firedevops.firemud.config;

import java.util.Arrays;
import java.util.UUID;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.security.JwtUtil;
import org.slf4j.Logger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfig {
  private static final Logger logger = LoggingUtil.getLogger(AuthConfig.class);
  private final Environment environment;

  public AuthConfig(Environment environment) {
    this.environment = environment;
  }

  @Bean
  public JwtUtil jwtUtil(AuthProperties props) {
    String secret = props.getJwtSecret();
    if (secret == null || secret.isBlank()) {
      boolean dev = Arrays.asList(environment.getActiveProfiles()).contains("dev");
      if (dev) {
        secret =
            UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        logger.info("Generated random JWT secret for development profile");
        props.setJwtSecret(secret);
      } else {
        throw new IllegalStateException("firemud.auth.jwt-secret must be set");
      }
    }
    return new JwtUtil(secret, props.getJwtExpirationMs());
  }
}
