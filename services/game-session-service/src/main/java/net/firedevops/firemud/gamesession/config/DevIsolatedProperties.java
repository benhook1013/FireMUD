package net.firedevops.firemud.gamesession.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DevIsolatedProperties {
  private final boolean devIsolated;

  public DevIsolatedProperties(
      @Value("${game-session.dev-isolated:${GAME_SESSION_DEV_ISOLATED:false}}")
          boolean devIsolated) {
    this.devIsolated = devIsolated;
  }

  public boolean isDevIsolated() {
    return devIsolated;
  }
}
