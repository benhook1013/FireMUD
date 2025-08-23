package net.firedevops.firemud.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.firedevops.firemud.service.IpConnectionLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Redis-backed implementation of {@link IpConnectionLimiter}. */
@Service
public class IpConnectionLimiterImpl implements IpConnectionLimiter {
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  private final StringRedisTemplate redisTemplate;

  private final int maxConnectionsPerIp;

  public IpConnectionLimiterImpl(
      StringRedisTemplate redisTemplate,
      @Value("${GAME_SESSION_MAX_CONNECTIONS_PER_IP:5}") int maxConnectionsPerIp) {
    this.redisTemplate = redisTemplate;
    this.maxConnectionsPerIp = maxConnectionsPerIp;
  }

  @Override
  public boolean canAccept(String ip) {
    String key = ipKey(ip);
    String value = redisTemplate.opsForValue().get(key);
    long count = value == null ? 0L : Long.parseLong(value);
    return count < maxConnectionsPerIp;
  }

  @Override
  public void register(String ip, long sessionId) {
    redisTemplate.opsForValue().increment(ipKey(ip));
    redisTemplate.opsForValue().set(sessionKey(sessionId), ip);
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
}
