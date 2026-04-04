package net.firedevops.firemud.gamelogic.config;

import net.firedevops.firemud.gamelogic.v1.CommunicationType;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Communication defaults exposed through the emerging platform settings model and metadata. */
@ConfigurationProperties(prefix = "firemud.communication")
public record CommunicationProperties(int maxMessageLength, Defaults defaults) {
  private static final int DEFAULT_MAX_MESSAGE_LENGTH = 512;

  public CommunicationProperties {
    maxMessageLength = maxMessageLength > 0 ? maxMessageLength : DEFAULT_MAX_MESSAGE_LENGTH;
    defaults = defaults == null ? new Defaults(true, true, true, true) : defaults;
  }

  public CommunicationProperties(int maxMessageLength) {
    this(maxMessageLength, new Defaults(true, true, true, true));
  }

  public CommunicationProperties() {
    this(DEFAULT_MAX_MESSAGE_LENGTH, new Defaults(true, true, true, true));
  }

  public boolean enabled(CommunicationType type) {
    return switch (type) {
      case SAY -> defaults.sayEnabled();
      case WHISPER -> defaults.whisperEnabled();
      case TELL -> defaults.tellEnabled();
      default -> true;
    };
  }

  public record Defaults(
      boolean sayEnabled,
      boolean whisperEnabled,
      boolean tellEnabled,
      boolean whisperObserverMetadataEnabled) {}
}
