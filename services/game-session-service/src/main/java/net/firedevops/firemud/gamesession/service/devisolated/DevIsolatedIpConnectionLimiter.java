package net.firedevops.firemud.gamesession.service.devisolated;

import net.firedevops.firemud.gamesession.service.IpConnectionLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Permissive IP limiter used when Redis is unavailable in dev. */
@Service
@ConditionalOnProperty(name = "game-session.dev-isolated", havingValue = "true")
public class DevIsolatedIpConnectionLimiter implements IpConnectionLimiter {
  @Override
  public boolean canAccept(String ip) {
    return true;
  }

  @Override
  public boolean tryRegister(String ip, long sessionId) {
    return true;
  }

  @Override
  public boolean transferRegistration(String ip, long previousSessionId, long newSessionId) {
    return true;
  }

  @Override
  public void release(long sessionId) {}
}
