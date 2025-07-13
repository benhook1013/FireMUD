package net.firedevops.firemud.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "firemud.chat")
public class ChatProperties {
  private ChatCacheSettings says = new ChatCacheSettings(7200L, 50);
  private ChatCacheSettings tells = new ChatCacheSettings(172800L, 50);
  private ChatCacheSettings guild = new ChatCacheSettings(172800L, 50);
  private ChatCacheSettings city = new ChatCacheSettings(172800L, 50);
  private ChatCacheSettings account = new ChatCacheSettings(172800L, 50);

  @Data
  public static class ChatCacheSettings {
    private long historyTtlSeconds;
    private int maxMessages;

    public ChatCacheSettings() {}

    public ChatCacheSettings(long ttl, int max) {
      this.historyTtlSeconds = ttl;
      this.maxMessages = max;
    }
  }
}
