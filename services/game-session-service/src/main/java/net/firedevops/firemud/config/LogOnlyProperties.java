package net.firedevops.firemud.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LogOnlyProperties {
  private final boolean logOnly;

  public LogOnlyProperties(
      @Value("${game-session.log-only:${GAME_SESSION_LOG_ONLY:false}}") boolean logOnly) {
    this.logOnly = logOnly;
  }

  public boolean isLogOnly() {
    return logOnly;
  }
}
