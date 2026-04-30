package net.firedevops.firemud.gamesession.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Configurable operating flags for the Game Session Service. */
@Data
@Validated
@ConfigurationProperties(prefix = "game-session")
public class GameSessionProperties {
  /** Require authentication before processing gameplay commands. */
  private boolean requireAuthenticatedCommands = true;

  /** Tenant identifier used for initial account logins. */
  private String defaultTenantId = "demo";

  /** Maximum age for persisted pin observations before control-plane reads mark them stale. */
  @Min(1)
  private long pinConvergenceStaleThresholdMs = 5000;
}
