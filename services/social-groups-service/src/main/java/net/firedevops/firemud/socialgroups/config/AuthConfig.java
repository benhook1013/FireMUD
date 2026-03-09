package net.firedevops.firemud.socialgroups.config;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.security.JwtSecretWatcher;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.ReloadableJwtUtil;
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
  private JwtSecretWatcher watcher;

  public AuthConfig(Environment environment) {
    this.environment = environment;
  }

  @Bean
  public JwtUtil jwtUtil(AuthProperties props) throws IOException {
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
    ReloadableJwtUtil util = new ReloadableJwtUtil(secret, props.getJwtExpirationMs());
    String path = props.getJwtSecretPath();
    if (path != null && !path.isBlank()) {
      Path p = Path.of(path);
      Runnable reload =
          () -> {
            try {
              String newSecret = Files.readString(p).trim();
              util.updateSecret(newSecret);
            } catch (IOException e) {
              logger.error("Failed to reload JWT secret", e);
            }
          };
      reload.run();
      watcher = JwtSecretWatcher.createAndStart(p, reload);
    }
    return util;
  }

  @PreDestroy
  public void close() throws Exception {
    if (watcher != null) {
      watcher.close();
    }
  }
}
