package net.firedevops.firemud.gamelogic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Communication defaults exposed through the emerging platform settings model. */
@ConfigurationProperties(prefix = "firemud.communication")
public record CommunicationProperties(int maxMessageLength) {
  public CommunicationProperties {
    maxMessageLength = maxMessageLength > 0 ? maxMessageLength : 512;
  }
}
