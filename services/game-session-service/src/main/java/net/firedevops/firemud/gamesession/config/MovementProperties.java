package net.firedevops.firemud.gamesession.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Movement-facing settings surfaced through the new platform settings model. */
@ConfigurationProperties(prefix = "firemud.movement")
public record MovementProperties(boolean postMoveLookEnabled) {
  public MovementProperties() {
    this(true);
  }
}
