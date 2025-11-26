package net.firedevops.firemud.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import net.firedevops.firemud.config.LogOnlyProperties;
import net.firedevops.firemud.service.IpConnectionLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Redis-backed implementation of {@link IpConnectionLimiter}. */
@Service
@ConditionalOnProperty(name = "game-session.log-only", havingValue = "false", matchIfMissing = false)
public class IpConnectionLimiterImpl implements IpConnectionLimiter {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "RedisTemplate is injected and not exposed")
  private final StringRedisTemplate redisTemplate;

  private final int maxConnectionsPerIp;

  private final Duration entryTtl;
  private final LogOnlyProperties logOnlyProperties;

  public IpConnectionLimiterImpl(
      StringRedisTemplate redisTemplate,
      @Value("${GAME_SESSION_MAX_CONNECTIONS_PER_IP:5}") int maxConnectionsPerIp,
      @Value("${GAME_SESSION_CONN_TTL_SEC:3600}") long entryTtlSeconds,
      LogOnlyProperties logOnlyProperties) {
    this.redisTemplate = redisTemplate;
    this.maxConnectionsPerIp = maxConnectionsPerIp;
    this.entryTtl = Duration.ofSeconds(entryTtlSeconds);
    this.logOnlyProperties = logOnlyProperties;
  }

  @Override
  public boolean canAccept(String ip) {
    if (logOnlyProperties.isLogOnly()) {
      return true;
    }

    String key = ipKey(ip);
    String value = redisTemplate.opsForValue().get(key);
    long count = value == null ? 0L : Long.parseLong(value);
    return count < maxConnectionsPerIp;
  }

  @Override
  public void register(String ip, long sessionId) {
    if (logOnlyProperties.isLogOnly()) {
      return;
    }

    String ipKey = ipKey(ip);
    Long count = redisTemplate.opsForValue().increment(ipKey);
    if (Long.valueOf(1L).equals(count)) {
      redisTemplate.expire(ipKey, entryTtl);
    }
    redisTemplate.opsForValue().set(sessionKey(sessionId), ip, entryTtl);
  }

  @Override
  public void release(long sessionId) {
    if (logOnlyProperties.isLogOnly()) {
      return;
    }

    String sessionKey = sessionKey(sessionId);
    String ip = redisTemplate.opsForValue().get(sessionKey);
    if (ip != null) {
      Long remaining = redisTemplate.opsForValue().decrement(ipKey(ip));
      if (remaining != null && remaining <= 0) {
        redisTemplate.delete(ipKey(ip));
      }
      redisTemplate.delete(sessionKey);
    }
  }

  private String ipKey(String ip) {
    return "ipconn:" + ip;
  }

  private String sessionKey(long sessionId) {
    return "sessionip:" + sessionId;
  }
}
