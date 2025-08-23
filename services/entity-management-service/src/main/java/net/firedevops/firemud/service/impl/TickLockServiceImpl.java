package net.firedevops.firemud.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.conflict.ConflictTracker;
import net.firedevops.firemud.service.TickLockService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Redis-backed implementation of {@link TickLockService}. */
@Service
@RequiredArgsConstructor
public class TickLockServiceImpl implements TickLockService {
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  private final StringRedisTemplate redisTemplate;

  @SuppressFBWarnings("EI_EXPOSE_REP2")
  private final MeterRegistry meterRegistry;

  private final ConflictTracker conflictTracker;

  private Counter lockContentionCounter;

  @Value("${game.tick-duration-ms:1000}")
  private long tickDurationMs;

  @PostConstruct
  void initMetrics() {
    lockContentionCounter = meterRegistry.counter("tick_lock_contention_total");
  }

  @Override
  @Timed(value = "tick.lock.acquire")
  public boolean acquireLock(Long tenantId, Long entityId) {
    String key = lockKey(tenantId, entityId);
    Boolean result =
        redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofMillis(tickDurationMs));
    boolean acquired = Boolean.TRUE.equals(result);
    if (!acquired) {
      lockContentionCounter.increment();
      conflictTracker.recordConflict("entity:" + tenantId + ":" + entityId);
    }
    return acquired;
  }

  @Override
  @Timed(value = "tick.lock.release")
  public void releaseLock(Long tenantId, Long entityId) {
    redisTemplate.delete(lockKey(tenantId, entityId));
  }

  private String lockKey(Long tenantId, Long entityId) {
    return "tick:lock:" + tenantId + ":" + entityId;
  }
}
