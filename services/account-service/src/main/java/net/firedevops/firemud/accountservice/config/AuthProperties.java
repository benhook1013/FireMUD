package net.firedevops.firemud.accountservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "firemud.auth")
public class AuthProperties {
  private String jwtSecret;
  private String jwtSecretPath;
  private long jwtExpirationMs;
  private long sessionExpirationMs = 3600000L;
}
