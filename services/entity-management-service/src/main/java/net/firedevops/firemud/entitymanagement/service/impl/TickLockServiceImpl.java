package net.firedevops.firemud.entitymanagement.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.conflict.ConflictTracker;
import net.firedevops.firemud.entitymanagement.service.TickLockService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Redis-backed implementation of {@link TickLockService}. */
@Service
@RequiredArgsConstructor
public class TickLockServiceImpl implements TickLockService {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "RedisTemplate is injected and used safely")
  private final StringRedisTemplate redisTemplate;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "MeterRegistry is thread-safe and injected by Spring")
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
        redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofMillis(computeLockTtlMs()));
    boolean acquired = Boolean.TRUE.equals(result);
    if (!acquired) {
      lockContentionCounter.increment();
      conflictTracker.recordConflict("entity:" + tenantId + ":" + entityId);
    }
    return acquired;
  }

  private long computeLockTtlMs() {
    long tickBudgetMs = (long) (tickDurationMs * 0.8);
    long lockTtl = tickBudgetMs * 8;
    if (lockTtl < 500L) {
      lockTtl = 500L;
    } else if (lockTtl > 5_000L) {
      lockTtl = 5_000L;
    }
    return lockTtl;
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
