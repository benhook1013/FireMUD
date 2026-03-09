package net.firedevops.firemud.gamesession.service.devisolated;

import net.firedevops.firemud.gamesession.service.SessionRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Allows all commands when running without Redis. */
@Service
@ConditionalOnProperty(name = "game-session.dev-isolated", havingValue = "true")
public class DevIsolatedSessionRateLimiter implements SessionRateLimiter {
  @Override
  public boolean allow(long sessionId) {
    return true;
  }
}
