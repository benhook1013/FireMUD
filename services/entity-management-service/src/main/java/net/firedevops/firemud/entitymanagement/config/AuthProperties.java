package net.firedevops.firemud.entitymanagement.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "firemud.auth")
public class AuthProperties {
  private String jwtSecret;
  private String jwtSecretPath;
  private long jwtExpirationMs;
}
