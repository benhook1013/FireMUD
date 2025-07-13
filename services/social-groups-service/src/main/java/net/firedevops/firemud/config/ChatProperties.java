package net.firedevops.firemud.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "firemud.chat")
public class ChatProperties {
  /** Time to keep chat history in Redis (seconds). */
  private long historyTtlSeconds = 3600;
}
