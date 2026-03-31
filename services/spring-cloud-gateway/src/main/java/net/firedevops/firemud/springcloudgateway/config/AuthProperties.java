package net.firedevops.firemud.springcloudgateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "firemud.auth")
public class AuthProperties {
  private String jwtSecret;
  private String jwtSecretPath;
  private long jwtExpirationMs = 30000L;
}
