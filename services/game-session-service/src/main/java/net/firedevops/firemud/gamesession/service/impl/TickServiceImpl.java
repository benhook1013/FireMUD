package net.firedevops.firemud.gamesession.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
import net.firedevops.firemud.automationscripting.v1.ObserveRuntimeTickProgressRequest;
import net.firedevops.firemud.automationscripting.v1.ObserveRuntimeTickProgressResponse;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.conflict.ConflictTracker;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.gamesession.client.AutomationScriptingClient;
import net.firedevops.firemud.gamesession.command.text.GameplayLoggingContext;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.entity.TickBatch;
import net.firedevops.firemud.gamesession.entity.TickEffect;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.RemoteFollowupRepository;
import net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository;
import net.firedevops.firemud.gamesession.repository.TickBatchRepository;
import net.firedevops.firemud.gamesession.repository.TickEffectRepository;
import net.firedevops.firemud.gamesession.service.DurableGameplayCommandExecutionService;
import net.firedevops.firemud.gamesession.service.DurableRemoteFollowupExecutionService;
import net.firedevops.firemud.gamesession.service.RemoteFollowupDrainService;
import net.firedevops.firemud.gamesession.service.RemoteFollowupRuntimeService;
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
  private static final String PURGED_FAILURE_CODE = "ROLLBACK_PURGED";

  private final RedisTemplate<String, Object> redisTemplate;
  private final MeterRegistry meterRegistry;
  private final ConflictTracker conflictTracker;
  private final GameInstanceRepository gameInstanceRepository;
  private final GameplayCommandRepository gameplayCommandRepository;
  private final RuntimeIdentity runtimeIdentity;
  private final RuntimeRegionStatusRepository runtimeRegionStatusRepository;
  private final RemoteFollowupRepository remoteFollowupRepository;
  private final TickBatchRepository tickBatchRepository;
  private final TickEffectRepository tickEffectRepository;
  private final SessionContextService sessionContextService;
  private final DurableGameplayCommandExecutionService durableGameplayCommandExecutionService;
  private final DurableRemoteFollowupExecutionService durableRemoteFollowupExecutionService;
  private final RemoteFollowupDrainService remoteFollowupDrainService;
  private final RemoteFollowupRuntimeService remoteFollowupRuntimeService;
  private final AutomationScriptingClient automationScriptingClient;
  private final ObjectMapper objectMapper = new ObjectMapper();

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
  private Counter manifestMismatchCounter;
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
  private final Map<String, AtomicLong> remoteFollowupDueGaugeByTarget = new ConcurrentHashMap<>();
  private final Map<String, AtomicLong> remoteFollowupDrainLagGaugeByTarget =
      new ConcurrentHashMap<>();
  private final Map<String, Long> remoteFollowupDuePresenceByTarget = new ConcurrentHashMap<>();
  private final AtomicInteger remoteFollowupTargetsWithDue = new AtomicInteger();

  private record OwnershipSnapshot(
      String regionId,
      long regionEpoch,
      String executorFence,
      boolean paused,
      long lastCommittedTickId) {}

  private record QueuedCommandEnvelope(
      boolean requiresSoloTick, String commandId, String command) {}

  private record CommandSelection(
      QueuedCommandEnvelope entry,
      GameplayCommand command,
      long sourceOrdinal,
      String effectKey,
      String commandDigest) {}

  private static final class StaleOwnershipException extends RuntimeException {
    private StaleOwnershipException(String message) {
      super(message);
    }
  }

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
    this.retryBackoffCounter = meterRegistry.counter("tick_retry_backoff_count_total");
    this.manifestMismatchCounter = meterRegistry.counter("tick_manifest_mismatch_total");
    meterRegistry.gauge("game_session_retry_queue_depth_total", retryQueueDepthTotal);
    meterRegistry.gauge(
        "game_session_retry_queue_targets_with_pending", retryQueueTargetsWithPending);
    meterRegistry.gauge(
        "game_session_remote_followups_targets_with_due", remoteFollowupTargetsWithDue);
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
      String value = queuePayload(requiresSoloTick, commandId, command);
      redisTemplate
          .opsForList()
          .rightPush(queueKey(normalizedTenantId, normalizedQueueTargetId), value);
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
    requirePositive(tenantId, "tenant_id");
    requirePositive(gameInstanceId, "game_instance_id");
    requireText(scriptPatchVersion, "script_patch_version");
    List<GameplayCommand> commands =
        gameplayCommandRepository.findQueuedAutomationCommandsForScriptPatch(
            tenantId, gameInstanceId, normalize(regionId), scriptPatchVersion);
    return purgeQueuedCommands(tenantId, gameInstanceId, commands, reason);
  }

  @Override
  public long purgeQueuedAutomationCommandsForPluginVersion(
      Long tenantId,
      Long gameInstanceId,
      String regionId,
      String pluginId,
      String pluginVersionId,
      String reason) {
    requirePositive(tenantId, "tenant_id");
    requirePositive(gameInstanceId, "game_instance_id");
    requireText(pluginId, "plugin_id");
    requireText(pluginVersionId, "plugin_version_id");
    List<GameplayCommand> commands =
        gameplayCommandRepository.findQueuedAutomationCommandsForPluginVersion(
            tenantId, gameInstanceId, normalize(regionId), pluginId, pluginVersionId);
    return purgeQueuedCommands(tenantId, gameInstanceId, commands, reason);
  }

  private long purgeQueuedCommands(
      Long tenantId, Long gameInstanceId, List<GameplayCommand> commands, String reason) {
    if (commands.isEmpty()) {
      return 0L;
    }
    String key = queueKey(tenantId, gameInstanceId);
    Instant now = Instant.now();
    for (GameplayCommand command : commands) {
      redisTemplate
          .opsForList()
          .remove(
              key,
              0,
              queuePayload(
                  command.isRequiresSoloTick(), command.getCommandId(), command.getCommandText()));
      command.setExecutionOutcome("PURGED");
      command.setGameplayResult("NOT_APPLIED");
      command.setCompletedAt(now);
      command.setLastAttemptAt(now);
      command.setFailureCode(PURGED_FAILURE_CODE);
      command.setFailureMessage(truncate(reason, 500));
    }
    gameplayCommandRepository.saveAll(commands);
    logger.info(
        "Purged {} queued automation commands tenantId={} gameInstanceId={}",
        commands.size(),
        tenantId,
        gameInstanceId);
    return commands.size();
  }

  @Override
  @Timed(value = "gamesession.tick.process")
  public void processTick(Long tenantId, Long queueTargetId) {
    Long normalizedTenantId = tenantId != null ? tenantId : 0L;
    Long normalizedQueueTargetId = queueTargetId != null ? queueTargetId : 0L;
    try (GameplayLoggingContext ignored =
        GameplayLoggingContext.open(Long.toString(normalizedTenantId), null, null, null)) {
      OwnershipSnapshot ownership = observeOwnership(normalizedTenantId, normalizedQueueTargetId);
      observeRemoteFollowupBacklog(normalizedTenantId, ownership);
      if (pauseRequested.get()
          || pausedGameInstances.contains(normalizedQueueTargetId)
          || ownership.paused()) {
        reconcilePausedRemoteFollowupResults(normalizedTenantId, ownership);
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
      TickBatch activeBatch = null;
      List<QueuedCommandEnvelope> activeBatchEntries = List.of();
      boolean tickSucceeded = false;
      RuntimeRegionStatus tickProgressToPublish = null;
      try {
        executeDurableEffects(normalizedTenantId, normalizedQueueTargetId);
        drainRemoteFollowups(normalizedTenantId, normalizedQueueTargetId, ownership);
        Long pending =
            redisTemplate
                .opsForList()
                .size(pendingKey(normalizedTenantId, normalizedQueueTargetId));
        long depth = pending != null ? pending : 0L;
        updateRetryQueueDepth(normalizedTenantId, normalizedQueueTargetId, depth);
        if (pending != null && pending > 0) {
          List<QueuedCommandEnvelope> replayEntries =
              readPendingEntries(normalizedTenantId, normalizedQueueTargetId);
          TickBatch replayBatch =
              resolveReplayBatch(
                  normalizedTenantId, normalizedQueueTargetId, replayEntries, ownership);
          requireCurrentOwnership(replayBatch, false);
          logger.info("Replaying {} pending commands for {}", pending, normalizedQueueTargetId);
          tickTimer.record(
              () -> {
                luaTimer.record(
                    () ->
                        executeScriptWithRetry(
                            commitScript,
                            List.of(pendingKey(normalizedTenantId, normalizedQueueTargetId))));
              });
          markBatchDrained(replayBatch, replayEntries);
          executeDurableEffects(normalizedTenantId, normalizedQueueTargetId);
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
        activeBatchEntries = readPendingEntries(normalizedTenantId, normalizedQueueTargetId);
        if (!activeBatchEntries.isEmpty()) {
          activeBatch =
              createBatch(
                  "FRESH_STAGE",
                  normalizedTenantId,
                  normalizedQueueTargetId,
                  solo,
                  ownership,
                  activeBatchEntries);
        }
        if (activeBatch != null) {
          requireCurrentOwnership(activeBatch, false);
        }
        tickTimer.record(
            () -> {
              luaTimer.record(
                  () ->
                      executeScriptWithRetry(
                          commitScript,
                          List.of(pendingKey(normalizedTenantId, normalizedQueueTargetId))));
            });
        if (activeBatch != null) {
          markBatchDrained(activeBatch, activeBatchEntries);
          executeDurableEffects(normalizedTenantId, normalizedQueueTargetId);
        }
        tickProgressToPublish =
            advanceRuntimeTickProgress(normalizedTenantId, normalizedQueueTargetId, ownership);
        reconcileRemoteFollowupTimeouts(tickProgressToPublish);
        tickSucceeded = true;
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
        if (activeBatch != null) {
          markBatchAbandoned(activeBatch, activeBatchEntries, failureCode(ex), ex.getMessage());
        }
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
        if (tickSucceeded) {
          publishRuntimeTickProgress(tickProgressToPublish);
          logger.debug(
              "Tick completed tenantId={} gameInstanceId={}",
              normalizedTenantId,
              normalizedQueueTargetId);
        }
      }
    }
  }

  private RuntimeRegionStatus advanceRuntimeTickProgress(
      Long tenantId, Long gameInstanceId, OwnershipSnapshot ownership) {
    RuntimeRegionStatus status =
        requireRuntimeOwnership(tenantId, gameInstanceId, ownership.regionId());
    if (status.getRegionEpoch() != ownership.regionEpoch()
        || !status.getExecutorFence().equals(ownership.executorFence())
        || status.isPaused()) {
      throw new StaleOwnershipException(
          "Cannot advance stale runtime tick progress for tenantId=%d gameInstanceId=%d"
              .formatted(tenantId, gameInstanceId));
    }
    status.setLastCommittedTickId(status.getLastCommittedTickId() + 1L);
    status.setUpdatedAt(Instant.now());
    return runtimeRegionStatusRepository.save(status);
  }

  private void reconcileRemoteFollowupTimeouts(RuntimeRegionStatus status) {
    if (status == null) {
      return;
    }
    int resultReconciled =
        remoteFollowupRuntimeService.reconcileResults(
            status.getTenantId(), status.getRegionId(), status.getRegionEpoch());
    if (resultReconciled > 0) {
      logger.info(
          "Reconciled remote followup results tenantId={} regionId={} regionEpoch={} tickId={} count={}",
          status.getTenantId(),
          status.getRegionId(),
          status.getRegionEpoch(),
          status.getLastCommittedTickId(),
          resultReconciled);
    }
    int reconciled =
        remoteFollowupRuntimeService.reconcileTimeouts(
            status.getTenantId(),
            status.getRegionId(),
            status.getRegionEpoch(),
            status.getLastCommittedTickId());
    if (reconciled > 0) {
      logger.info(
          "Reconciled remote followup timeouts tenantId={} regionId={} regionEpoch={} tickId={} count={}",
          status.getTenantId(),
          status.getRegionId(),
          status.getRegionEpoch(),
          status.getLastCommittedTickId(),
          reconciled);
    }
  }

  private void reconcilePausedRemoteFollowupResults(Long tenantId, OwnershipSnapshot ownership) {
    if (ownership == null || ownership.regionId() == null || ownership.regionId().isBlank()) {
      return;
    }
    int reconciled =
        remoteFollowupRuntimeService.reconcileResults(
            tenantId, ownership.regionId(), ownership.regionEpoch());
    if (reconciled > 0) {
      logger.info(
          "Reconciled remote followup results while paused tenantId={} regionId={} regionEpoch={} count={}",
          tenantId,
          ownership.regionId(),
          ownership.regionEpoch(),
          reconciled);
    }
  }

  private void publishRuntimeTickProgress(RuntimeRegionStatus status) {
    if (status == null) {
      return;
    }
    ObserveRuntimeTickProgressResponse response =
        automationScriptingClient.observeRuntimeTickProgress(
            ObserveRuntimeTickProgressRequest.newBuilder()
                .setTenantId(Long.toString(status.getTenantId()))
                .setGameInstanceId(Long.toString(status.getGameInstanceId()))
                .setRegionId(status.getRegionId())
                .setRegionEpoch(status.getRegionEpoch())
                .setTickId(status.getLastCommittedTickId())
                .setObservedAtMs(status.getUpdatedAt().toEpochMilli())
                .build());
    if (response.hasError()) {
      logger.warn(
          "Automation runtime tick progress was not observed tenantId={} gameInstanceId={} tickId={} code={} message={}",
          status.getTenantId(),
          status.getGameInstanceId(),
          status.getLastCommittedTickId(),
          response.getError().getCode(),
          response.getError().getMessage());
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

  private void observeRemoteFollowupBacklog(Long tenantId, OwnershipSnapshot ownership) {
    long dueCount =
        remoteFollowupRepository.countByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqual(
            tenantId,
            ownership.regionId(),
            RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED,
            ownership.lastCommittedTickId() + 1L);
    long drainLagMs =
        remoteFollowupRepository
            .findFirstByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqualOrderByDueTickIdAsc(
                tenantId,
                ownership.regionId(),
                RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED,
                ownership.lastCommittedTickId() + 1L)
            .map(
                followup ->
                    Math.max(
                        0L,
                        (ownership.lastCommittedTickId() + 1L - followup.getDueTickId())
                            * tickDurationMs))
            .orElse(0L);
    String gaugeKey = tenantId + ":" + ownership.regionId();
    remoteFollowupDueGaugeByTarget
        .computeIfAbsent(
            gaugeKey,
            ignored ->
                meterRegistry.gauge(
                    "remote_followups_due_total",
                    Tags.of("tenantId", tenantId.toString(), "regionId", ownership.regionId()),
                    new AtomicLong()))
        .set(dueCount);
    remoteFollowupDrainLagGaugeByTarget
        .computeIfAbsent(
            gaugeKey,
            ignored ->
                meterRegistry.gauge(
                    "remote_followups_drain_lag_ms",
                    Tags.of("tenantId", tenantId.toString(), "regionId", ownership.regionId()),
                    new AtomicLong()))
        .set(drainLagMs);
    remoteFollowupDuePresenceByTarget.compute(
        gaugeKey,
        (ignored, previousDepth) -> {
          long prior = previousDepth != null ? previousDepth : 0L;
          if (prior == 0L && dueCount > 0L) {
            remoteFollowupTargetsWithDue.incrementAndGet();
            return 1L;
          } else if (prior > 0L && dueCount == 0L) {
            remoteFollowupTargetsWithDue.decrementAndGet();
            return 0L;
          }
          return prior;
        });
    if (dueCount > maxRemoteFollowupsPerTick) {
      meterRegistry
          .counter(
              "remote_followups_backlog_over_budget_total",
              "tenantId",
              tenantId.toString(),
              "regionId",
              ownership.regionId())
          .increment();
    }
  }

  private void drainRemoteFollowups(
      Long tenantId, Long gameInstanceId, OwnershipSnapshot ownership) {
    String tickBatchId = "tb-" + UUID.randomUUID();
    RemoteFollowupDrainService.ClaimOutcome claimOutcome =
        remoteFollowupDrainService.claimDueFollowups(
            tenantId,
            ownership.regionId(),
            ownership.lastCommittedTickId() + 1L,
            tickBatchId,
            maxRemoteFollowupsPerTick);
    if (claimOutcome.claimedCount() <= 0) {
      return;
    }
    List<net.firedevops.firemud.gamesession.entity.RemoteFollowup> claimedFollowups =
        remoteFollowupRepository.findByClaimedTickBatchIdOrderByIdAsc(tickBatchId);
    TickBatch batch = null;
    try {
      batch =
          createRemoteFollowupBatch(
              tickBatchId, tenantId, gameInstanceId, ownership, claimedFollowups);
      requireCurrentOwnership(batch, false);
      markRemoteFollowupBatchDrained(batch);
      executeDurableEffects(tenantId, gameInstanceId);
    } catch (Exception ex) {
      if (batch != null) {
        markRemoteFollowupBatchAbandoned(batch, failureCode(ex), ex.getMessage());
      } else {
        remoteFollowupDrainService.releaseClaimedFollowups(
            tickBatchId, failureCode(ex), ex.getMessage());
      }
      throw ex;
    }
  }

  private List<QueuedCommandEnvelope> readPendingEntries(Long tenantId, Long queueTargetId) {
    List<Object> rawEntries =
        redisTemplate.opsForList().range(pendingKey(tenantId, queueTargetId), 0, -1);
    if (rawEntries == null || rawEntries.isEmpty()) {
      return List.of();
    }
    List<QueuedCommandEnvelope> entries = new ArrayList<>(rawEntries.size());
    for (Object rawEntry : rawEntries) {
      if (rawEntry == null) {
        continue;
      }
      entries.add(parseQueuedCommand(rawEntry.toString()));
    }
    return List.copyOf(entries);
  }

  private QueuedCommandEnvelope parseQueuedCommand(String payload) {
    String[] parts = payload.split("\\|", 3);
    if (parts.length < 3) {
      return new QueuedCommandEnvelope(false, null, payload);
    }
    boolean requiresSoloTick = "S".equals(parts[0]);
    String commandId = "-".equals(parts[1]) || parts[1].isBlank() ? null : parts[1];
    return new QueuedCommandEnvelope(requiresSoloTick, commandId, parts[2]);
  }

  private TickBatch resolveReplayBatch(
      Long tenantId,
      Long gameInstanceId,
      List<QueuedCommandEnvelope> replayEntries,
      OwnershipSnapshot ownership) {
    Optional<TickBatch> existing =
        tickBatchRepository.findFirstByTenantIdAndGameInstanceIdAndStatusOrderByStagedAtDesc(
            tenantId, gameInstanceId, "STAGED");
    if (existing.isEmpty()) {
      return createBatch(
          "PENDING_REPLAY", tenantId, gameInstanceId, false, ownership, replayEntries);
    }
    TickBatch batch = existing.orElseThrow();
    String replayManifest =
        selectedWorkManifest(ownership.regionId(), commandSelections(replayEntries));
    String replayDigest = shortHash(replayManifest);
    if (replayDigest.equals(batch.getSelectedWorkManifestDigest())) {
      return batch;
    }
    manifestMismatchCounter.increment();
    logger.warn(
        "Replay manifest mismatch for staged batch tickBatchId={} tenantId={} gameInstanceId={} expectedDigest={} actualDigest={}",
        batch.getTickBatchId(),
        tenantId,
        gameInstanceId,
        batch.getSelectedWorkManifestDigest(),
        replayDigest);
    List<QueuedCommandEnvelope> sealedEntries = loadSealedReplayEntries(batch);
    restorePendingProjection(tenantId, gameInstanceId, replayEntries, sealedEntries);
    markBatchManifestMismatch(batch, sealedEntries, replayDigest);
    return createBatch("PENDING_REPLAY", tenantId, gameInstanceId, false, ownership, sealedEntries);
  }

  private TickBatch createBatch(
      String batchSource,
      Long tenantId,
      Long gameInstanceId,
      boolean requiresSoloTick,
      OwnershipSnapshot ownership,
      List<QueuedCommandEnvelope> entries) {
    Instant now = Instant.now();
    requireDurableCommandIdentifiers(entries);
    List<CommandSelection> selections = commandSelections(entries);
    TickBatch batch = new TickBatch();
    batch.setTickBatchId("tb-" + UUID.randomUUID());
    batch.setTenantId(tenantId);
    batch.setGameInstanceId(gameInstanceId);
    batch.setRegionId(ownership.regionId());
    batch.setRegionEpoch(ownership.regionEpoch());
    batch.setExecutorFence(ownership.executorFence());
    batch.setBatchSource(batchSource);
    batch.setStatus("STAGED");
    batch.setRequiresSoloTick(requiresSoloTick);
    batch.setCommandCount(entries.size());
    batch.setExpectedEffectCount(entries.size());
    String selectedWorkManifest = selectedWorkManifest(ownership.regionId(), selections);
    batch.setSelectedWorkManifestJson(selectedWorkManifest);
    batch.setSelectedWorkManifestDigest(shortHash(selectedWorkManifest));
    batch.setStagedAt(now);
    TickBatch savedBatch = tickBatchRepository.save(batch);
    persistEffects(savedBatch, gameInstanceId, now, selections);
    bumpGameplayCommandAttempts(entries, now);
    logger.info(
        "Staged durable tick batch tickBatchId={} tenantId={} gameInstanceId={} source={} commandCount={}",
        savedBatch.getTickBatchId(),
        tenantId,
        gameInstanceId,
        batchSource,
        entries.size());
    return savedBatch;
  }

  private void markBatchManifestMismatch(
      TickBatch batch, List<QueuedCommandEnvelope> entries, String actualManifestDigest) {
    Instant now = Instant.now();
    String failureMessage =
        truncate(
            "Pending replay digest %s no longer matches sealed batch manifest %s"
                .formatted(actualManifestDigest, batch.getSelectedWorkManifestDigest()),
            500);
    batch.setStatus("ABANDONED");
    batch.setCompletedAt(now);
    batch.setFailureCode("MANIFEST_MISMATCH");
    batch.setFailureMessage(failureMessage);
    tickBatchRepository.save(batch);
    updateEffectStatuses(
        batch.getTickBatchId(), "ABANDONED", now, "MANIFEST_MISMATCH", failureMessage);
    updateGameplayCommands(
        entries, "RETRY_QUEUED", "PENDING", now, "MANIFEST_MISMATCH", failureMessage, false);
    recordRequeuedActions(entries);
  }

  private List<QueuedCommandEnvelope> loadSealedReplayEntries(TickBatch batch) {
    try {
      JsonNode root = objectMapper.readTree(batch.getSelectedWorkManifestJson());
      JsonNode items = root.path("items");
      if (!items.isArray() || items.isEmpty()) {
        throw new IllegalStateException(
            "Sealed replay manifest is missing item entries for tickBatchId="
                + batch.getTickBatchId());
      }
      List<String> commandIds = new ArrayList<>();
      List<Boolean> requiresSoloTicks = new ArrayList<>();
      for (JsonNode item : items) {
        String commandId = item.path("commandId").asText("").trim();
        if (commandId.isBlank()) {
          throw new IllegalStateException(
              "Sealed replay manifest requires commandId for tickBatchId="
                  + batch.getTickBatchId());
        }
        commandIds.add(commandId);
        requiresSoloTicks.add(item.path("requiresSoloTick").asBoolean(false));
      }
      Map<String, GameplayCommand> commandsById =
          gameplayCommandRepository.findByCommandIdIn(commandIds).stream()
              .collect(
                  java.util.stream.Collectors.toMap(GameplayCommand::getCommandId, cmd -> cmd));
      List<QueuedCommandEnvelope> entries = new ArrayList<>(commandIds.size());
      for (int index = 0; index < commandIds.size(); index++) {
        String commandId = commandIds.get(index);
        GameplayCommand command = commandsById.get(commandId);
        if (command == null) {
          throw new IllegalStateException(
              "Sealed replay manifest references missing gameplay command "
                  + commandId
                  + " for tickBatchId="
                  + batch.getTickBatchId());
        }
        entries.add(
            new QueuedCommandEnvelope(
                requiresSoloTicks.get(index), commandId, command.getCommandText()));
      }
      return List.copyOf(entries);
    } catch (java.io.IOException ex) {
      throw new IllegalStateException(
          "Failed to restore sealed replay manifest for tickBatchId=" + batch.getTickBatchId(), ex);
    }
  }

  private void restorePendingProjection(
      Long tenantId,
      Long gameInstanceId,
      List<QueuedCommandEnvelope> pendingEntries,
      List<QueuedCommandEnvelope> sealedEntries) {
    Instant now = Instant.now();
    Set<String> sealedCommandIds =
        sealedEntries.stream()
            .map(QueuedCommandEnvelope::commandId)
            .collect(java.util.stream.Collectors.toSet());
    List<QueuedCommandEnvelope> redisOnlyEntries =
        pendingEntries.stream()
            .filter(
                entry ->
                    entry.commandId() != null
                        && !entry.commandId().isBlank()
                        && !sealedCommandIds.contains(entry.commandId()))
            .toList();
    if (!redisOnlyEntries.isEmpty()) {
      requeueEntries(tenantId, gameInstanceId, redisOnlyEntries);
      updateGameplayCommands(
          redisOnlyEntries,
          "RETRY_QUEUED",
          "PENDING",
          now,
          "MANIFEST_MISMATCH",
          "Redis pending entry was returned to queue because sealed replay manifest won",
          false);
      recordRequeuedActions(redisOnlyEntries);
    }
    String pendingKey = pendingKey(tenantId, gameInstanceId);
    redisTemplate.delete(pendingKey);
    for (QueuedCommandEnvelope entry : sealedEntries) {
      redisTemplate
          .opsForList()
          .rightPush(
              pendingKey,
              queuePayload(entry.requiresSoloTick(), entry.commandId(), entry.command()));
    }
  }

  private void persistEffects(
      TickBatch batch, Long gameInstanceId, Instant stagedAt, List<CommandSelection> selections) {
    if (selections.isEmpty()) {
      return;
    }
    List<TickEffect> effects = new ArrayList<>(selections.size());
    for (CommandSelection selection : selections) {
      QueuedCommandEnvelope entry = selection.entry();
      TickEffect effect = new TickEffect();
      effect.setEffectId(effectId(batch.getTickBatchId(), selection.effectKey()));
      effect.setTickBatchId(batch.getTickBatchId());
      effect.setCommandId(entry.commandId());
      effect.setEffectKey(selection.effectKey());
      effect.setEffectType("GAMEPLAY_COMMAND");
      effect.setTargetAggregate(effectTargetAggregate(gameInstanceId, selection.command()));
      effect.setStatus("STAGED");
      effect.setStagedAt(stagedAt);
      effects.add(effect);
    }
    tickEffectRepository.saveAll(effects);
  }

  private TickBatch createRemoteFollowupBatch(
      String tickBatchId,
      Long tenantId,
      Long gameInstanceId,
      OwnershipSnapshot ownership,
      List<net.firedevops.firemud.gamesession.entity.RemoteFollowup> followups) {
    Instant now = Instant.now();
    TickBatch batch = new TickBatch();
    batch.setTickBatchId(tickBatchId);
    batch.setTenantId(tenantId);
    batch.setGameInstanceId(gameInstanceId);
    batch.setRegionId(ownership.regionId());
    batch.setRegionEpoch(ownership.regionEpoch());
    batch.setExecutorFence(ownership.executorFence());
    batch.setBatchSource("REMOTE_FOLLOWUP_DRAIN");
    batch.setStatus("STAGED");
    batch.setRequiresSoloTick(false);
    batch.setCommandCount(0);
    batch.setExpectedEffectCount(followups.size());
    String selectedWorkManifest = selectedRemoteFollowupManifest(ownership.regionId(), followups);
    batch.setSelectedWorkManifestJson(selectedWorkManifest);
    batch.setSelectedWorkManifestDigest(shortHash(selectedWorkManifest));
    batch.setStagedAt(now);
    TickBatch savedBatch = tickBatchRepository.save(batch);
    persistRemoteFollowupEffects(savedBatch, now, followups);
    logger.info(
        "Staged durable remote followup batch tickBatchId={} tenantId={} gameInstanceId={} followupCount={}",
        savedBatch.getTickBatchId(),
        tenantId,
        gameInstanceId,
        followups.size());
    return savedBatch;
  }

  private void persistRemoteFollowupEffects(
      TickBatch batch,
      Instant stagedAt,
      List<net.firedevops.firemud.gamesession.entity.RemoteFollowup> followups) {
    if (followups.isEmpty()) {
      return;
    }
    List<TickEffect> effects = new ArrayList<>(followups.size());
    for (net.firedevops.firemud.gamesession.entity.RemoteFollowup followup : followups) {
      TickEffect effect = new TickEffect();
      effect.setEffectId(effectId(batch.getTickBatchId(), followup.getFollowupId()));
      effect.setTickBatchId(batch.getTickBatchId());
      effect.setCommandId(null);
      effect.setEffectKey(followup.getFollowupId());
      effect.setEffectType("REMOTE_FOLLOWUP");
      effect.setTargetAggregate(remoteFollowupTargetAggregate(followup));
      effect.setStatus("STAGED");
      effect.setStagedAt(stagedAt);
      effects.add(effect);
    }
    tickEffectRepository.saveAll(effects);
  }

  private static String remoteFollowupTargetAggregate(
      net.firedevops.firemud.gamesession.entity.RemoteFollowup followup) {
    if (followup.getTargetEntityId() != null && !followup.getTargetEntityId().isBlank()) {
      return "entity:" + followup.getTargetEntityId();
    }
    return "game-instance:" + followup.getTargetGameInstanceId();
  }

  private static String effectTargetAggregate(Long gameInstanceId, GameplayCommand command) {
    if (command != null) {
      if (command.getCharacterId() != null && command.getCharacterId() > 0) {
        return "character:" + command.getCharacterId();
      }
      if (command.getTargetEntityId() != null && !command.getTargetEntityId().isBlank()) {
        return "entity:" + command.getTargetEntityId();
      }
    }
    return "game-instance:" + gameInstanceId;
  }

  private void bumpGameplayCommandAttempts(
      List<QueuedCommandEnvelope> entries, Instant attemptedAt) {
    List<GameplayCommand> commands = loadCommands(entries);
    if (commands.isEmpty()) {
      return;
    }
    for (GameplayCommand command : commands) {
      command.setAttemptCount(command.getAttemptCount() + 1);
      command.setLastAttemptAt(attemptedAt);
    }
    gameplayCommandRepository.saveAll(commands);
  }

  private List<CommandSelection> commandSelections(List<QueuedCommandEnvelope> entries) {
    if (entries.isEmpty()) {
      return List.of();
    }
    requireDurableCommandIdentifiers(entries);
    Map<String, GameplayCommand> commandsById =
        loadCommands(entries).stream()
            .collect(java.util.stream.Collectors.toMap(GameplayCommand::getCommandId, cmd -> cmd));
    List<CommandSelection> selections = new ArrayList<>(entries.size());
    for (int index = 0; index < entries.size(); index++) {
      QueuedCommandEnvelope entry = entries.get(index);
      GameplayCommand command =
          entry.commandId() == null || entry.commandId().isBlank()
              ? null
              : commandsById.get(entry.commandId());
      String effectKey = effectKey(entry, index);
      selections.add(
          new CommandSelection(
              entry,
              command,
              selectionSourceOrdinal(command, index),
              effectKey,
              shortHash(entry.command())));
    }
    return List.copyOf(selections);
  }

  private long selectionSourceOrdinal(GameplayCommand command, int fallbackIndex) {
    if (command != null && command.getEnqueueSeq() != null && command.getEnqueueSeq() > 0) {
      return command.getEnqueueSeq();
    }
    return fallbackIndex;
  }

  private void markBatchDrained(TickBatch batch, List<QueuedCommandEnvelope> entries) {
    Instant now = Instant.now();
    RuntimeRegionStatus ownership =
        requireCurrentOwnership(batch, false).orElseThrow(IllegalStateException::new);
    batch.setStatus("DRAINED");
    batch.setCompletedAt(now);
    tickBatchRepository.save(batch);
    updateEffectStatuses(batch.getTickBatchId(), "DRAINED", now, null, null);
    updateGameplayCommands(entries, "DRAINED", "PENDING", now, null, null, false);
    ownership.setLastCommittedTickBatchId(batch.getTickBatchId());
    ownership.setUpdatedAt(now);
    runtimeRegionStatusRepository.save(ownership);
    logger.info(
        "Drained durable tick batch tickBatchId={} tenantId={} gameInstanceId={} commandCount={}",
        batch.getTickBatchId(),
        batch.getTenantId(),
        batch.getGameInstanceId(),
        batch.getCommandCount());
  }

  private void markRemoteFollowupBatchDrained(TickBatch batch) {
    Instant now = Instant.now();
    RuntimeRegionStatus ownership =
        requireCurrentOwnership(batch, false).orElseThrow(IllegalStateException::new);
    batch.setStatus("DRAINED");
    batch.setCompletedAt(now);
    tickBatchRepository.save(batch);
    updateEffectStatuses(batch.getTickBatchId(), "DRAINED", now, null, null);
    ownership.setLastCommittedTickBatchId(batch.getTickBatchId());
    ownership.setUpdatedAt(now);
    runtimeRegionStatusRepository.save(ownership);
    logger.info(
        "Drained durable remote followup batch tickBatchId={} tenantId={} gameInstanceId={}",
        batch.getTickBatchId(),
        batch.getTenantId(),
        batch.getGameInstanceId());
  }

  private void markBatchAbandoned(
      TickBatch batch,
      List<QueuedCommandEnvelope> entries,
      String failureCode,
      String failureMessage) {
    Instant now = Instant.now();
    batch.setStatus("ABANDONED");
    batch.setCompletedAt(now);
    batch.setFailureCode(failureCode);
    batch.setFailureMessage(truncate(failureMessage, 500));
    tickBatchRepository.save(batch);
    updateEffectStatuses(batch.getTickBatchId(), "ABANDONED", now, failureCode, failureMessage);
    updateGameplayCommands(
        entries, "RETRY_QUEUED", "PENDING", now, failureCode, failureMessage, false);
    remoteFollowupDrainService.releaseClaimedFollowups(
        batch.getTickBatchId(), failureCode, failureMessage);
    recordRequeuedActions(entries);
    logger.warn(
        "Abandoned durable tick batch tickBatchId={} tenantId={} gameInstanceId={} code={} message={}",
        batch.getTickBatchId(),
        batch.getTenantId(),
        batch.getGameInstanceId(),
        failureCode,
        truncate(failureMessage, 500));
  }

  private void markRemoteFollowupBatchAbandoned(
      TickBatch batch, String failureCode, String failureMessage) {
    Instant now = Instant.now();
    batch.setStatus("ABANDONED");
    batch.setCompletedAt(now);
    batch.setFailureCode(failureCode);
    batch.setFailureMessage(truncate(failureMessage, 500));
    tickBatchRepository.save(batch);
    updateEffectStatuses(batch.getTickBatchId(), "ABANDONED", now, failureCode, failureMessage);
    remoteFollowupDrainService.releaseClaimedFollowups(
        batch.getTickBatchId(), failureCode, failureMessage);
    logger.warn(
        "Abandoned durable remote followup batch tickBatchId={} tenantId={} gameInstanceId={} code={} message={}",
        batch.getTickBatchId(),
        batch.getTenantId(),
        batch.getGameInstanceId(),
        failureCode,
        truncate(failureMessage, 500));
  }

  private void executeDurableEffects(Long tenantId, Long gameInstanceId) {
    List<TickBatch> drainedBatches =
        tickBatchRepository.findByTenantIdAndGameInstanceIdAndStatusOrderByCompletedAtAsc(
            tenantId, gameInstanceId, "DRAINED");
    for (TickBatch batch : drainedBatches) {
      try {
        requireCurrentOwnership(batch, false);
      } catch (StaleOwnershipException ex) {
        abandonStaleDrainedBatch(batch, ex.getMessage());
        continue;
      }
      List<TickEffect> drainedEffects =
          tickEffectRepository.findByTickBatchIdAndStatusOrderByIdAsc(
              batch.getTickBatchId(), "DRAINED");
      if (drainedEffects.isEmpty()) {
        batch.setStatus("APPLIED");
        tickBatchRepository.save(batch);
        continue;
      }
      for (TickEffect effect : drainedEffects) {
        executeDurableEffect(effect);
      }
      if (tickEffectRepository
          .findByTickBatchIdAndStatusOrderByIdAsc(batch.getTickBatchId(), "DRAINED")
          .isEmpty()) {
        batch.setStatus("APPLIED");
        tickBatchRepository.save(batch);
      }
    }
  }

  private void abandonStaleDrainedBatch(TickBatch batch, String failureMessage) {
    List<TickEffect> drainedEffects =
        tickEffectRepository.findByTickBatchIdAndStatusOrderByIdAsc(
            batch.getTickBatchId(), "DRAINED");
    List<GameplayCommand> commands = loadCommandsForEffects(drainedEffects);
    requeueCommands(batch.getTenantId(), batch.getGameInstanceId(), commands);
    Instant now = Instant.now();
    for (TickEffect effect : drainedEffects) {
      effect.setStatus("ABANDONED");
      effect.setCompletedAt(now);
      effect.setFailureCode("STALE_EXECUTOR_FENCE");
      effect.setFailureMessage(truncate(failureMessage, 500));
    }
    if (!drainedEffects.isEmpty()) {
      tickEffectRepository.saveAll(drainedEffects);
    }
    for (GameplayCommand command : commands) {
      command.setExecutionOutcome("RETRY_QUEUED");
      command.setGameplayResult("PENDING");
      command.setCompletedAt(null);
      command.setLastAttemptAt(now);
      command.setFailureCode("STALE_EXECUTOR_FENCE");
      command.setFailureMessage(truncate(failureMessage, 500));
    }
    if (!commands.isEmpty()) {
      gameplayCommandRepository.saveAll(commands);
    }
    remoteFollowupDrainService.releaseClaimedFollowups(
        batch.getTickBatchId(), "STALE_EXECUTOR_FENCE", failureMessage);
    recordRequeuedCommands(commands);
    batch.setStatus("ABANDONED");
    batch.setCompletedAt(now);
    batch.setFailureCode("STALE_EXECUTOR_FENCE");
    batch.setFailureMessage(truncate(failureMessage, 500));
    tickBatchRepository.save(batch);
    logger.warn(
        "Abandoned stale drained tick batch tickBatchId={} tenantId={} gameInstanceId={} requeuedCommands={}",
        batch.getTickBatchId(),
        batch.getTenantId(),
        batch.getGameInstanceId(),
        commands.size());
  }

  private void requeueCommands(Long tenantId, Long gameInstanceId, List<GameplayCommand> commands) {
    for (int index = commands.size() - 1; index >= 0; index--) {
      GameplayCommand command = commands.get(index);
      redisTemplate
          .opsForList()
          .leftPush(
              queueKey(tenantId, gameInstanceId),
              queuePayload(
                  command.isRequiresSoloTick(), command.getCommandId(), command.getCommandText()));
    }
  }

  private void requeueEntries(
      Long tenantId, Long gameInstanceId, List<QueuedCommandEnvelope> entries) {
    for (int index = entries.size() - 1; index >= 0; index--) {
      QueuedCommandEnvelope entry = entries.get(index);
      redisTemplate
          .opsForList()
          .leftPush(
              queueKey(tenantId, gameInstanceId),
              queuePayload(entry.requiresSoloTick(), entry.commandId(), entry.command()));
    }
  }

  private void recordRequeuedActions(List<QueuedCommandEnvelope> entries) {
    if (entries.isEmpty()) {
      return;
    }
    recordRequeuedCommands(loadCommands(entries), entries.size());
  }

  private void recordRequeuedCommands(List<GameplayCommand> commands) {
    recordRequeuedCommands(commands, commands.size());
  }

  private void recordRequeuedCommands(List<GameplayCommand> commands, int fallbackCount) {
    if (!commands.isEmpty()) {
      Map<String, Long> countsBySource =
          commands.stream()
              .collect(
                  java.util.stream.Collectors.groupingBy(
                      this::requeueMetricSource, java.util.stream.Collectors.counting()));
      countsBySource.forEach(
          (source, count) ->
              meterRegistry
                  .counter("tick_requeued_action_total", "source", source)
                  .increment(count));
      return;
    }
    if (fallbackCount > 0) {
      meterRegistry
          .counter("tick_requeued_action_total", "source", "unknown")
          .increment(fallbackCount);
    }
  }

  private String requeueMetricSource(GameplayCommand command) {
    String sourceType = normalize(command.getSourceType()).trim();
    return sourceType.isBlank() ? "unknown" : sourceType.toLowerCase(java.util.Locale.ROOT);
  }

  private void executeDurableEffect(TickEffect effect) {
    if ("REMOTE_FOLLOWUP".equals(effect.getEffectType())) {
      DurableRemoteFollowupExecutionService.DurableRemoteFollowupExecutionResult result =
          durableRemoteFollowupExecutionService.execute(effect);
      markEffectTerminal(
          effect,
          result.effectStatus(),
          "COMPLETED",
          "NOT_APPLIED",
          result.failureCode(),
          result.failureMessage());
      return;
    }
    if (effect.getCommandId() == null || effect.getCommandId().isBlank()) {
      markEffectTerminal(
          effect,
          "REJECTED",
          "COMPLETED",
          "NOT_APPLIED",
          "COMMAND_ID_REQUIRED",
          "Durable effect execution requires a linked command id");
      return;
    }
    GameplayCommand command =
        gameplayCommandRepository.findByCommandId(effect.getCommandId()).orElse(null);
    if (command == null) {
      markEffectTerminal(
          effect,
          "REJECTED",
          "COMPLETED",
          "NOT_APPLIED",
          "COMMAND_NOT_FOUND",
          "Durable effect execution could not load the linked gameplay command");
      return;
    }
    durableGameplayCommandExecutionService
        .execute(effect, command)
        .ifPresent(
            result ->
                markEffectTerminal(
                    effect,
                    result.effectStatus(),
                    result.commandExecutionOutcome(),
                    result.gameplayResult(),
                    result.failureCode(),
                    result.failureMessage()));
  }

  private void markEffectTerminal(
      TickEffect effect,
      String effectStatus,
      String commandExecutionOutcome,
      String gameplayResult,
      String failureCode,
      String failureMessage) {
    Instant now = Instant.now();
    effect.setStatus(effectStatus);
    effect.setCompletedAt(now);
    effect.setFailureCode(failureCode);
    effect.setFailureMessage(truncate(failureMessage, 500));
    tickEffectRepository.save(effect);
    if (effect.getCommandId() == null || effect.getCommandId().isBlank()) {
      return;
    }
    gameplayCommandRepository
        .findByCommandId(effect.getCommandId())
        .ifPresent(
            command -> {
              command.setExecutionOutcome(commandExecutionOutcome);
              command.setGameplayResult(gameplayResult);
              command.setCompletedAt(now);
              command.setLastAttemptAt(now);
              command.setFailureCode(failureCode);
              command.setFailureMessage(truncate(failureMessage, 500));
              gameplayCommandRepository.save(command);
            });
  }

  private Optional<RuntimeRegionStatus> requireCurrentOwnership(
      TickBatch batch, boolean allowPausedOwner) {
    RuntimeRegionStatus status =
        requireRuntimeOwnership(
            batch.getTenantId(), batch.getGameInstanceId(), batch.getRegionId());
    if (status.getRegionEpoch() != batch.getRegionEpoch()
        || !status.getExecutorFence().equals(batch.getExecutorFence())
        || (!allowPausedOwner && status.isPaused())) {
      throw new StaleOwnershipException(
          "Stale runtime ownership for tickBatchId=%s expected=(epoch=%d,fence=%s) actual=(epoch=%d,fence=%s,paused=%s)"
              .formatted(
                  batch.getTickBatchId(),
                  batch.getRegionEpoch(),
                  batch.getExecutorFence(),
                  status.getRegionEpoch(),
                  status.getExecutorFence(),
                  status.isPaused()));
    }
    return Optional.of(status);
  }

  private void updateEffectStatuses(
      String tickBatchId,
      String status,
      Instant completedAt,
      String failureCode,
      String failureMessage) {
    List<TickEffect> effects = tickEffectRepository.findByTickBatchId(tickBatchId);
    if (effects.isEmpty()) {
      return;
    }
    for (TickEffect effect : effects) {
      effect.setStatus(status);
      effect.setCompletedAt(completedAt);
      effect.setFailureCode(failureCode);
      effect.setFailureMessage(truncate(failureMessage, 500));
    }
    tickEffectRepository.saveAll(effects);
  }

  private void updateGameplayCommands(
      List<QueuedCommandEnvelope> entries,
      String executionOutcome,
      String gameplayResult,
      Instant attemptedAt,
      String failureCode,
      String failureMessage,
      boolean completed) {
    List<GameplayCommand> commands = loadCommands(entries);
    if (commands.isEmpty()) {
      return;
    }
    Map<String, Integer> entryIndexByCommandId = new HashMap<>();
    for (int index = 0; index < entries.size(); index++) {
      QueuedCommandEnvelope entry = entries.get(index);
      if (entry.commandId() != null && !entry.commandId().isBlank()) {
        entryIndexByCommandId.putIfAbsent(entry.commandId(), index);
      }
    }
    for (GameplayCommand command : commands) {
      int fallbackIndex = entryIndexByCommandId.getOrDefault(command.getCommandId(), 0);
      command.setQueueSourceKind(selectionSourceKind(command));
      command.setQueueSourceState(selectionSourceState(command));
      command.setQueueSourceOrdinal(selectionSourceOrdinal(command, fallbackIndex));
      command.setQueueSourceDueTickId(selectionSourceDueTickId(command));
      command.setQueueSourceDueAtMs(selectionSourceDueAtMs(command));
      command.setExecutionOutcome(executionOutcome);
      command.setGameplayResult(gameplayResult);
      command.setLastAttemptAt(attemptedAt);
      command.setFailureCode(failureCode);
      command.setFailureMessage(truncate(failureMessage, 500));
      if (completed) {
        command.setCompletedAt(attemptedAt);
      }
    }
    gameplayCommandRepository.saveAll(commands);
  }

  private List<GameplayCommand> loadCommands(List<QueuedCommandEnvelope> entries) {
    List<String> commandIds =
        entries.stream()
            .map(QueuedCommandEnvelope::commandId)
            .filter(commandId -> commandId != null && !commandId.isBlank())
            .distinct()
            .toList();
    if (commandIds.isEmpty()) {
      return List.of();
    }
    return gameplayCommandRepository.findByCommandIdIn(commandIds);
  }

  private List<GameplayCommand> loadCommandsForEffects(List<TickEffect> effects) {
    List<String> commandIds =
        effects.stream()
            .map(TickEffect::getCommandId)
            .filter(commandId -> commandId != null && !commandId.isBlank())
            .distinct()
            .toList();
    if (commandIds.isEmpty()) {
      return List.of();
    }
    return gameplayCommandRepository.findByCommandIdIn(commandIds);
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }

  private void requirePositive(Long value, String fieldName) {
    if (value == null || value <= 0) {
      throw new IllegalArgumentException(fieldName + " must be positive");
    }
  }

  private void requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
  }

  private void requireDurableCommandIdentifiers(List<QueuedCommandEnvelope> entries) {
    for (QueuedCommandEnvelope entry : entries) {
      if (entry.commandId() == null || entry.commandId().isBlank()) {
        throw new IllegalStateException(
            "Durable tick batching requires linked command ids for all queued commands");
      }
    }
  }

  private String normalize(String value) {
    return value == null ? "" : value;
  }

  private String failureCode(Exception ex) {
    return ex instanceof StaleOwnershipException ? "STALE_EXECUTOR_FENCE" : "ROLLBACK_REQUEUED";
  }

  private String effectKey(QueuedCommandEnvelope entry, int index) {
    if (entry.commandId() != null && !entry.commandId().isBlank()) {
      return "command:" + entry.commandId();
    }
    return "command-text:" + shortHash(entry.command()) + ":slot:" + index;
  }

  private String effectId(String tickBatchId, String effectKey) {
    return "tfx-" + shortHash(tickBatchId + "|" + effectKey);
  }

  private String selectedWorkManifest(String regionId, List<CommandSelection> selections) {
    StringBuilder builder = new StringBuilder();
    builder
        .append("{\"version\":1,\"source\":\"GAMEPLAY_COMMAND_QUEUE\",\"regionId\":\"")
        .append(jsonEscape(regionId))
        .append("\",\"items\":[");
    for (int index = 0; index < selections.size(); index++) {
      if (index > 0) {
        builder.append(',');
      }
      CommandSelection selection = selections.get(index);
      QueuedCommandEnvelope entry = selection.entry();
      GameplayCommand command = selection.command();
      builder
          .append("{\"sourceKind\":\"")
          .append(selectionSourceKind(command))
          .append("\",\"sourceOrdinal\":")
          .append(selection.sourceOrdinal())
          .append(",\"sourceState\":\"")
          .append(selectionSourceState(command))
          .append("\"")
          .append(",\"effectKey\":\"")
          .append(jsonEscape(selection.effectKey()))
          .append("\",\"commandId\":");
      if (entry.commandId() == null || entry.commandId().isBlank()) {
        builder.append("null");
      } else {
        builder.append('"').append(jsonEscape(entry.commandId())).append('"');
      }
      appendJsonStringField(
          builder, "sourceType", command == null ? null : command.getSourceType());
      appendJsonStringField(
          builder,
          "automationDispatchId",
          command == null ? null : command.getAutomationDispatchId());
      appendJsonStringField(
          builder,
          "automationWorkItemId",
          command == null ? null : command.getAutomationWorkItemId());
      appendJsonStringField(builder, "scriptId", command == null ? null : command.getScriptId());
      appendJsonStringField(
          builder, "scriptPatchVersion", command == null ? null : command.getScriptPatchVersion());
      appendJsonStringField(builder, "pluginId", command == null ? null : command.getPluginId());
      appendJsonStringField(
          builder, "pluginVersionId", command == null ? null : command.getPluginVersionId());
      appendJsonStringField(
          builder, "targetEntityId", command == null ? null : command.getTargetEntityId());
      appendJsonStringField(builder, "regionId", command == null ? null : command.getRegionId());
      appendJsonNumberField(
          builder, "regionEpoch", command == null ? null : command.getRegionEpoch());
      appendJsonNumberField(
          builder, "enqueueSeq", command == null ? null : command.getEnqueueSeq());
      appendJsonNumberField(builder, "dueTickId", command == null ? null : command.getDueTickId());
      appendJsonNumberField(builder, "queueSourceDueTickId", selectionSourceDueTickId(command));
      appendJsonNumberField(builder, "queueSourceDueAtMs", selectionSourceDueAtMs(command));
      appendJsonStringField(
          builder, "originSourceKind", command == null ? null : command.getOriginSourceKind());
      appendJsonStringField(
          builder, "originSourceState", command == null ? null : command.getOriginSourceState());
      appendJsonNumberField(
          builder,
          "originSourceOrdinal",
          command == null ? null : command.getOriginSourceOrdinal());
      appendJsonNumberField(
          builder,
          "originSourceDueTickId",
          command == null ? null : command.getOriginSourceDueTickId());
      appendJsonNumberField(
          builder,
          "originSourceDueAtMs",
          command == null ? null : command.getOriginSourceDueAtMs());
      appendJsonStringField(
          builder, "playableStateScope", command == null ? null : command.getPlayableStateScope());
      appendJsonStringField(builder, "worldSlug", command == null ? null : command.getWorldSlug());
      appendJsonStringField(builder, "realmSlug", command == null ? null : command.getRealmSlug());
      appendJsonNumberField(
          builder, "pointerVersion", command == null ? null : command.getPointerVersion());
      builder
          .append(",\"requiresSoloTick\":")
          .append(entry.requiresSoloTick())
          .append(",\"commandDigest\":\"")
          .append(selection.commandDigest())
          .append("\"}");
    }
    builder.append("]}");
    return builder.toString();
  }

  private String selectedRemoteFollowupManifest(
      String regionId, List<net.firedevops.firemud.gamesession.entity.RemoteFollowup> followups) {
    StringBuilder builder = new StringBuilder();
    builder
        .append("{\"version\":1,\"source\":\"REMOTE_FOLLOWUP_QUEUE\",\"regionId\":\"")
        .append(jsonEscape(regionId))
        .append("\",\"items\":[");
    for (int index = 0; index < followups.size(); index++) {
      if (index > 0) {
        builder.append(',');
      }
      net.firedevops.firemud.gamesession.entity.RemoteFollowup followup = followups.get(index);
      builder
          .append("{\"sourceKind\":\"REMOTE_FOLLOWUP\"")
          .append(",\"sourceOrdinal\":")
          .append(remoteFollowupSourceOrdinal(followup))
          .append(",\"sourceState\":\"TARGET_REGION_CLAIMED\"")
          .append(",\"effectKey\":\"")
          .append(jsonEscape(followup.getFollowupId()))
          .append("\"");
      appendJsonNumberField(builder, "sourceDueTickId", followup.getDueTickId());
      appendJsonStringField(builder, "followupId", followup.getFollowupId());
      appendJsonStringField(builder, "originRegionId", followup.getOriginRegionId());
      appendJsonNumberField(builder, "originRegionEpoch", followup.getOriginRegionEpoch());
      appendJsonStringField(builder, "targetRegionId", followup.getTargetRegionId());
      appendJsonNumberField(builder, "targetRegionEpoch", followup.getTargetRegionEpoch());
      appendJsonNumberField(builder, "dueTickId", followup.getDueTickId());
      appendJsonStringField(builder, "targetEntityId", followup.getTargetEntityId());
      appendJsonStringField(builder, "commandId", followup.getCommandId());
      appendJsonStringField(builder, "automationDispatchId", followup.getAutomationDispatchId());
      appendJsonStringField(builder, "automationWorkItemId", followup.getAutomationWorkItemId());
      appendJsonStringField(builder, "scriptId", followup.getScriptId());
      appendJsonStringField(builder, "scriptPatchVersion", followup.getScriptPatchVersion());
      appendJsonStringField(builder, "pluginId", followup.getPluginId());
      appendJsonStringField(builder, "pluginVersionId", followup.getPluginVersionId());
      appendJsonStringField(builder, "playableStateScope", followup.getPlayableStateScope());
      appendJsonStringField(builder, "worldSlug", followup.getWorldSlug());
      appendJsonStringField(builder, "realmSlug", followup.getRealmSlug());
      appendJsonNumberField(builder, "pointerVersion", followup.getPointerVersion());
      appendJsonStringField(builder, "payloadKind", followup.getPayloadKind());
      appendJsonStringField(builder, "requestedCommand", followup.getRequestedCommand());
      appendJsonBooleanField(builder, "requiresSoloTick", followup.isRequiresSoloTick());
      appendJsonStringField(builder, "payloadJson", followup.getPayloadJson());
      builder.append('}');
    }
    builder.append("]}");
    return builder.toString();
  }

  private long remoteFollowupSourceOrdinal(
      net.firedevops.firemud.gamesession.entity.RemoteFollowup followup) {
    return followup.getClaimOrdinal() == null || followup.getClaimOrdinal() <= 0L
        ? followup.getDueTickId()
        : followup.getClaimOrdinal();
  }

  private String selectionSourceKind(GameplayCommand command) {
    if (command != null && "RETRY_QUEUED".equals(command.getExecutionOutcome())) {
      return "GAMEPLAY_RETRY";
    }
    return "GAMEPLAY_COMMAND";
  }

  private String selectionSourceState(GameplayCommand command) {
    if (command != null && "RETRY_QUEUED".equals(command.getExecutionOutcome())) {
      return "REDIS_RETRY_CLAIMED";
    }
    return "REDIS_PENDING_CLAIMED";
  }

  private Long selectionSourceDueTickId(GameplayCommand command) {
    return command == null ? null : command.getDueTickId();
  }

  private Long selectionSourceDueAtMs(GameplayCommand command) {
    return null;
  }

  private static void appendJsonStringField(StringBuilder builder, String fieldName, String value) {
    builder.append(",\"").append(fieldName).append("\":");
    if (value == null || value.isBlank()) {
      builder.append("null");
      return;
    }
    builder.append('"').append(jsonEscape(value)).append('"');
  }

  private static void appendJsonNumberField(StringBuilder builder, String fieldName, Number value) {
    builder.append(",\"").append(fieldName).append("\":");
    if (value == null) {
      builder.append("null");
      return;
    }
    builder.append(value);
  }

  private static void appendJsonBooleanField(
      StringBuilder builder, String fieldName, boolean value) {
    builder.append(",\"").append(fieldName).append("\":").append(value);
  }

  private static String jsonEscape(String value) {
    StringBuilder builder = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      switch (current) {
        case '"' -> builder.append("\\\"");
        case '\\' -> builder.append("\\\\");
        case '\b' -> builder.append("\\b");
        case '\f' -> builder.append("\\f");
        case '\n' -> builder.append("\\n");
        case '\r' -> builder.append("\\r");
        case '\t' -> builder.append("\\t");
        default -> {
          if (current < 0x20) {
            builder.append(String.format("\\u%04x", (int) current));
          } else {
            builder.append(current);
          }
        }
      }
    }
    return builder.toString();
  }

  private String shortHash(String value) {
    try {
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(bytes.length * 2);
      for (byte current : bytes) {
        builder.append(String.format("%02x", current));
      }
      return builder.substring(0, 60);
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }

  private OwnershipSnapshot observeOwnership(Long tenantId, Long gameInstanceId) {
    Instant now = Instant.now();
    RuntimeRegionStatus status =
        runtimeRegionStatusRepository
            .findByTenantIdAndGameInstanceId(tenantId, gameInstanceId)
            .orElseGet(
                () -> {
                  RuntimeRegionStatus created = new RuntimeRegionStatus();
                  created.setTenantId(tenantId);
                  created.setGameInstanceId(gameInstanceId);
                  created.setRegionId(defaultCurrentBoundaryRegionId(gameInstanceId));
                  created.setRegionEpoch(1L);
                  created.setExecutorFence("fence-" + UUID.randomUUID());
                  created.setPaused(false);
                  return created;
                });
    status.setOwnerService(runtimeIdentity.service());
    status.setOwnerInstanceId(runtimeIdentity.serviceInstanceId());
    status.setUpdatedAt(now);
    RuntimeRegionStatus saved = runtimeRegionStatusRepository.save(status);
    return new OwnershipSnapshot(
        saved.getRegionId(),
        saved.getRegionEpoch(),
        saved.getExecutorFence(),
        saved.isPaused(),
        saved.getLastCommittedTickId());
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
    gameInstanceRepository
        .findById(gameInstanceId)
        .ifPresent(instance -> bumpOwnershipEpoch(instance.getTenantId(), gameInstanceId, true));
    logger.info("Tick pause requested for game instance {}: {}", gameInstanceId, reason);
  }

  @Override
  public void resumeTicksForGameInstance(Long gameInstanceId, String reason) {
    if (gameInstanceId == null) {
      throw new IllegalArgumentException("gameInstanceId is required");
    }
    pausedGameInstances.remove(gameInstanceId);
    gameInstanceRepository
        .findById(gameInstanceId)
        .ifPresent(instance -> bumpOwnershipEpoch(instance.getTenantId(), gameInstanceId, false));
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

  private String queuePayload(boolean requiresSoloTick, String commandId, String command) {
    String mode = requiresSoloTick ? "S" : "N";
    String durableCommandId = commandId == null || commandId.isBlank() ? "-" : commandId;
    return mode + "|" + durableCommandId + "|" + command;
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

  private void bumpOwnershipEpoch(Long tenantId, Long gameInstanceId, boolean paused) {
    Instant now = Instant.now();
    RuntimeRegionStatus status =
        runtimeRegionStatusRepository
            .findByTenantIdAndGameInstanceId(tenantId, gameInstanceId)
            .orElseGet(
                () -> {
                  RuntimeRegionStatus created = new RuntimeRegionStatus();
                  created.setTenantId(tenantId);
                  created.setGameInstanceId(gameInstanceId);
                  created.setRegionId(defaultCurrentBoundaryRegionId(gameInstanceId));
                  created.setRegionEpoch(0L);
                  return created;
                });
    status.setRegionEpoch(status.getRegionEpoch() + 1L);
    status.setExecutorFence("fence-" + UUID.randomUUID());
    status.setOwnerService(runtimeIdentity.service());
    status.setOwnerInstanceId(runtimeIdentity.serviceInstanceId());
    status.setPaused(paused);
    status.setUpdatedAt(now);
    runtimeRegionStatusRepository.save(status);
  }

  private RuntimeRegionStatus requireRuntimeOwnership(
      Long tenantId, Long gameInstanceId, String regionId) {
    if (regionId != null && !regionId.isBlank()) {
      Optional<RuntimeRegionStatus> byRegionId =
          runtimeRegionStatusRepository.findByTenantIdAndRegionId(tenantId, regionId);
      if (byRegionId.isPresent()) {
        return byRegionId.orElseThrow();
      }
    }
    return runtimeRegionStatusRepository
        .findByTenantIdAndGameInstanceId(tenantId, gameInstanceId)
        .orElseThrow(
            () ->
                new StaleOwnershipException(
                    "Missing runtime ownership for tenantId=%d gameInstanceId=%d regionId=%s"
                        .formatted(tenantId, gameInstanceId, regionId)));
  }

  private String defaultCurrentBoundaryRegionId(Long gameInstanceId) {
    return Long.toString(gameInstanceId);
  }
}
