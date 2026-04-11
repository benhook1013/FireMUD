package net.firedevops.firemud.gamesession.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Gameplay presence and activity-policy defaults. */
@ConfigurationProperties(prefix = "firemud.presence")
public class PresenceProperties {
  /** Whether inactivity should derive auto-AFK state from gameplay activity timestamps. */
  private boolean autoAfkEnabled = false;

  /** Inactivity threshold, in milliseconds, after which auto-AFK becomes true. */
  private long autoAfkThresholdMs = 300_000L;

  public boolean isAutoAfkEnabled() {
    return autoAfkEnabled;
  }

  public void setAutoAfkEnabled(boolean autoAfkEnabled) {
    this.autoAfkEnabled = autoAfkEnabled;
  }

  public long getAutoAfkThresholdMs() {
    return autoAfkThresholdMs;
  }

  public void setAutoAfkThresholdMs(long autoAfkThresholdMs) {
    this.autoAfkThresholdMs = autoAfkThresholdMs;
  }
}
