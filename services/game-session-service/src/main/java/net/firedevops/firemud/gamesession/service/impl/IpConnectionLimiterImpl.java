package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.gamesession.service.IpConnectionLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Redis-backed implementation of {@link IpConnectionLimiter}. */
@Service
@ConditionalOnProperty(
    name = "game-session.dev-isolated",
    havingValue = "false",
    matchIfMissing = false)
public class IpConnectionLimiterImpl implements IpConnectionLimiter {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "RedisTemplate is injected and not exposed")
  private final StringRedisTemplate redisTemplate;

  private final int maxConnectionsPerIp;

  private final Duration entryTtl;
  private final DevIsolatedProperties devIsolatedProperties;

  public IpConnectionLimiterImpl(
      StringRedisTemplate redisTemplate,
      @Value("${GAME_SESSION_MAX_CONNECTIONS_PER_IP:5}") int maxConnectionsPerIp,
      @Value("${GAME_SESSION_CONN_TTL_SEC:3600}") long entryTtlSeconds,
      DevIsolatedProperties devIsolatedProperties) {
    this.redisTemplate = redisTemplate;
    this.maxConnectionsPerIp = maxConnectionsPerIp;
    this.entryTtl = Duration.ofSeconds(entryTtlSeconds);
    this.devIsolatedProperties = devIsolatedProperties;
  }

  @Override
  public boolean canAccept(String ip) {
    if (devIsolatedProperties.isDevIsolated()) {
      return true;
    }

    String key = ipKey(ip);
    String value = redisTemplate.opsForValue().get(key);
    long count = value == null ? 0L : Long.parseLong(value);
    return count < maxConnectionsPerIp;
  }

  @Override
  public void register(String ip, long sessionId) {
    if (devIsolatedProperties.isDevIsolated()) {
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
    if (devIsolatedProperties.isDevIsolated()) {
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
