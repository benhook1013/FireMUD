package net.firedevops.firemud.gamesession.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configurable operating flags for the Game Session Service. */
@Data
@ConfigurationProperties(prefix = "game-session")
public class GameSessionProperties {
  /** Require authentication before processing gameplay commands. */
  private boolean requireAuthenticatedCommands = true;

  /** Tenant identifier used for initial account logins. */
  private String defaultTenantId = "demo";
}
