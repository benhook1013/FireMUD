package net.firedevops.firemud.service.logonly;

import net.firedevops.firemud.service.IpConnectionLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Permissive IP limiter used when Redis is unavailable in dev. */
@Service
@ConditionalOnProperty(name = "game-session.log-only", havingValue = "true")
public class LogOnlyIpConnectionLimiter implements IpConnectionLimiter {
  @Override
  public boolean canAccept(String ip) {
    return true;
  }

  @Override
  public void register(String ip, long sessionId) {}

  @Override
  public void release(long sessionId) {}
}
