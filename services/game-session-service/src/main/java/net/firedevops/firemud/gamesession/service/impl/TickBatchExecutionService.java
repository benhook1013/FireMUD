package net.firedevops.firemud.gamesession.service.impl;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.entity.TickBatch;
import net.firedevops.firemud.gamesession.entity.TickEffect;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.TickBatchRepository;
import net.firedevops.firemud.gamesession.repository.TickEffectRepository;
import net.firedevops.firemud.gamesession.service.DurableGameplayCommandExecutionService;
import net.firedevops.firemud.gamesession.service.DurableRemoteFollowupExecutionService;
import net.firedevops.firemud.gamesession.service.RemoteFollowupDrainService;
import org.slf4j.Logger;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
final class TickBatchExecutionService {
  private static final Logger logger = LoggingUtil.getLogger(TickBatchExecutionService.class);

  private final MeterRegistry meterRegistry;
  private final RedisTemplate<String, Object> redisTemplate;
  private final GameplayCommandRepository gameplayCommandRepository;
  private final TickBatchRepository tickBatchRepository;
  private final TickEffectRepository tickEffectRepository;
  private final DurableGameplayCommandExecutionService durableGameplayCommandExecutionService;
  private final DurableRemoteFollowupExecutionService durableRemoteFollowupExecutionService;
  private final RemoteFollowupDrainService remoteFollowupDrainService;
  private final TickQueueControlService tickQueueControlService;

  TickBatchExecutionService(
      MeterRegistry meterRegistry,
      RedisTemplate<String, Object> redisTemplate,
      GameplayCommandRepository gameplayCommandRepository,
      TickBatchRepository tickBatchRepository,
      TickEffectRepository tickEffectRepository,
      DurableGameplayCommandExecutionService durableGameplayCommandExecutionService,
      DurableRemoteFollowupExecutionService durableRemoteFollowupExecutionService,
      RemoteFollowupDrainService remoteFollowupDrainService,
      TickQueueControlService tickQueueControlService) {
    this.meterRegistry = meterRegistry;
    this.redisTemplate = redisTemplate;
    this.gameplayCommandRepository = gameplayCommandRepository;
    this.tickBatchRepository = tickBatchRepository;
    this.tickEffectRepository = tickEffectRepository;
    this.durableGameplayCommandExecutionService = durableGameplayCommandExecutionService;
    this.durableRemoteFollowupExecutionService = durableRemoteFollowupExecutionService;
    this.remoteFollowupDrainService = remoteFollowupDrainService;
    this.tickQueueControlService = tickQueueControlService;
  }

  void restorePendingProjection(
      Long tenantId,
      Long gameInstanceId,
      List<TickQueuedCommandEnvelope> pendingEntries,
      List<TickQueuedCommandEnvelope> sealedEntries) {
    Instant now = Instant.now();
    Set<String> sealedCommandIds =
        sealedEntries.stream()
            .map(TickQueuedCommandEnvelope::commandId)
            .collect(java.util.stream.Collectors.toSet());
    List<TickQueuedCommandEnvelope> redisOnlyEntries =
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
    String pendingKey = tickQueueControlService.pendingKey(tenantId, gameInstanceId);
    redisTemplate.delete(pendingKey);
    for (TickQueuedCommandEnvelope entry : sealedEntries) {
      redisTemplate
          .opsForList()
          .rightPush(
              pendingKey,
              tickQueueControlService.queuePayload(
                  entry.requiresSoloTick(), entry.commandId(), entry.command()));
    }
  }

  void markBatchManifestMismatch(
      TickBatch batch, List<TickQueuedCommandEnvelope> entries, String actualManifestDigest) {
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
    meterRegistry.counter("tick_manifest_mismatch_total").increment();
  }

  void markBatchDrained(TickBatch batch, List<TickQueuedCommandEnvelope> entries) {
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
    tickQueueControlService.saveRuntimeOwnership(ownership);
    logger.info(
        "Drained durable tick batch tickBatchId={} tenantId={} gameInstanceId={} commandCount={}",
        batch.getTickBatchId(),
        batch.getTenantId(),
        batch.getGameInstanceId(),
        batch.getCommandCount());
  }

  void markRemoteFollowupBatchDrained(TickBatch batch) {
    Instant now = Instant.now();
    RuntimeRegionStatus ownership =
        requireCurrentOwnership(batch, false).orElseThrow(IllegalStateException::new);
    batch.setStatus("DRAINED");
    batch.setCompletedAt(now);
    tickBatchRepository.save(batch);
    updateEffectStatuses(batch.getTickBatchId(), "DRAINED", now, null, null);
    ownership.setLastCommittedTickBatchId(batch.getTickBatchId());
    ownership.setUpdatedAt(now);
    tickQueueControlService.saveRuntimeOwnership(ownership);
    logger.info(
        "Drained durable remote followup batch tickBatchId={} tenantId={} gameInstanceId={}",
        batch.getTickBatchId(),
        batch.getTenantId(),
        batch.getGameInstanceId());
  }

  void markBatchAbandoned(
      TickBatch batch,
      List<TickQueuedCommandEnvelope> entries,
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

  void markRemoteFollowupBatchAbandoned(
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

  void executeDurableEffects(Long tenantId, Long gameInstanceId) {
    List<TickBatch> drainedBatches =
        tickBatchRepository.findByTenantIdAndGameInstanceIdAndStatusOrderByCompletedAtAsc(
            tenantId, gameInstanceId, "DRAINED");
    for (TickBatch batch : drainedBatches) {
      try {
        requireCurrentOwnership(batch, false);
      } catch (TickQueueControlService.StaleOwnershipException ex) {
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

  Optional<RuntimeRegionStatus> requireCurrentOwnership(TickBatch batch, boolean allowPausedOwner) {
    RuntimeRegionStatus status =
        tickQueueControlService.requireRuntimeOwnership(
            batch.getTenantId(), batch.getGameInstanceId(), batch.getRegionId());
    if (status.getRegionEpoch() != batch.getRegionEpoch()
        || !status.getExecutorFence().equals(batch.getExecutorFence())
        || (!allowPausedOwner && status.isPaused())) {
      throw new TickQueueControlService.StaleOwnershipException(
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
      stampQueueSource(command, "RETRY_QUEUED", null, 0);
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
              tickQueueControlService.queueKey(tenantId, gameInstanceId),
              tickQueueControlService.queuePayload(
                  command.isRequiresSoloTick(), command.getCommandId(), command.getCommandText()));
    }
  }

  private void requeueEntries(
      Long tenantId, Long gameInstanceId, List<TickQueuedCommandEnvelope> entries) {
    for (int index = entries.size() - 1; index >= 0; index--) {
      TickQueuedCommandEnvelope entry = entries.get(index);
      redisTemplate
          .opsForList()
          .leftPush(
              tickQueueControlService.queueKey(tenantId, gameInstanceId),
              tickQueueControlService.queuePayload(
                  entry.requiresSoloTick(), entry.commandId(), entry.command()));
    }
  }

  private void recordRequeuedActions(List<TickQueuedCommandEnvelope> entries) {
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
      List<TickQueuedCommandEnvelope> entries,
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
    Map<String, TickQueuedCommandEnvelope> entriesByCommandId = new HashMap<>();
    for (int index = 0; index < entries.size(); index++) {
      TickQueuedCommandEnvelope entry = entries.get(index);
      if (entry.commandId() != null && !entry.commandId().isBlank()) {
        entryIndexByCommandId.putIfAbsent(entry.commandId(), index);
        entriesByCommandId.putIfAbsent(entry.commandId(), entry);
      }
    }
    for (GameplayCommand command : commands) {
      int fallbackIndex = entryIndexByCommandId.getOrDefault(command.getCommandId(), 0);
      stampQueueSource(
          command, executionOutcome, entriesByCommandId.get(command.getCommandId()), fallbackIndex);
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

  private List<GameplayCommand> loadCommands(List<TickQueuedCommandEnvelope> entries) {
    List<String> commandIds =
        entries.stream()
            .map(TickQueuedCommandEnvelope::commandId)
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

  private void stampQueueSource(
      GameplayCommand command,
      String targetExecutionOutcome,
      TickQueuedCommandEnvelope entry,
      int fallbackIndex) {
    command.setQueueSourceKind(queueSourceKind(command, targetExecutionOutcome));
    command.setQueueSourceState(queueSourceState(command, targetExecutionOutcome));
    command.setQueueSourceOrdinal(selectionSourceOrdinal(command, entry, fallbackIndex));
    command.setQueueSourceDueTickId(
        selectionSourceDueTickId(command, entry, targetExecutionOutcome));
    command.setQueueSourceDueAtMs(selectionSourceDueAtMs(command, entry, targetExecutionOutcome));
  }

  private long selectionSourceOrdinal(
      GameplayCommand command, TickQueuedCommandEnvelope entry, int fallbackIndex) {
    if (entry != null
        && entry.sealedQueueSource() != null
        && entry.sealedQueueSource().sourceOrdinal() > 0) {
      return entry.sealedQueueSource().sourceOrdinal();
    }
    if (command != null
        && command.getQueueSourceOrdinal() != null
        && command.getQueueSourceOrdinal() > 0) {
      return command.getQueueSourceOrdinal();
    }
    if (timerOriginSelection(command)
        && command.getOriginSourceOrdinal() != null
        && command.getOriginSourceOrdinal() > 0) {
      return command.getOriginSourceOrdinal();
    }
    if (command != null && command.getEnqueueSeq() != null && command.getEnqueueSeq() > 0) {
      return command.getEnqueueSeq();
    }
    return fallbackIndex;
  }

  private String selectionSourceKind(GameplayCommand command) {
    if (command != null && "RETRY_QUEUED".equals(command.getExecutionOutcome())) {
      return "GAMEPLAY_RETRY";
    }
    if (timerOriginSelection(command)) {
      return "SCHEDULE_TIMER";
    }
    return "GAMEPLAY_COMMAND";
  }

  private String queueSourceKind(GameplayCommand command, String targetExecutionOutcome) {
    if ("RETRY_QUEUED".equals(targetExecutionOutcome)) {
      return "GAMEPLAY_RETRY";
    }
    return selectionSourceKind(command);
  }

  private String selectionSourceState(GameplayCommand command) {
    if (command != null && "RETRY_QUEUED".equals(command.getExecutionOutcome())) {
      return "REDIS_RETRY_CLAIMED";
    }
    if (timerOriginSelection(command)
        && command.getOriginSourceState() != null
        && !command.getOriginSourceState().isBlank()) {
      return command.getOriginSourceState();
    }
    return "REDIS_PENDING_CLAIMED";
  }

  private String queueSourceState(GameplayCommand command, String targetExecutionOutcome) {
    if ("RETRY_QUEUED".equals(targetExecutionOutcome)) {
      return "REDIS_RETRY_QUEUED";
    }
    return selectionSourceState(command);
  }

  private Long selectionSourceDueTickId(
      GameplayCommand command, TickQueuedCommandEnvelope entry, String targetExecutionOutcome) {
    if (entry != null
        && entry.sealedQueueSource() != null
        && entry.sealedQueueSource().sourceDueTickId() != null
        && entry.sealedQueueSource().sourceDueTickId() > 0) {
      return entry.sealedQueueSource().sourceDueTickId();
    }
    if ("RETRY_QUEUED".equals(targetExecutionOutcome)
        && command != null
        && command.getQueueSourceDueTickId() != null
        && command.getQueueSourceDueTickId() > 0) {
      return command.getQueueSourceDueTickId();
    }
    if (timerOriginSelection(command)
        && command.getOriginSourceDueTickId() != null
        && command.getOriginSourceDueTickId() > 0) {
      return command.getOriginSourceDueTickId();
    }
    return command == null ? null : command.getDueTickId();
  }

  private Long selectionSourceDueAtMs(
      GameplayCommand command, TickQueuedCommandEnvelope entry, String targetExecutionOutcome) {
    if (entry != null
        && entry.sealedQueueSource() != null
        && entry.sealedQueueSource().sourceDueAtMs() != null
        && entry.sealedQueueSource().sourceDueAtMs() > 0) {
      return entry.sealedQueueSource().sourceDueAtMs();
    }
    if ("RETRY_QUEUED".equals(targetExecutionOutcome)
        && command != null
        && command.getQueueSourceDueAtMs() != null
        && command.getQueueSourceDueAtMs() > 0) {
      return command.getQueueSourceDueAtMs();
    }
    if (timerOriginSelection(command)
        && command.getOriginSourceDueAtMs() != null
        && command.getOriginSourceDueAtMs() > 0) {
      return command.getOriginSourceDueAtMs();
    }
    return null;
  }

  private boolean timerOriginSelection(GameplayCommand command) {
    return command != null
        && !"RETRY_QUEUED".equals(command.getExecutionOutcome())
        && "SCHEDULE_TIMER".equals(command.getOriginSourceKind());
  }

  private String normalize(String value) {
    return value == null ? "" : value;
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }
}
