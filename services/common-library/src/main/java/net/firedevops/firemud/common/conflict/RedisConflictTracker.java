package net.firedevops.firemud.common.conflict;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Records conflict metadata in Redis for hotspot detection. */
@Component
@RequiredArgsConstructor
@ConditionalOnBean(StringRedisTemplate.class)
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected dependencies are safe to store without defensive copies")
public class RedisConflictTracker implements ConflictTracker {
  private final StringRedisTemplate redisTemplate;
  private final MeterRegistry meterRegistry;

  @Value("${firemud.conflict.ttlSeconds:300}")
  private long ttlSeconds;

  @Override
  public void recordConflict(String key) {
    String redisKey = "conflict:" + key;
    redisTemplate.opsForValue().increment(redisKey);
    redisTemplate.expire(redisKey, Duration.ofSeconds(ttlSeconds));
    meterRegistry.counter("tick_conflict_hotspot_detected_total").increment();
  }
}
