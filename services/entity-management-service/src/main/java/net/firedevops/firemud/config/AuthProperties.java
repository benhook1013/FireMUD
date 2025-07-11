package net.firedevops.firemud.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** JWT configuration properties loaded from application.yml. */
@Data
@ConfigurationProperties(prefix = "firemud.auth")
public class AuthProperties {
  private String jwtSecret;
  private long jwtExpirationMs;
}
