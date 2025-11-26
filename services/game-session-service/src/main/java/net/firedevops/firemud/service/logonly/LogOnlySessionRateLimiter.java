package net.firedevops.firemud.service.logonly;

import net.firedevops.firemud.service.SessionRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Allows all commands when running without Redis. */
@Service
@ConditionalOnProperty(name = "game-session.log-only", havingValue = "true")
public class LogOnlySessionRateLimiter implements SessionRateLimiter {
  @Override
  public boolean allow(long sessionId) {
    return true;
  }
}
