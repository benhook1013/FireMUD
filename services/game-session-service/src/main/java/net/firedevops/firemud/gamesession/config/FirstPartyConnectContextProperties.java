package net.firedevops.firemud.gamesession.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "firemud.gateway.connect-context")
public class FirstPartyConnectContextProperties {
  /** Local retention bound for an admitted context; this is not a signing or verification key. */
  private long ttlMs = 30_000L;
}
