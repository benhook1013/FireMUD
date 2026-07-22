package net.firedevops.firemud.gamesession.service.impl;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.v1.TickStatus;
import org.slf4j.Logger;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
final class TickQueueControlService {
  static final String PURGED_FAILURE_CODE = "ROLLBACK_PURGED";

  private final RedisTemplate<String, Object> redisTemplate;
  private final GameInstanceRepository gameInstanceRepository;
  private final GameplayCommandRepository gameplayCommandRepository;
  private final RuntimeRegionStatusRepository runtimeRegionStatusRepository;
  private final RuntimeIdentity runtimeIdentity;
  private final SessionAuthenticationService sessionAuthenticationService;
  private final AtomicBoolean pauseRequested = new AtomicBoolean(false);
  private final Set<Long> pausedGameInstances = ConcurrentHashMap.newKeySet();
  private final AtomicInteger activeTicks = new AtomicInteger();

  TickQueueControlService(
      RedisTemplate<String, Object> redisTemplate,
      GameInstanceRepository gameInstanceRepository,
      GameplayCommandRepository gameplayCommandRepository,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      RuntimeIdentity runtimeIdentity,
      SessionAuthenticationService sessionAuthenticationService) {
    this.redisTemplate = redisTemplate;
    this.gameInstanceRepository = gameInstanceRepository;
    this.gameplayCommandRepository = gameplayCommandRepository;
    this.runtimeRegionStatusRepository = runtimeRegionStatusRepository;
    this.runtimeIdentity = runtimeIdentity;
    this.sessionAuthenticationService = sessionAuthenticationService;
  }

  record OwnershipSnapshot(
      String regionId,
      long regionEpoch,
      String executorFence,
      boolean paused,
      long lastCommittedTickId) {}

  static final class StaleOwnershipException extends RuntimeException {
    StaleOwnershipException(String message) {
      super(message);
    }
  }

  void enqueueCommand(
      Long tenantId,
      Long queueTargetId,
      String commandId,
      String command,
      boolean requiresSoloTick) {
    redisTemplate
        .opsForList()
        .rightPush(
            queueKey(tenantId, queueTargetId), queuePayload(requiresSoloTick, commandId, command));
  }

  long purgeQueuedAutomationCommandsForScriptPatch(
      Long tenantId,
      Long gameInstanceId,
      String regionId,
      String scriptPatchVersion,
      String reason,
      Logger logger) {
    requireText(reason, "reason");
    requirePositive(tenantId, "tenant_id");
    requirePositive(gameInstanceId, "game_instance_id");
    requireText(scriptPatchVersion, "script_patch_version");
    List<GameplayCommand> commands =
        gameplayCommandRepository.findQueuedAutomationCommandsForScriptPatch(
            tenantId, gameInstanceId, normalize(regionId), scriptPatchVersion);
    return purgeQueuedCommands(tenantId, gameInstanceId, commands, reason, logger);
  }

  long purgeQueuedAutomationCommandsForPluginVersion(
      Long tenantId,
      Long gameInstanceId,
      String regionId,
      String pluginId,
      String pluginVersionId,
      String reason,
      Logger logger) {
    requireText(reason, "reason");
    requirePositive(tenantId, "tenant_id");
    requirePositive(gameInstanceId, "game_instance_id");
    requireText(pluginId, "plugin_id");
    requireText(pluginVersionId, "plugin_version_id");
    List<GameplayCommand> commands =
        gameplayCommandRepository.findQueuedAutomationCommandsForPluginVersion(
            tenantId, gameInstanceId, normalize(regionId), pluginId, pluginVersionId);
    return purgeQueuedCommands(tenantId, gameInstanceId, commands, reason, logger);
  }

  OwnershipSnapshot observeOwnership(Long tenantId, Long gameInstanceId) {
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

  boolean isPaused(Long gameInstanceId, boolean ownershipPaused) {
    return pauseRequested.get() || pausedGameInstances.contains(gameInstanceId) || ownershipPaused;
  }

  void markTickStarted() {
    activeTicks.incrementAndGet();
  }

  void markTickFinished() {
    activeTicks.decrementAndGet();
  }

  String queryState(Long sessionId) {
    Optional<SessionContext> maybeContext =
        sessionAuthenticationService.resolveUnverifiedSessionContext(Long.toString(sessionId));
    Long tenantId =
        maybeContext
            .map(SessionContext::tenantId)
            .filter(tenant -> tenant != null && tenant > 0)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "No session context found for sessionId=" + sessionId));
    Object state = redisTemplate.opsForValue().get(stateKey(tenantId, sessionId));
    return state != null ? state.toString() : "{}";
  }

  void pauseTicks(String reason, Logger logger) {
    pauseRequested.set(true);
    logger.info("Tick pause requested: {}", reason);
  }

  void resumeTicks(String reason, Logger logger) {
    pauseRequested.set(false);
    logger.info("Tick resume requested: {}", reason);
  }

  void pauseTicksForGameInstance(Long gameInstanceId, String reason, Logger logger) {
    if (gameInstanceId == null) {
      throw new IllegalArgumentException("gameInstanceId is required");
    }
    pausedGameInstances.add(gameInstanceId);
    gameInstanceRepository
        .findById(gameInstanceId)
        .ifPresent(instance -> bumpOwnershipEpoch(instance.getTenantId(), gameInstanceId, true));
    logger.info("Tick pause requested for game instance {}: {}", gameInstanceId, reason);
  }

  void resumeTicksForGameInstance(Long gameInstanceId, String reason, Logger logger) {
    if (gameInstanceId == null) {
      throw new IllegalArgumentException("gameInstanceId is required");
    }
    pausedGameInstances.remove(gameInstanceId);
    gameInstanceRepository
        .findById(gameInstanceId)
        .ifPresent(instance -> bumpOwnershipEpoch(instance.getTenantId(), gameInstanceId, false));
    logger.info("Tick resume requested for game instance {}: {}", gameInstanceId, reason);
  }

  TickStatus getTickStatus() {
    return pauseRequested.get() && activeTicks.get() == 0
        ? TickStatus.TICK_STATUS_PAUSED
        : TickStatus.TICK_STATUS_RUNNING;
  }

  RuntimeRegionStatus requireRuntimeOwnership(Long tenantId, Long gameInstanceId, String regionId) {
    if (regionId != null && !regionId.isBlank()) {
      Optional<RuntimeRegionStatus> byRegionId =
          runtimeRegionStatusRepository.findByTenantIdAndRegionId(tenantId, regionId);
      if (byRegionId.isPresent()) {
        RuntimeRegionStatus status = byRegionId.orElseThrow();
        if (gameInstanceId != null && !gameInstanceId.equals(status.getGameInstanceId())) {
          throw new StaleOwnershipException(
              "regionId %s does not match gameInstanceId %d".formatted(regionId, gameInstanceId));
        }
        return status;
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

  RuntimeRegionStatus saveRuntimeOwnership(RuntimeRegionStatus status) {
    return runtimeRegionStatusRepository.save(status);
  }

  String queueKey(Long tenantId, Long sessionId) {
    return "gamesession:tick:queue:" + tenantId + ":" + sessionId;
  }

  String queuePayload(boolean requiresSoloTick, String commandId, String command) {
    String mode = requiresSoloTick ? "S" : "N";
    String durableCommandId = commandId == null || commandId.isBlank() ? "-" : commandId;
    return mode + "|" + durableCommandId + "|" + command;
  }

  String lockKey(Long tenantId, Long sessionId) {
    return "gamesession:tick:lock:" + tenantId + ":" + sessionId;
  }

  String stateKey(Long tenantId, Long sessionId) {
    return "session:" + tenantId + ":" + sessionId;
  }

  String pendingKey(Long tenantId, Long sessionId) {
    return "gamesession:tick:pending:" + tenantId + ":" + sessionId;
  }

  private long purgeQueuedCommands(
      Long tenantId,
      Long gameInstanceId,
      List<GameplayCommand> commands,
      String reason,
      Logger logger) {
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
      command.setExecutionOutcome("ABANDONED");
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

  private String defaultCurrentBoundaryRegionId(Long gameInstanceId) {
    return Long.toString(gameInstanceId);
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
