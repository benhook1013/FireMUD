package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.automationscripting.service.tick.ScriptTickService;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.conflict.ConflictTracker;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

/** Redis-backed implementation of {@link ScriptTickService}. */
@Service
@RequiredArgsConstructor
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected dependencies are not exposed externally")
public class ScriptTickServiceImpl implements ScriptTickService {
  private static final Logger logger = LoggingUtil.getLogger(ScriptTickServiceImpl.class);

  private final RedisTemplate<String, Object> redisTemplate;
  private final MeterRegistry meterRegistry;
  private final net.firedevops.firemud.automationscripting.service.quota.ScriptQuotaService
      quotaService;
  private final ConflictTracker conflictTracker;

  @Value("${automation.tick-duration-ms:1000}")
  private long tickDurationMs;

  @Value("${automation.tick-max-events:50}")
  private int tickMaxEvents;

  private Counter enqueueCounter;
  private Counter redisErrorCounter;
  private Counter lockContentionCounter;
  private Counter budgetExceededCounter;
  private Timer tickTimer;
  private Timer luaTimer;
  private java.util.concurrent.atomic.AtomicInteger retryQueueDepth =
      new java.util.concurrent.atomic.AtomicInteger();
  private RedisScript<Long> stageScript;
  private RedisScript<Long> commitScript;
  private RedisScript<Long> rollbackScript;

  private Long executeScriptWithRetry(RedisScript<Long> script, List<String> keys, Object... args) {
    int attempts = 0;
    while (true) {
      try {
        return redisTemplate.execute(script, keys, args);
      } catch (Exception ex) {
        attempts++;
        redisErrorCounter.increment();
        if (attempts >= 3) {
          logger.error("Redis script execution failed after {} attempts", attempts, ex);
          throw ex;
        }
        logger.debug("Redis script execution failed, retrying attempt {}", attempts, ex);
        try {
          Thread.sleep(50L * attempts);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new RuntimeException("Retry interrupted", ie);
        }
      }
    }
  }

  @PostConstruct
  void init() {
    enqueueCounter = meterRegistry.counter("automation_tick_events_enqueued_total");
    redisErrorCounter = meterRegistry.counter("automation_tick_redis_errors_total");
    lockContentionCounter = meterRegistry.counter("automation_tick_lock_contention_total");
    budgetExceededCounter = meterRegistry.counter("automation_tick_budget_exceeded_total");
    tickTimer = meterRegistry.timer("automation_tick_duration_ms");
    luaTimer = meterRegistry.timer("automation_lua_latency_ms");
    Gauge.builder(
            "automation_retry_queue_depth",
            retryQueueDepth,
            java.util.concurrent.atomic.AtomicInteger::get)
        .register(meterRegistry);
    ResourceScriptSource commitSrc =
        new ResourceScriptSource(new ClassPathResource("redis/tick_commit.lua"));
    ResourceScriptSource stageSrc =
        new ResourceScriptSource(new ClassPathResource("redis/tick_stage.lua"));
    ResourceScriptSource rollbackSrc =
        new ResourceScriptSource(new ClassPathResource("redis/tick_rollback.lua"));
    stageScript = RedisScript.of(stageSrc.getResource(), Long.class);
    commitScript = RedisScript.of(commitSrc.getResource(), Long.class);
    rollbackScript = RedisScript.of(rollbackSrc.getResource(), Long.class);
  }

  private void awaitReplication() {
    try {
      redisTemplate.execute(
          (RedisCallback<Object>)
              connection -> {
                connection.execute(
                    "WAIT",
                    "1".getBytes(StandardCharsets.UTF_8),
                    "100".getBytes(StandardCharsets.UTF_8));
                return null;
              });
    } catch (Exception e) {
      logger.debug("WAIT replication failed", e);
    }
  }

  @Override
  @Timed(value = "automation.event.enqueue")
  public void enqueueEvent(Long tenantId, Long scriptId, String eventJson) {
    if (!quotaService.tryAcquire(tenantId, scriptId)) {
      logger.warn("Script quota exceeded for {}:{}", tenantId, scriptId);
      return;
    }
    redisTemplate.opsForList().rightPush(queueKey(tenantId, scriptId), eventJson);
    enqueueCounter.increment();
    logger.debug("Queued script event for {}:{}", tenantId, scriptId);
  }

  @Override
  @Timed(value = "automation.tick.process")
  public void processTick(Long tenantId, Long scriptId) {
    long start = System.nanoTime();
    String lockKey = lockKey(tenantId, scriptId);
    Boolean acquired =
        redisTemplate
            .opsForValue()
            .setIfAbsent(lockKey, "1", Duration.ofMillis(computeLockTtlMs()));
    if (Boolean.FALSE.equals(acquired)) {
      lockContentionCounter.increment();
      conflictTracker.recordConflict("script:" + tenantId + ":" + scriptId);
      logger.debug("Could not acquire tick lock {}", lockKey);
      return;
    }
    try {
      Long pending = redisTemplate.opsForList().size(pendingKey(tenantId, scriptId));
      retryQueueDepth.set(pending != null ? pending.intValue() : 0);
      if (pending != null && pending > 0) {
        logger.info("Replaying {} pending events for {}:{}", pending, tenantId, scriptId);
        tickTimer.record(
            () ->
                luaTimer.record(
                    () ->
                        executeScriptWithRetry(
                            commitScript, List.of(pendingKey(tenantId, scriptId)))));
        awaitReplication();
      }
      tickTimer.record(
          () ->
              luaTimer.record(
                  () ->
                      executeScriptWithRetry(
                          stageScript,
                          List.of(queueKey(tenantId, scriptId), pendingKey(tenantId, scriptId)),
                          tickMaxEvents)));
      tickTimer.record(
          () ->
              luaTimer.record(
                  () ->
                      executeScriptWithRetry(
                          commitScript, List.of(pendingKey(tenantId, scriptId)))));
      awaitReplication();
    } catch (Exception ex) {
      logger.error("Script tick failed, rolling back", ex);
      conflictTracker.recordConflict("script:" + tenantId + ":" + scriptId);
      luaTimer.record(
          () ->
              executeScriptWithRetry(
                  rollbackScript,
                  List.of(pendingKey(tenantId, scriptId), queueKey(tenantId, scriptId))));
      awaitReplication();
    } finally {
      long elapsed = (System.nanoTime() - start) / 1_000_000;
      long budgetMs = (long) (tickDurationMs * 0.8);
      if (elapsed > budgetMs) {
        budgetExceededCounter.increment();
        logger.debug("Tick budget exceeded: {} ms (budget {} ms)", elapsed, budgetMs);
      }
      redisTemplate.delete(lockKey);
    }
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

  private String queueKey(Long tenantId, Long scriptId) {
    return "tick:queue:" + tenantId + ":" + scriptId;
  }

  private String lockKey(Long tenantId, Long scriptId) {
    return "tick:lock:" + tenantId + ":" + scriptId;
  }

  private String pendingKey(Long tenantId, Long scriptId) {
    return "tick:pending:" + tenantId + ":" + scriptId;
  }
}
