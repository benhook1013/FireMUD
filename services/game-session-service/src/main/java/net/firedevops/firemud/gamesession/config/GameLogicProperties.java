package net.firedevops.firemud.gamesession.config;

import java.net.URI;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "game.logic")
public class GameLogicProperties {
  private String defaultRoomId = "R-1021";
  private String readinessUrl = "";

  /** Only the configured internal actuator readiness endpoint may be probed. */
  public URI readinessEndpoint() {
    if (readinessUrl == null || readinessUrl.isBlank()) {
      return null;
    }
    try {
      URI endpoint = URI.create(readinessUrl);
      if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
          || endpoint.getHost() == null
          || endpoint.getUserInfo() != null
          || endpoint.getRawQuery() != null
          || endpoint.getRawFragment() != null
          || !"/actuator/health/readiness".equals(endpoint.getRawPath())
          || endpoint.getPort() == 0
          || endpoint.getPort() > 65535) {
        return null;
      }
      return endpoint;
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }
}
