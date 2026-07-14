package net.firedevops.firemud.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Operator maximum for the bounded durable accepted-command history shown by {@code HISTORY}. */
@ConfigurationProperties(prefix = "firemud.command-history")
public record FiremudCommandHistoryProperties(int maxEntries) {
  public FiremudCommandHistoryProperties {
    maxEntries = maxEntries > 0 ? Math.min(maxEntries, 20) : 10;
  }
}
