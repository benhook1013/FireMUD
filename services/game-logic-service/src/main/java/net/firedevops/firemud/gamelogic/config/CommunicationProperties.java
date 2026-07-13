package net.firedevops.firemud.gamelogic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Communication behavior settings exposed through the platform settings model and metadata. */
@ConfigurationProperties(prefix = "firemud.communication")
public record CommunicationProperties(
    int maxMessageLength, boolean whisperObserverMetadataEnabled) {
  private static final int DEFAULT_MAX_MESSAGE_LENGTH = 512;

  public CommunicationProperties {
    maxMessageLength = maxMessageLength > 0 ? maxMessageLength : DEFAULT_MAX_MESSAGE_LENGTH;
  }

  public CommunicationProperties() {
    this(DEFAULT_MAX_MESSAGE_LENGTH, true);
  }
}
