package net.firedevops.firemud.gamesession.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "firemud.gateway.connect-context")
public class FirstPartyConnectContextProperties {
  private long ttlMs = 30_000L;
  private int allowedClockSkewMs = 0;
}
