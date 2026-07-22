package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.conflict.ConflictTracker;
import net.firedevops.firemud.gamesession.command.text.GameplayLoggingContext;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.entity.TickBatch;
import net.firedevops.firemud.gamesession.service.TickService;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
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
  private final TickQueueControlService tickQueueControlService;
  private final TickBatchExecutionService tickBatchExecutionService;
  private final TickStagingService tickStagingService;
  private final TickRuntimeProgressService tickRuntimeProgressService;

  @Value("${game.tick-duration-ms:1000}")
  private long tickDurationMs;

  @Value("${game.tick-max-commands:50}")
  private int tickMaxCommands;

  @Value("${game.tick-budget-ms:100}")
  private long tickBudgetMs;

  @Value("${game.solo-tick-budget-ms:500}")
  private long soloTickBudgetMs;

  @Value("${game.remote-followups.max-per-tick:16}")
  private int maxRemoteFollowupsPerTick;

  private Counter enqueueCounter;
  private Counter redisErrorCounter;
  private Counter lockContentionCounter;
  private Counter budgetExceededCounter;
  private Counter retryBackoffCounter;
  private Timer tickTimer;
  private Timer luaTimer;
  private RedisScript<Long> stageScript;
  private RedisScript<Long> commitScript;
  private RedisScript<Long> rollbackScript;
  private RedisTemplate<String, Object> fencedScriptRedisTemplate;
  private final StringRedisSerializer scriptArgsSerializer = new StringRedisSerializer();
  private final GenericToStringSerializer<Long> scriptResultSerializer =
      new GenericToStringSerializer<>(Long.class);

  private Long executeScriptWithRetry(RedisScript<Long> script, List<String> keys, Object... args) {
    int attempts = 0;
    while (true) {
      try {
        if (fencedScriptRedisTemplate == null) {
          return redisTemplate.execute(script, keys, args);
        }
        return fencedScriptRedisTemplate.execute(
            script, scriptArgsSerializer, scriptResultSerializer, keys, args);
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

  private long executeFencedScript(
      RedisScript<Long> script,
      TickQueueControlService.QueueLockLease lease,
      List<String> dataKeys,
      String operation,
      Object... operationArguments) {
    List<String> keys = new ArrayList<>(dataKeys);
    keys.add(lease.key());
    List<Object> arguments = new ArrayList<>(operationArguments.length + 1);
    arguments.add(lease.token());
    arguments.addAll(List.of(operationArguments));
    Long result = executeScriptWithRetry(script, keys, arguments.toArray());
    if (result == null || result < 0L || ("rollback".equals(operation) && result != 1L)) {
      lease.markLost();
      throw new TickQueueControlService.QueueUnavailableException(
          "Lost tick lock " + lease.key() + " during Redis " + operation);
    }
    return result;
  }

  private RedisTemplate<String, Object> createFencedScriptRedisTemplate() {
    if (redisTemplate.getConnectionFactory() == null || redisTemplate.getKeySerializer() == null) {
      return null;
    }
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(redisTemplate.getConnectionFactory());
    template.setKeySerializer(new FencedScriptKeySerializer(redisTemplate.getKeySerializer()));
    if (redisTemplate.getValueSerializer() != null) {
      template.setValueSerializer(redisTemplate.getValueSerializer());
    }
    template.afterPropertiesSet();
    return template;
  }

  private static final class FencedScriptKeySerializer implements RedisSerializer<Object> {
    private static final String TICK_LOCK_PREFIX = "gamesession:tick:lock:";
    private static final StringRedisSerializer RAW_STRING_SERIALIZER = new StringRedisSerializer();

    @SuppressWarnings("unchecked")
    private FencedScriptKeySerializer(RedisSerializer<?> queueKeySerializer) {
      this.queueKeySerializer = (RedisSerializer<Object>) queueKeySerializer;
    }

    private final RedisSerializer<Object> queueKeySerializer;

    @Override
    public byte[] serialize(Object value) {
      if (value instanceof String key && key.startsWith(TICK_LOCK_PREFIX)) {
        return RAW_STRING_SERIALIZER.serialize(key);
      }
      return queueKeySerializer.serialize(value);
    }

    @Override
    public Object deserialize(byte[] bytes) {
      return queueKeySerializer.deserialize(bytes);
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
    this.fencedScriptRedisTemplate = createFencedScriptRedisTemplate();
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
      Long tenantId,
      Long queueTargetId,
      String commandId,
      String command,
      boolean requiresSoloTick) {
    requireText(commandId, "command_id");
    requireText(command, "command");
    Long normalizedTenantId = tenantId != null ? tenantId : 0L;
    Long normalizedQueueTargetId = queueTargetId != null ? queueTargetId : 0L;
    try (GameplayLoggingContext ignored =
        GameplayLoggingContext.open(Long.toString(normalizedTenantId), null, null, null)) {
      tickQueueControlService.enqueueCommand(
          normalizedTenantId, normalizedQueueTargetId, commandId, command, requiresSoloTick);
      enqueueCounter.increment();
      logger.debug(
          "Queued command for {}:{} commandId={}",
          normalizedTenantId,
          normalizedQueueTargetId,
          commandId);
    }
  }

  @Override
  public long purgeQueuedAutomationCommandsForScriptPatch(
      Long tenantId,
      Long gameInstanceId,
      String regionId,
      String scriptPatchVersion,
      String reason) {
    return tickQueueControlService.purgeQueuedAutomationCommandsForScriptPatch(
        tenantId, gameInstanceId, regionId, scriptPatchVersion, reason, logger);
  }

  @Override
  public long purgeQueuedAutomationCommandsForPluginVersion(
      Long tenantId,
      Long gameInstanceId,
      String regionId,
      String pluginId,
      String pluginVersionId,
      String reason) {
    return tickQueueControlService.purgeQueuedAutomationCommandsForPluginVersion(
        tenantId, gameInstanceId, regionId, pluginId, pluginVersionId, reason, logger);
  }

  @Override
  @Timed(value = "gamesession.tick.process")
  public void processTick(Long tenantId, Long queueTargetId) {
    Long normalizedTenantId = tenantId != null ? tenantId : 0L;
    Long normalizedQueueTargetId = queueTargetId != null ? queueTargetId : 0L;
    try (GameplayLoggingContext ignored =
        GameplayLoggingContext.open(Long.toString(normalizedTenantId), null, null, null)) {
      TickQueueControlService.OwnershipSnapshot ownership =
          tickQueueControlService.observeOwnership(normalizedTenantId, normalizedQueueTargetId);
      tickRuntimeProgressService.observeRemoteFollowupBacklog(normalizedTenantId, ownership);
      if (tickQueueControlService.isPaused(normalizedQueueTargetId, ownership.paused())) {
        tickRuntimeProgressService.reconcilePausedRemoteFollowupResults(
            normalizedTenantId, ownership);
        logger.debug("Tick processing skipped while paused");
        return;
      }
      long start = System.nanoTime();
      Optional<TickQueueControlService.QueueLockLease> maybeLease =
          tickQueueControlService.tryAcquireTickLease(
              normalizedTenantId, normalizedQueueTargetId, "process tick", logger);
      if (maybeLease.isEmpty()) {
        lockContentionCounter.increment();
        conflictTracker.recordConflict(
            "session:" + normalizedTenantId + ":" + normalizedQueueTargetId);
        return;
      }
      try (TickQueueControlService.QueueLockLease lease = maybeLease.orElseThrow()) {
        tickQueueControlService.markTickStarted();
        String head = null;
        boolean solo = false;
        TickBatch activeBatch = null;
        List<TickQueuedCommandEnvelope> activeBatchEntries = List.of();
        boolean activeBatchDurablyDrained = false;
        boolean tickSucceeded = false;
        RuntimeRegionStatus tickProgressToPublish = null;
        try {
          tickBatchExecutionService.executeDurableEffects(
              normalizedTenantId, normalizedQueueTargetId);
          tickStagingService.drainRemoteFollowups(
              normalizedTenantId, normalizedQueueTargetId, ownership);
          lease.requireOwned();
          Long pending =
              redisTemplate
                  .opsForList()
                  .size(
                      tickQueueControlService.pendingKey(
                          normalizedTenantId, normalizedQueueTargetId));
          long depth = pending != null ? pending : 0L;
          tickRuntimeProgressService.updateRetryQueueDepth(
              normalizedTenantId, normalizedQueueTargetId, depth);
          if (pending != null && pending > 0) {
            List<TickQueuedCommandEnvelope> replayEntries =
                tickStagingService.readExecutablePendingEntries(
                    normalizedTenantId, normalizedQueueTargetId);
            if (!replayEntries.isEmpty()) {
              TickBatch replayBatch =
                  tickStagingService.resolveReplayBatch(
                      normalizedTenantId, normalizedQueueTargetId, replayEntries, ownership);
              tickBatchExecutionService.requireCurrentOwnership(replayBatch, false);
              lease.requireOwned();
              logger.info(
                  "Replaying {} executable pending commands for {}",
                  replayEntries.size(),
                  normalizedQueueTargetId);
              tickBatchExecutionService.markBatchDrained(replayBatch, replayEntries);
              lease.requireOwned();
              tickTimer.record(
                  () -> {
                    luaTimer.record(
                        () ->
                            executeFencedScript(
                                commitScript,
                                lease,
                                List.of(
                                    tickQueueControlService.pendingKey(
                                        normalizedTenantId, normalizedQueueTargetId)),
                                "commit"));
                  });
              tickBatchExecutionService.executeDurableEffects(
                  normalizedTenantId, normalizedQueueTargetId);
              lease.requireOwned();
            } else {
              executeFencedScript(
                  commitScript,
                  lease,
                  List.of(
                      tickQueueControlService.pendingKey(
                          normalizedTenantId, normalizedQueueTargetId)),
                  "commit");
            }
            awaitReplication();
          }
          Object headObj =
              redisTemplate
                  .opsForList()
                  .index(
                      tickQueueControlService.queueKey(normalizedTenantId, normalizedQueueTargetId),
                      0);
          head = headObj != null ? headObj.toString() : null;
          solo = head != null && head.startsWith("S|");
          int max = solo ? 1 : tickMaxCommands;
          lease.requireOwned();
          tickTimer.record(
              () -> {
                luaTimer.record(
                    () ->
                        executeFencedScript(
                            stageScript,
                            lease,
                            List.of(
                                tickQueueControlService.queueKey(
                                    normalizedTenantId, normalizedQueueTargetId),
                                tickQueueControlService.pendingKey(
                                    normalizedTenantId, normalizedQueueTargetId)),
                            "stage",
                            String.valueOf(max)));
              });
          activeBatchEntries =
              tickStagingService.readExecutablePendingEntries(
                  normalizedTenantId, normalizedQueueTargetId);
          if (!activeBatchEntries.isEmpty()) {
            activeBatch =
                tickStagingService.createBatch(
                    "FRESH_STAGE",
                    normalizedTenantId,
                    normalizedQueueTargetId,
                    solo,
                    ownership,
                    activeBatchEntries);
          }
          if (activeBatch != null) {
            tickBatchExecutionService.requireCurrentOwnership(activeBatch, false);
          }
          lease.requireOwned();
          if (activeBatch != null) {
            tickBatchExecutionService.markBatchDrained(activeBatch, activeBatchEntries);
            activeBatchDurablyDrained = true;
            lease.requireOwned();
            tickTimer.record(
                () -> {
                  luaTimer.record(
                      () ->
                          executeFencedScript(
                              commitScript,
                              lease,
                              List.of(
                                  tickQueueControlService.pendingKey(
                                      normalizedTenantId, normalizedQueueTargetId)),
                              "commit"));
                });
            tickBatchExecutionService.executeDurableEffects(
                normalizedTenantId, normalizedQueueTargetId);
            lease.requireOwned();
          } else {
            tickTimer.record(
                () -> {
                  luaTimer.record(
                      () ->
                          executeFencedScript(
                              commitScript,
                              lease,
                              List.of(
                                  tickQueueControlService.pendingKey(
                                      normalizedTenantId, normalizedQueueTargetId)),
                              "commit"));
                });
          }
          tickProgressToPublish =
              tickRuntimeProgressService.advanceRuntimeTickProgress(
                  normalizedTenantId, normalizedQueueTargetId, ownership);
          tickRuntimeProgressService.reconcileRemoteFollowupTimeouts(tickProgressToPublish);
          lease.requireOwned();
          tickSucceeded = true;
          awaitReplication();
        } catch (Exception ex) {
          logger.error("Tick processing failed", ex);
          conflictTracker.recordConflict(
              "session:" + normalizedTenantId + ":" + normalizedQueueTargetId);
          if (lease.isOwned()) {
            boolean rollbackSucceeded = false;
            try {
              luaTimer.record(
                  () ->
                      executeFencedScript(
                          rollbackScript,
                          lease,
                          List.of(
                              tickQueueControlService.pendingKey(
                                  normalizedTenantId, normalizedQueueTargetId),
                              tickQueueControlService.queueKey(
                                  normalizedTenantId, normalizedQueueTargetId)),
                          "rollback"));
              rollbackSucceeded = true;
            } catch (RuntimeException rollbackFailure) {
              logger.error(
                  "Skipped Redis rollback after queue lease loss or rollback failure "
                      + "tenantId={} gameInstanceId={}",
                  normalizedTenantId,
                  normalizedQueueTargetId,
                  rollbackFailure);
            }
            if (rollbackSucceeded && activeBatch != null && !activeBatchDurablyDrained) {
              tickBatchExecutionService.markBatchAbandoned(
                  activeBatch, activeBatchEntries, failureCode(ex), ex.getMessage());
            } else if (rollbackSucceeded && activeBatchDurablyDrained) {
              logger.warn(
                  "Durable tick drain committed before post-commit failure; preserving batch state "
                      + "for tenantId={} gameInstanceId={}",
                  normalizedTenantId,
                  normalizedQueueTargetId);
            }
            if (rollbackSucceeded) {
              awaitReplication();
            }
          } else {
            logger.error(
                "Skipped Redis rollback after queue lease loss tenantId={} gameInstanceId={}",
                normalizedTenantId,
                normalizedQueueTargetId);
          }
        } finally {
          long elapsed = (System.nanoTime() - start) / 1_000_000;
          long budget = solo ? soloTickBudgetMs : tickBudgetMs;
          if (elapsed > budget) {
            budgetExceededCounter.increment();
            logger.debug("Tick budget exceeded: {} ms", elapsed);
          }
          tickQueueControlService.markTickFinished();
          if (tickSucceeded) {
            tickRuntimeProgressService.publishRuntimeTickProgress(tickProgressToPublish);
            logger.debug(
                "Tick completed tenantId={} gameInstanceId={}",
                normalizedTenantId,
                normalizedQueueTargetId);
          }
        }
      }
    }
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }

  private void requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
  }

  private String failureCode(Exception ex) {
    return ex instanceof TickQueueControlService.StaleOwnershipException
        ? "STALE_EXECUTOR_FENCE"
        : "ROLLBACK_REQUEUED";
  }

  @Override
  @Timed(value = "gamesession.state.query")
  public String queryState(Long sessionId) {
    return tickQueueControlService.queryState(sessionId);
  }

  @Override
  public void pauseTicks(String reason) {
    tickQueueControlService.pauseTicks(reason, logger);
  }

  @Override
  public void resumeTicks(String reason) {
    tickQueueControlService.resumeTicks(reason, logger);
  }

  @Override
  public void pauseTicksForGameInstance(Long gameInstanceId, String reason) {
    tickQueueControlService.pauseTicksForGameInstance(gameInstanceId, reason, logger);
  }

  @Override
  public void resumeTicksForGameInstance(Long gameInstanceId, String reason) {
    tickQueueControlService.resumeTicksForGameInstance(gameInstanceId, reason, logger);
  }

  @Override
  public net.firedevops.firemud.gamesession.v1.TickStatus getTickStatus() {
    return tickQueueControlService.getTickStatus();
  }
}
