package net.firedevops.firemud.service.impl;

import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.service.TickService;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

/** Default Redis-backed implementation of {@link TickService}. */
@Service
@RequiredArgsConstructor
public class TickServiceImpl implements TickService {
  private static final Logger logger = LoggingUtil.getLogger(TickServiceImpl.class);

  private final RedisTemplate<String, Object> redisTemplate;
  private final MeterRegistry meterRegistry;

  @Value("${game.tick-duration-ms:1000}")
  private long tickDurationMs;

  @Value("${game.tick-budget-ms:100}")
  private long tickBudgetMs;

  @Value("${game.solo-tick-budget-ms:500}")
  private long soloTickBudgetMs;

  @Value("${game.tick-max-commands:50}")
  private int tickMaxCommands;

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
    this.enqueueCounter = meterRegistry.counter("game_session_commands_enqueued_total");
    this.redisErrorCounter = meterRegistry.counter("game_session_redis_errors_total");
    this.lockContentionCounter = meterRegistry.counter("game_session_lock_contention_total");
    this.budgetExceededCounter = meterRegistry.counter("game_session_tick_budget_exceeded_total");
    this.tickTimer = meterRegistry.timer("game_session_tick_duration_ms");
    this.luaTimer = meterRegistry.timer("game_session_lua_latency_ms");
    Gauge.builder(
            "game_session_retry_queue_depth",
            retryQueueDepth,
            java.util.concurrent.atomic.AtomicInteger::get)
        .register(meterRegistry);
    ResourceScriptSource commitSrc =
        new ResourceScriptSource(new ClassPathResource("redis/tick_commit.lua"));
    ResourceScriptSource stageSrc =
        new ResourceScriptSource(new ClassPathResource("redis/tick_stage.lua"));
    ResourceScriptSource rollbackSrc =
        new ResourceScriptSource(new ClassPathResource("redis/tick_rollback.lua"));
    this.stageScript = RedisScript.of(stageSrc.getResource(), Long.class);
    this.commitScript = RedisScript.of(commitSrc.getResource(), Long.class);
    this.rollbackScript = RedisScript.of(rollbackSrc.getResource(), Long.class);
  }

  private void awaitReplication() {
    try {
      redisTemplate.execute(
          (RedisCallback<Object>)
              connection -> {
                connection.execute("WAIT", "1".getBytes(), "100".getBytes());
                return null;
              });
    } catch (Exception e) {
      logger.debug("WAIT replication failed", e);
    }
  }

  @Override
  @Timed(value = "gamesession.command.enqueue")
  public void enqueueCommand(Long sessionId, String command, boolean requiresSoloTick) {
    String value = (requiresSoloTick ? "S|" : "N|") + command;
    redisTemplate.opsForList().rightPush(queueKey(sessionId), value);
    enqueueCounter.increment();
    logger.debug("Queued command for {}", sessionId);
  }

  @Override
  @Timed(value = "gamesession.tick.process")
  @Async("tickExecutor")
  public void processTick(Long sessionId) {
    long start = System.nanoTime();
    String lockKey = lockKey(sessionId);
    Boolean acquired =
        redisTemplate.opsForValue().setIfAbsent(lockKey, "1", Duration.ofMillis(tickDurationMs));
    if (Boolean.FALSE.equals(acquired)) {
      lockContentionCounter.increment();
      logger.debug("Could not acquire tick lock {}", lockKey);
      return;
    }
    String head = null;
    boolean solo = false;
    try {
      Long pending = redisTemplate.opsForList().size(pendingKey(sessionId));
      retryQueueDepth.set(pending != null ? pending.intValue() : 0);
      if (pending != null && pending > 0) {
        logger.info("Replaying {} pending commands for {}", pending, sessionId);
        tickTimer.record(
            () ->
                luaTimer.record(
                    () -> executeScriptWithRetry(commitScript, List.of(pendingKey(sessionId)))));
        awaitReplication();
      }
      Object headObj = redisTemplate.opsForList().index(queueKey(sessionId), 0);
      head = headObj != null ? headObj.toString() : null;
      solo = head != null && head.startsWith("S|");
      int max = solo ? 1 : tickMaxCommands;
      tickTimer.record(
          () ->
              luaTimer.record(
                  () ->
                      executeScriptWithRetry(
                          stageScript, List.of(queueKey(sessionId), pendingKey(sessionId)), max)));
      tickTimer.record(
          () ->
              luaTimer.record(
                  () -> executeScriptWithRetry(commitScript, List.of(pendingKey(sessionId)))));
      awaitReplication();
    } catch (Exception ex) {
      logger.error("Tick processing failed, rolling back", ex);
      luaTimer.record(
          () ->
              executeScriptWithRetry(
                  rollbackScript, List.of(pendingKey(sessionId), queueKey(sessionId))));
      awaitReplication();
    } finally {
      long elapsed = (System.nanoTime() - start) / 1_000_000;
      long budget = solo ? soloTickBudgetMs : tickBudgetMs;
      if (elapsed > budget) {
        budgetExceededCounter.increment();
        logger.debug("Tick budget exceeded: {} ms", elapsed);
      }
      redisTemplate.delete(lockKey);
    }
  }

  @Override
  @Timed(value = "gamesession.state.query")
  public String queryState(Long sessionId) {
    Object state = redisTemplate.opsForValue().get(stateKey(sessionId));
    return state != null ? state.toString() : "{}";
  }

  private String queueKey(Long sessionId) {
    return "tick:queue:" + sessionId;
  }

  private String lockKey(Long sessionId) {
    return "tick:lock:" + sessionId;
  }

  private String stateKey(Long sessionId) {
    return "session:" + sessionId;
  }

  private String pendingKey(Long sessionId) {
    return "tick:pending:" + sessionId;
  }
}
