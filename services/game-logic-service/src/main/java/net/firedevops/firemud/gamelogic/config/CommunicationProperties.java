package net.firedevops.firemud.gamelogic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Communication defaults exposed through the emerging platform settings model and metadata. */
@ConfigurationProperties(prefix = "firemud.communication")
public record CommunicationProperties(int maxMessageLength) {
  private static final int DEFAULT_MAX_MESSAGE_LENGTH = 512;

  public CommunicationProperties {
    maxMessageLength = maxMessageLength > 0 ? maxMessageLength : DEFAULT_MAX_MESSAGE_LENGTH;
  }
}
