package net.firedevops.firemud.entitymanagement.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.conflict.ConflictTracker;
import net.firedevops.firemud.entitymanagement.service.TickLockService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/** Redis-backed implementation of {@link TickLockService}. */
@Service
@RequiredArgsConstructor
public class TickLockServiceImpl implements TickLockService {
  private static final RedisScript<Long> RELEASE_LOCK_SCRIPT =
      redisScript(
          """
          if redis.call('GET', KEYS[1]) == ARGV[1] then
            return redis.call('DEL', KEYS[1])
          end
          return 0
          """);

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "RedisTemplate is injected and used safely")
  private final StringRedisTemplate redisTemplate;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "MeterRegistry is thread-safe and injected by Spring")
  private final MeterRegistry meterRegistry;

  private final ConflictTracker conflictTracker;
  private final ConcurrentMap<String, String> acquiredLockTokens = new ConcurrentHashMap<>();

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
    String token = lockToken(tenantId, entityId);
    Boolean result =
        redisTemplate.opsForValue().setIfAbsent(key, token, Duration.ofMillis(computeLockTtlMs()));
    boolean acquired = Boolean.TRUE.equals(result);
    if (!acquired) {
      lockContentionCounter.increment();
      conflictTracker.recordConflict("entity:" + tenantId + ":" + entityId);
      return false;
    }
    acquiredLockTokens.put(key, token);
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
    String key = lockKey(tenantId, entityId);
    String token = acquiredLockTokens.remove(key);
    if (token != null) {
      redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(key), token);
    }
  }

  private String lockKey(Long tenantId, Long entityId) {
    return "tick:lock:" + tenantId + ":" + entityId;
  }

  private String lockToken(Long tenantId, Long entityId) {
    return tenantId + ":" + entityId + ":" + UUID.randomUUID();
  }

  private static RedisScript<Long> redisScript(String scriptText) {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setScriptText(scriptText);
    script.setResultType(Long.class);
    return script;
  }
}
