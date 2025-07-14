package net.firedevops.firemud.common.conflict;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Records conflict metadata in Redis for hotspot detection. */
@Component
@RequiredArgsConstructor
public class RedisConflictTracker implements ConflictTracker {
  private final StringRedisTemplate redisTemplate;

  @Value("${firemud.conflict.ttlSeconds:300}")
  private long ttlSeconds;

  @Override
  public void recordConflict(String key) {
    String redisKey = "conflict:" + key;
    redisTemplate.opsForValue().increment(redisKey);
    redisTemplate.expire(redisKey, Duration.ofSeconds(ttlSeconds));
  }
}
