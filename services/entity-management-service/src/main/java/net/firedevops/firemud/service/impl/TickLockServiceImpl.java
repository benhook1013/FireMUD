package net.firedevops.firemud.service.impl;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.service.TickLockService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Redis-backed implementation of {@link TickLockService}. */
@Service
@RequiredArgsConstructor
public class TickLockServiceImpl implements TickLockService {
  private final StringRedisTemplate redisTemplate;

  @Value("${game.tick-duration-ms:1000}")
  private long tickDurationMs;

  @Override
  public boolean acquireLock(Long entityId) {
    String key = lockKey(entityId);
    Boolean result =
        redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofMillis(tickDurationMs));
    return Boolean.TRUE.equals(result);
  }

  @Override
  public void releaseLock(Long entityId) {
    redisTemplate.delete(lockKey(entityId));
  }

  private String lockKey(Long entityId) {
    return "tick:lock:" + entityId;
  }
}
