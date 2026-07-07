package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.redis.RedisAtomicOperations;
import net.firedevops.firemud.gamesession.service.IpConnectionLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Redis-backed implementation of {@link IpConnectionLimiter}. */
@Service
public class IpConnectionLimiterImpl implements IpConnectionLimiter {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "RedisTemplate is injected and not exposed")
  private final StringRedisTemplate redisTemplate;

  private final int maxConnectionsPerIp;

  private final Duration entryTtl;

  public IpConnectionLimiterImpl(
      StringRedisTemplate redisTemplate,
      @Value("${GAME_SESSION_MAX_CONNECTIONS_PER_IP:5}") int maxConnectionsPerIp,
      @Value("${GAME_SESSION_CONN_TTL_SEC:3600}") long entryTtlSeconds) {
    this.redisTemplate = redisTemplate;
    this.maxConnectionsPerIp = maxConnectionsPerIp;
    this.entryTtl = Duration.ofSeconds(entryTtlSeconds);
  }

  @Override
  public boolean canAccept(String ip) {
    long count = currentConnectionCount(ip);
    return count < maxConnectionsPerIp;
  }

  @Override
  public boolean canAccept(String ip, Long replacingSessionId) {
    if (canAccept(ip)) {
      return true;
    }
    if (replacingSessionId == null) {
      return false;
    }
    String currentIp = redisTemplate.opsForValue().get(sessionKey(replacingSessionId));
    return ip.equals(currentIp);
  }

  @Override
  public boolean tryRegister(String ip, long sessionId) {
    return RedisAtomicOperations.reserveBoundedCounter(
        redisTemplate, ipKey(ip), sessionKey(sessionId), maxConnectionsPerIp, entryTtl, ip);
  }

  @Override
  public boolean transferRegistration(String ip, long previousSessionId, long newSessionId) {
    String previousSessionKey = sessionKey(previousSessionId);
    String currentIp = redisTemplate.opsForValue().get(previousSessionKey);
    if (!ip.equals(currentIp)) {
      return false;
    }

    Long ttlMs = redisTemplate.getExpire(previousSessionKey, TimeUnit.MILLISECONDS);
    Duration ttl = ttlMs != null && ttlMs > 0 ? Duration.ofMillis(ttlMs) : entryTtl;
    redisTemplate.opsForValue().set(sessionKey(newSessionId), ip, ttl);
    redisTemplate.delete(previousSessionKey);
    return true;
  }

  @Override
  public void release(long sessionId) {
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

  private long currentConnectionCount(String ip) {
    String key = ipKey(ip);
    String value = redisTemplate.opsForValue().get(key);
    if (value == null) {
      return 0L;
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ex) {
      return Long.MAX_VALUE;
    }
  }
}
