package net.firedevops.firemud.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import net.firedevops.firemud.config.LogOnlyProperties;
import net.firedevops.firemud.service.SessionRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Redis-backed implementation of {@link SessionRateLimiter}. */
@Service
@ConditionalOnProperty(name = "game-session.log-only", havingValue = "false", matchIfMissing = false)
public class SessionRateLimiterImpl implements SessionRateLimiter {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "RedisTemplate is injected and not exposed")
  private final StringRedisTemplate redisTemplate;

  private final int maxMessagesPerSecond;
  private final LogOnlyProperties logOnlyProperties;

  public SessionRateLimiterImpl(
      StringRedisTemplate redisTemplate,
      @Value("${GAME_SESSION_MAX_MSGS_PER_SEC:20}") int maxMessagesPerSecond,
      LogOnlyProperties logOnlyProperties) {
    this.redisTemplate = redisTemplate;
    this.maxMessagesPerSecond = maxMessagesPerSecond;
    this.logOnlyProperties = logOnlyProperties;
  }

  @Override
  public boolean allow(long sessionId) {
    if (logOnlyProperties.isLogOnly()) {
      return true;
    }

    String key = key(sessionId);
    Long count = redisTemplate.opsForValue().increment(key);
    if (Long.valueOf(1L).equals(count)) {
      redisTemplate.expire(key, Duration.ofSeconds(1));
    }
    return count != null && count <= maxMessagesPerSecond;
  }

  private String key(long sessionId) {
    return "ratelimit:" + sessionId;
  }
}
