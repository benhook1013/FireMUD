package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import net.firedevops.firemud.common.redis.RedisAtomicOperations;
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.gamesession.service.SessionRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Redis-backed implementation of {@link SessionRateLimiter}. */
@Service
@ConditionalOnProperty(
    name = "game-session.dev-isolated",
    havingValue = "false",
    matchIfMissing = false)
public class SessionRateLimiterImpl implements SessionRateLimiter {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "RedisTemplate is injected and not exposed")
  private final StringRedisTemplate redisTemplate;

  private final int maxMessagesPerSecond;
  private final DevIsolatedProperties devIsolatedProperties;

  public SessionRateLimiterImpl(
      StringRedisTemplate redisTemplate,
      @Value("${GAME_SESSION_MAX_MSGS_PER_SEC:20}") int maxMessagesPerSecond,
      DevIsolatedProperties devIsolatedProperties) {
    this.redisTemplate = redisTemplate;
    this.maxMessagesPerSecond = maxMessagesPerSecond;
    this.devIsolatedProperties = devIsolatedProperties;
  }

  @Override
  public boolean allow(long sessionId) {
    if (devIsolatedProperties.isDevIsolated()) {
      return true;
    }

    String key = key(sessionId);
    Long count = RedisAtomicOperations.incrementWithTtl(redisTemplate, key, Duration.ofSeconds(1));
    return count != null && count <= maxMessagesPerSecond;
  }

  private String key(long sessionId) {
    return "ratelimit:" + sessionId;
  }
}
