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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.conflict.ConflictTracker;
import net.firedevops.firemud.gamesession.command.text.GameplayLoggingContext;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.service.TickService;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

/** Default Redis-backed implementation of {@link TickService}. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected dependencies are kept internal")
@Service
@RequiredArgsConstructor
public class TickServiceImpl implements TickService {
  private static final Logger logger = LoggingUtil.getLogger(TickServiceImpl.class);

  private final RedisTemplate<String, Object> redisTemplate;
  private final MeterRegistry meterRegistry;
  private final ConflictTracker conflictTracker;
  private final GameInstanceRepository gameInstanceRepository;
  private final SessionContextService sessionContextService;

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
  private RedisScript<Long> unlockScript;
  private final StringRedisSerializer scriptArgsSerializer = new StringRedisSerializer();
  private final GenericToStringSerializer<Long> scriptResultSerializer =
      new GenericToStringSerializer<>(Long.class);
  private final AtomicBoolean pauseRequested = new AtomicBoolean(false);
  private final Set<Long> pausedGameInstances = ConcurrentHashMap.newKeySet();
  private final AtomicInteger activeTicks = new AtomicInteger();
  private final Map<String, Long> retryQueueDepthByTarget = new ConcurrentHashMap<>();
  private final AtomicLong retryQueueDepthTotal = new AtomicLong();
  private final AtomicInteger retryQueueTargetsWithPending = new AtomicInteger();

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
    meterRegistry.gauge("game_session_retry_queue_depth_total", retryQueueDepthTotal);
    meterRegistry.gauge(
        "game_session_retry_queue_targets_with_pending", retryQueueTargetsWithPending);
    ResourceScriptSource commitSrc =
        new ResourceScriptSource(new ClassPathResource("redis/tick_commit.lua"));
    ResourceScriptSource stageSrc =
        new ResourceScriptSource(new ClassPathResource("redis/tick_stage.lua"));
    ResourceScriptSource rollbackSrc =
        new ResourceScriptSource(new ClassPathResource("redis/tick_rollback.lua"));
    ResourceScriptSource unlockSrc =
        new ResourceScriptSource(new ClassPathResource("redis/tick_unlock_if_owned.lua"));
    this.stageScript = RedisScript.of(stageSrc.getResource(), Long.class);
    this.commitScript = RedisScript.of(commitSrc.getResource(), Long.class);
    this.rollbackScript = RedisScript.of(rollbackSrc.getResource(), Long.class);
    this.unlockScript = RedisScript.of(unlockSrc.getResource(), Long.class);
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
  public void enqueueCommand(
      Long tenantId, Long queueTargetId, String command, boolean requiresSoloTick) {
    Long normalizedTenantId = tenantId != null ? tenantId : 0L;
    Long normalizedQueueTargetId = queueTargetId != null ? queueTargetId : 0L;
    try (GameplayLoggingContext ignored =
        GameplayLoggingContext.open(Long.toString(normalizedTenantId), null, null, null)) {
      String value = (requiresSoloTick ? "S|" : "N|") + command;
      redisTemplate
          .opsForList()
          .rightPush(queueKey(normalizedTenantId, normalizedQueueTargetId), value);
      enqueueCounter.increment();
      logger.debug("Queued command for {}:{}", normalizedTenantId, normalizedQueueTargetId);
    }
  }

  @Override
  @Timed(value = "gamesession.tick.process")
  public void processTick(Long tenantId, Long queueTargetId) {
    Long normalizedTenantId = tenantId != null ? tenantId : 0L;
    Long normalizedQueueTargetId = queueTargetId != null ? queueTargetId : 0L;
    try (GameplayLoggingContext ignored =
        GameplayLoggingContext.open(Long.toString(normalizedTenantId), null, null, null)) {
      if (pauseRequested.get() || pausedGameInstances.contains(normalizedQueueTargetId)) {
        logger.debug("Tick processing skipped while paused");
        return;
      }
      long start = System.nanoTime();
      String lockKey = lockKey(normalizedTenantId, normalizedQueueTargetId);
      String lockToken = UUID.randomUUID().toString();
      Boolean acquired =
          redisTemplate
              .opsForValue()
              .setIfAbsent(lockKey, lockToken, Duration.ofMillis(tickDurationMs));
      if (Boolean.FALSE.equals(acquired)) {
        lockContentionCounter.increment();
        conflictTracker.recordConflict(
            "session:" + normalizedTenantId + ":" + normalizedQueueTargetId);
        logger.debug("Could not acquire tick lock {}", lockKey);
        return;
      }
      activeTicks.incrementAndGet();
      String head = null;
      boolean solo = false;
      try {
        Long pending =
            redisTemplate
                .opsForList()
                .size(pendingKey(normalizedTenantId, normalizedQueueTargetId));
        long depth = pending != null ? pending : 0L;
        updateRetryQueueDepth(normalizedTenantId, normalizedQueueTargetId, depth);
        if (pending != null && pending > 0) {
          logger.info("Replaying {} pending commands for {}", pending, normalizedQueueTargetId);
          tickTimer.record(
              () -> {
                luaTimer.record(
                    () ->
                        executeScriptWithRetry(
                            commitScript,
                            List.of(pendingKey(normalizedTenantId, normalizedQueueTargetId))));
              });
          awaitReplication();
        }
        Object headObj =
            redisTemplate
                .opsForList()
                .index(queueKey(normalizedTenantId, normalizedQueueTargetId), 0);
        head = headObj != null ? headObj.toString() : null;
        solo = head != null && head.startsWith("S|");
        int max = solo ? 1 : tickMaxCommands;
        tickTimer.record(
            () -> {
              luaTimer.record(
                  () ->
                      redisTemplate.execute(
                          stageScript,
                          scriptArgsSerializer,
                          scriptResultSerializer,
                          List.of(
                              queueKey(normalizedTenantId, normalizedQueueTargetId),
                              pendingKey(normalizedTenantId, normalizedQueueTargetId)),
                          String.valueOf(max)));
            });
        tickTimer.record(
            () -> {
              luaTimer.record(
                  () ->
                      executeScriptWithRetry(
                          commitScript,
                          List.of(pendingKey(normalizedTenantId, normalizedQueueTargetId))));
            });
        awaitReplication();
      } catch (Exception ex) {
        logger.error("Tick processing failed, rolling back", ex);
        conflictTracker.recordConflict(
            "session:" + normalizedTenantId + ":" + normalizedQueueTargetId);
        luaTimer.record(
            () ->
                executeScriptWithRetry(
                    rollbackScript,
                    List.of(
                        pendingKey(normalizedTenantId, normalizedQueueTargetId),
                        queueKey(normalizedTenantId, normalizedQueueTargetId))));
        requeuedActionCounter.increment();
        awaitReplication();
      } finally {
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        long budget = solo ? soloTickBudgetMs : tickBudgetMs;
        if (elapsed > budget) {
          budgetExceededCounter.increment();
          logger.debug("Tick budget exceeded: {} ms", elapsed);
        }
        luaTimer.record(() -> executeScriptWithRetry(unlockScript, List.of(lockKey), lockToken));
        activeTicks.decrementAndGet();
      }
    }
  }

  private void updateRetryQueueDepth(Long tenantId, Long queueTargetId, long depth) {
    String gaugeKey = tenantId + ":" + queueTargetId;
    retryQueueDepthByTarget.compute(
        gaugeKey,
        (ignored, previousDepth) -> {
          long prior = previousDepth != null ? previousDepth : 0L;
          retryQueueDepthTotal.addAndGet(depth - prior);
          if (prior == 0L && depth > 0L) {
            retryQueueTargetsWithPending.incrementAndGet();
          } else if (prior > 0L && depth == 0L) {
            retryQueueTargetsWithPending.decrementAndGet();
          }
          return depth > 0L ? depth : null;
        });
  }

  @Override
  @Timed(value = "gamesession.state.query")
  public String queryState(Long sessionId) {
    Optional<SessionContext> maybeContext = sessionContextService.findBySessionId(sessionId);
    Long tenantId =
        maybeContext.map(SessionContext::tenantId).orElseGet(() -> findTenantId(sessionId));
    try (GameplayLoggingContext ignored =
        maybeContext
            .<GameplayLoggingContext>map(GameplayLoggingContext::from)
            .orElseGet(
                () ->
                    GameplayLoggingContext.open(
                        tenantId != null ? Long.toString(tenantId) : null, null, null, null))) {
      Object state = redisTemplate.opsForValue().get(stateKey(tenantId, sessionId));
      return state != null ? state.toString() : "{}";
    }
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
    Optional<SessionContext> context = sessionContextService.findBySessionId(sessionId);
    if (context.isPresent()) {
      return context.get().tenantId();
    }
    return gameInstanceRepository.findById(sessionId).map(i -> i.getTenantId()).orElse(0L);
  }

  private String queueKey(Long tenantId, Long sessionId) {
    return "gamesession:tick:queue:" + tenantId + ":" + sessionId;
  }

  private String lockKey(Long tenantId, Long sessionId) {
    return "gamesession:tick:lock:" + tenantId + ":" + sessionId;
  }

  private String stateKey(Long tenantId, Long sessionId) {
    return "session:" + tenantId + ":" + sessionId;
  }

  private String pendingKey(Long tenantId, Long sessionId) {
    return "gamesession:tick:pending:" + tenantId + ":" + sessionId;
  }
}
