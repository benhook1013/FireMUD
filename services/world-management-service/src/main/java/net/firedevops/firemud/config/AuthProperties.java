package net.firedevops.firemud.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties for JWT handling. */
@Data
@ConfigurationProperties(prefix = "firemud.auth")
public class AuthProperties {
  private String jwtSecret;
  private long jwtExpirationMs;
}
