package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.conflict.ConflictTracker;
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.TickService;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

/** Default Redis-backed implementation of {@link TickService}. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected dependencies are kept internal")
@Service
@ConditionalOnProperty(
    name = "game-session.dev-isolated",
    havingValue = "false",
    matchIfMissing = false)
@RequiredArgsConstructor
public class TickServiceImpl implements TickService {
  private static final Logger logger = LoggingUtil.getLogger(TickServiceImpl.class);

  private final RedisTemplate<String, Object> redisTemplate;
  private final MeterRegistry meterRegistry;
  private final ConflictTracker conflictTracker;
  private final GameInstanceRepository gameInstanceRepository;
  private final DevIsolatedProperties devIsolatedProperties;

  @Value("${game.tick-duration-ms:1000}")
  private long tickDurationMs;

  @Value("${game.tick-max-commands:50}")
  private int tickMaxCommands;

  @Value("${game.tick-budget-ms:100}")
  private long tickBudgetMs;

  @Value("${game.solo-tick-budget-ms:500}")
  private long soloTickBudgetMs;

  private Counter enqueueCounter;
  private Counter redisErrorCounter;
  private Counter lockContentionCounter;
  private Counter budgetExceededCounter;
  private Counter requeuedActionCounter;
  private Counter retryBackoffCounter;
  private Timer tickTimer;
  private Timer luaTimer;
  private RedisScript<Long> stageScript;
  private RedisScript<Long> commitScript;
  private RedisScript<Long> rollbackScript;
  private final AtomicBoolean pauseRequested = new AtomicBoolean(false);
  private final Set<Long> pausedGameInstances = ConcurrentHashMap.newKeySet();
  private final AtomicInteger activeTicks = new AtomicInteger();
  private final Map<String, AtomicLong> retryQueueDepthGauges = new ConcurrentHashMap<>();

  private Long executeScriptWithRetry(RedisScript<Long> script, List<String> keys, Object... args) {
    int attempts = 0;
    while (true) {
      try {
        return redisTemplate.execute(script, keys, args);
      } catch (Exception ex) {
        attempts++;
        redisErrorCounter.increment();
        retryBackoffCounter.increment();
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
    this.requeuedActionCounter =
        meterRegistry.counter("tick_requeued_action_total", "source", "player");
    this.retryBackoffCounter = meterRegistry.counter("tick_retry_backoff_count_total");
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
  @Timed(value = "gamesession.command.enqueue")
  public void enqueueCommand(Long sessionId, String command, boolean requiresSoloTick) {
    if (devIsolatedProperties.isDevIsolated()) {
      logger.info(
          "Dev-isolated mode enabled; recording enqueue request for session {} command {}",
          sessionId,
          command);
      return;
    }

    Long tenantId = findTenantId(sessionId);
    String value = (requiresSoloTick ? "S|" : "N|") + command;
    redisTemplate.opsForList().rightPush(queueKey(tenantId, sessionId), value);
    enqueueCounter.increment();
    logger.debug("Queued command for {}:{}", tenantId, sessionId);
  }

  @Override
  @Timed(value = "gamesession.tick.process")
  @Async("tickExecutor")
  public void processTick(Long sessionId) {
    if (devIsolatedProperties.isDevIsolated()) {
      logger.info("Dev-isolated mode enabled; skipping tick processing for session {}", sessionId);
      return;
    }

    if (pauseRequested.get() || pausedGameInstances.contains(sessionId)) {
      logger.debug("Tick processing skipped while paused");
      return;
    }
    long start = System.nanoTime();
    Long tenantId = findTenantId(sessionId);
    String lockKey = lockKey(tenantId, sessionId);
    Boolean acquired =
        redisTemplate.opsForValue().setIfAbsent(lockKey, "1", Duration.ofMillis(tickDurationMs));
    if (Boolean.FALSE.equals(acquired)) {
      lockContentionCounter.increment();
      conflictTracker.recordConflict("session:" + tenantId + ":" + sessionId);
      logger.debug("Could not acquire tick lock {}", lockKey);
      return;
    }
    activeTicks.incrementAndGet();
    String head = null;
    boolean solo = false;
    try {
      Long pending = redisTemplate.opsForList().size(pendingKey(tenantId, sessionId));
      long depth = pending != null ? pending : 0L;
      retryQueueDepthGauge(tenantId, sessionId).set(depth);
      if (pending != null && pending > 0) {
        logger.info("Replaying {} pending commands for {}", pending, sessionId);
        tickTimer.record(
            () ->
                luaTimer.record(
                    () ->
                        executeScriptWithRetry(
                            commitScript, List.of(pendingKey(tenantId, sessionId)))));
        awaitReplication();
      }
      Object headObj = redisTemplate.opsForList().index(queueKey(tenantId, sessionId), 0);
      head = headObj != null ? headObj.toString() : null;
      solo = head != null && head.startsWith("S|");
      int max = solo ? 1 : tickMaxCommands;
      tickTimer.record(
          () ->
              luaTimer.record(
                  () ->
                      executeScriptWithRetry(
                          stageScript,
                          List.of(queueKey(tenantId, sessionId), pendingKey(tenantId, sessionId)),
                          max)));
      tickTimer.record(
          () ->
              luaTimer.record(
                  () ->
                      executeScriptWithRetry(
                          commitScript, List.of(pendingKey(tenantId, sessionId)))));
      awaitReplication();
    } catch (Exception ex) {
      logger.error("Tick processing failed, rolling back", ex);
      conflictTracker.recordConflict("session:" + tenantId + ":" + sessionId);
      luaTimer.record(
          () ->
              executeScriptWithRetry(
                  rollbackScript,
                  List.of(pendingKey(tenantId, sessionId), queueKey(tenantId, sessionId))));
      requeuedActionCounter.increment();
      awaitReplication();
    } finally {
      long elapsed = (System.nanoTime() - start) / 1_000_000;
      long budget = solo ? soloTickBudgetMs : tickBudgetMs;
      if (elapsed > budget) {
        budgetExceededCounter.increment();
        logger.debug("Tick budget exceeded: {} ms", elapsed);
      }
      redisTemplate.delete(lockKey);
      activeTicks.decrementAndGet();
    }
  }

  private AtomicLong retryQueueDepthGauge(Long tenantId, Long sessionId) {
    String gaugeKey = tenantId + ":" + sessionId;
    return retryQueueDepthGauges.computeIfAbsent(
        gaugeKey,
        ignored ->
            meterRegistry.gauge(
                "tick_retry_queue_depth",
                io.micrometer.core.instrument.Tags.of(
                    "tenantId", tenantId.toString(), "regionId", sessionId.toString()),
                new AtomicLong()));
  }

  @Override
  @Timed(value = "gamesession.state.query")
  public String queryState(Long sessionId) {
    if (devIsolatedProperties.isDevIsolated()) {
      logger.info("Dev-isolated mode enabled; returning empty state for session {}", sessionId);
      return "{}";
    }

    Long tenantId = findTenantId(sessionId);
    Object state = redisTemplate.opsForValue().get(stateKey(tenantId, sessionId));
    return state != null ? state.toString() : "{}";
  }

  @Override
  public void pauseTicks(String reason) {
    pauseRequested.set(true);
    logger.info("Tick pause requested: {}", reason);
  }

  @Override
  public void resumeTicks(String reason) {
    pauseRequested.set(false);
    logger.info("Tick resume requested: {}", reason);
  }

  @Override
  public void pauseTicksForGameInstance(Long gameInstanceId, String reason) {
    if (gameInstanceId == null) {
      throw new IllegalArgumentException("gameInstanceId is required");
    }
    pausedGameInstances.add(gameInstanceId);
    logger.info("Tick pause requested for game instance {}: {}", gameInstanceId, reason);
  }

  @Override
  public void resumeTicksForGameInstance(Long gameInstanceId, String reason) {
    if (gameInstanceId == null) {
      throw new IllegalArgumentException("gameInstanceId is required");
    }
    pausedGameInstances.remove(gameInstanceId);
    logger.info("Tick resume requested for game instance {}: {}", gameInstanceId, reason);
  }

  @Override
  public net.firedevops.firemud.gamesession.v1.TickStatus getTickStatus() {
    return pauseRequested.get() && activeTicks.get() == 0
        ? net.firedevops.firemud.gamesession.v1.TickStatus.TICK_STATUS_PAUSED
        : net.firedevops.firemud.gamesession.v1.TickStatus.TICK_STATUS_RUNNING;
  }

  private Long findTenantId(Long sessionId) {
    return gameInstanceRepository.findById(sessionId).map(i -> i.getTenantId()).orElse(0L);
  }

  private String queueKey(Long tenantId, Long sessionId) {
    return "tick:queue:" + tenantId + ":" + sessionId;
  }

  private String lockKey(Long tenantId, Long sessionId) {
    return "tick:lock:" + tenantId + ":" + sessionId;
  }

  private String stateKey(Long tenantId, Long sessionId) {
    return "session:" + tenantId + ":" + sessionId;
  }

  private String pendingKey(Long tenantId, Long sessionId) {
    return "tick:pending:" + tenantId + ":" + sessionId;
  }
}
