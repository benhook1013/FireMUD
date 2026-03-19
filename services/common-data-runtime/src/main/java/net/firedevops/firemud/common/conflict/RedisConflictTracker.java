package net.firedevops.firemud.common.conflict;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Records conflict metadata in Redis for hotspot detection. */
@RequiredArgsConstructor
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected dependencies are safe to store without defensive copies")
public class RedisConflictTracker implements ConflictTracker {
  private static final Logger logger = LoggingUtil.getLogger(RedisConflictTracker.class);
  private final StringRedisTemplate redisTemplate;
  private final MeterRegistry meterRegistry;

  @Value("${firemud.conflict.ttlSeconds:300}")
  private long ttlSeconds;

  @Override
  public void recordConflict(String key) {
    String redisKey = "conflict:" + key;
    redisTemplate.opsForValue().increment(redisKey);
    boolean expirationSet =
        Boolean.TRUE.equals(redisTemplate.expire(redisKey, Duration.ofSeconds(ttlSeconds)));
    if (!expirationSet) {
      logger.warn("Failed to set expiration for {}", redisKey);
    }
    meterRegistry.counter("tick_conflict_hotspot_detected_total").increment();
  }
}
