package net.firedevops.firemud.gamesession.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.common.LoggingUtil;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class TickQueueControlService {
  private static final Logger classLogger = LoggingUtil.getLogger(TickQueueControlService.class);
  static final String TICK_LOCK_KEY_PREFIX = "gamesession:tick:lock:";
  static final String PURGED_FAILURE_CODE = "ROLLBACK_PURGED";
  private static final Duration QUEUE_LOCK_TTL = Duration.ofSeconds(30);
  private static final Duration QUEUE_LOCK_WAIT = Duration.ofSeconds(5);
  private static final Duration QUEUE_LOCK_RETRY = Duration.ofMillis(10);
  private static final RedisScript<Long> UNLOCK_IF_OWNED_SCRIPT =
      RedisScript.of(new ClassPathResource("redis/tick_unlock_if_owned.lua"), Long.class);
  private static final RedisScript<Long> RENEW_IF_OWNED_SCRIPT =
      RedisScript.of(new ClassPathResource("redis/tick_renew_if_owned.lua"), Long.class);
  private static final RedisScript<Long> ENQUEUE_IF_ABSENT_SCRIPT =
      RedisScript.of(new ClassPathResource("redis/tick_enqueue_if_absent.lua"), Long.class);
  private static final RedisScript<Long> ENSURE_COMMAND_INDEX_SCRIPT =
      RedisScript.of(new ClassPathResource("redis/tick_ensure_command_index.lua"), Long.class);
  private static final RedisScript<Long> REMOVE_PAYLOAD_IF_OWNED_SCRIPT =
      RedisScript.of(new ClassPathResource("redis/tick_remove_payload_if_owned.lua"), Long.class);

  private final RedisTemplate<String, Object> redisTemplate;
  private final StringRedisTemplate lockRedisTemplate;
  private final GameInstanceRepository gameInstanceRepository;
  private final GameplayCommandRepository gameplayCommandRepository;
  private final RuntimeRegionStatusRepository runtimeRegionStatusRepository;
  private final RuntimeIdentity runtimeIdentity;
  private final SessionAuthenticationService sessionAuthenticationService;
  private final ScheduledExecutorService queueLockRenewalExecutor;
  private volatile RedisTemplate<String, Object> fencedScriptRedisTemplate;
  private final AtomicBoolean pauseRequested = new AtomicBoolean(false);
  private final Set<Long> pausedGameInstances = ConcurrentHashMap.newKeySet();
  private final AtomicInteger activeTicks = new AtomicInteger();

  TickQueueControlService(
      RedisTemplate<String, Object> redisTemplate,
      StringRedisTemplate lockRedisTemplate,
      GameInstanceRepository gameInstanceRepository,
      GameplayCommandRepository gameplayCommandRepository,
      RuntimeRegionStatusRepository runtimeRegionStatusRepository,
      RuntimeIdentity runtimeIdentity,
      SessionAuthenticationService sessionAuthenticationService,
      @Qualifier("queueLockRenewalExecutor") ScheduledExecutorService queueLockRenewalExecutor) {
    this.redisTemplate = redisTemplate;
    this.lockRedisTemplate = lockRedisTemplate;
    this.gameInstanceRepository = gameInstanceRepository;
    this.gameplayCommandRepository = gameplayCommandRepository;
    this.runtimeRegionStatusRepository = runtimeRegionStatusRepository;
    this.runtimeIdentity = runtimeIdentity;
    this.sessionAuthenticationService = sessionAuthenticationService;
    this.queueLockRenewalExecutor = queueLockRenewalExecutor;
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

  static final class QueueUnavailableException extends IllegalStateException {
    QueueUnavailableException(String message) {
      super(message);
    }
  }

  @Transactional
  public void enqueueCommand(
      Long tenantId,
      Long queueTargetId,
      String commandId,
      String command,
      boolean requiresSoloTick) {
    String payload = queuePayload(requiresSoloTick, commandId, command);
    QueueMutationLease leases = acquireQueueMutationLease(tenantId, queueTargetId, "enqueue");
    AtomicBoolean pushed = new AtomicBoolean(false);
    boolean completionRegistered =
        registerEnqueueCompletion(leases, tenantId, queueTargetId, payload, pushed);
    try {
      if (!gameplayCommandRepository.lockAcceptedCommandForStaging(
          tenantId, queueTargetId, commandId, command, requiresSoloTick)) {
        throw new QueueUnavailableException(
            "Command is no longer eligible for queue staging: " + commandId);
      }
      leases.requireOwned();
      pushed.set(materializeQueuePayload(tenantId, queueTargetId, payload, leases.tickLease()));
      leases.requireOwned();
      if (!gameplayCommandRepository.markAcceptedCommandStaged(commandId, Instant.now())) {
        throw new QueueUnavailableException(
            "Command is no longer eligible for queue staging: " + commandId);
      }
    } catch (RuntimeException ex) {
      if (!completionRegistered && pushed.get()) {
        removeLatestQueuePayload(tenantId, queueTargetId, payload, leases.tickLease());
      }
      throw ex;
    } finally {
      if (!completionRegistered) {
        leases.close();
      }
    }
  }

  @Transactional
  public long purgeQueuedAutomationCommandsForScriptPatch(
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
    return purgeQueuedCommands(
        tenantId,
        gameInstanceId,
        () ->
            gameplayCommandRepository.findQueuedAutomationCommandsForScriptPatch(
                tenantId, gameInstanceId, normalize(regionId), scriptPatchVersion),
        reason,
        logger);
  }

  @Transactional
  public long purgeQueuedAutomationCommandsForPluginVersion(
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
    return purgeQueuedCommands(
        tenantId,
        gameInstanceId,
        () ->
            gameplayCommandRepository.findQueuedAutomationCommandsForPluginVersion(
                tenantId, gameInstanceId, normalize(regionId), pluginId, pluginVersionId),
        reason,
        logger);
  }

  OwnershipSnapshot claimOwnership(Long tenantId, Long gameInstanceId, QueueLockLease tickLease) {
    tickLease.requireOwned();
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
                  created.setExecutorFence(tickLease.token());
                  created.setPaused(false);
                  return created;
                });
    RuntimeRegionStatus baseline;
    if (status.getId() == null) {
      status.setOwnerService(runtimeIdentity.service());
      status.setOwnerInstanceId(runtimeIdentity.serviceInstanceId());
      status.setUpdatedAt(now);
      baseline = runtimeRegionStatusRepository.ensureBaseline(status);
    } else {
      baseline = status;
    }
    RuntimeRegionStatus saved =
        runtimeRegionStatusRepository
            .claimObservedOwnership(
                baseline,
                runtimeIdentity.service(),
                runtimeIdentity.serviceInstanceId(),
                tickLease.token(),
                now)
            .orElseThrow(
                () ->
                    new StaleOwnershipException(
                        "Runtime ownership changed during lease-backed claim"));
    tickLease.requireOwned();
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
    gameInstanceRepository
        .findById(gameInstanceId)
        .ifPresent(instance -> bumpOwnershipEpoch(instance.getTenantId(), gameInstanceId, true));
    pausedGameInstances.add(gameInstanceId);
    logger.info("Tick pause requested for game instance {}: {}", gameInstanceId, reason);
  }

  void resumeTicksForGameInstance(Long gameInstanceId, String reason, Logger logger) {
    if (gameInstanceId == null) {
      throw new IllegalArgumentException("gameInstanceId is required");
    }
    gameInstanceRepository
        .findById(gameInstanceId)
        .ifPresent(instance -> bumpOwnershipEpoch(instance.getTenantId(), gameInstanceId, false));
    pausedGameInstances.remove(gameInstanceId);
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

  RuntimeRegionStatus advanceLastCommittedTickId(RuntimeRegionStatus expectedOwnership) {
    return runtimeRegionStatusRepository
        .advanceLastCommittedTickId(expectedOwnership)
        .orElseThrow(
            () ->
                new StaleOwnershipException(
                    "Runtime ownership changed before tick progress could be committed"));
  }

  RuntimeRegionStatus commitDrainedBatch(
      RuntimeRegionStatus expectedOwnership, String tickBatchId) {
    return runtimeRegionStatusRepository
        .commitDrainedBatch(expectedOwnership, tickBatchId)
        .orElseThrow(
            () ->
                new StaleOwnershipException(
                    "Runtime ownership changed before drained batch could be committed"));
  }

  String queueKey(Long tenantId, Long sessionId) {
    return "gamesession:tick:queue:" + tenantId + ":" + sessionId;
  }

  String queuePayload(boolean requiresSoloTick, String commandId, String command) {
    String mode = requiresSoloTick ? "S" : "N";
    requireDurableCommandIdWireSafe(commandId, "command_id");
    return mode + "|" + commandId + "|" + command;
  }

  static void requireDurableCommandIdWireSafe(String value, String fieldName) {
    if (value == null || value.isBlank() || "-".equals(value)) {
      throw new MissingDurableCommandIdException(fieldName + " cannot be blank or '-'");
    }
    requireQueueEncodingSafe(value, fieldName);
  }

  static final class MissingDurableCommandIdException extends IllegalArgumentException {
    MissingDurableCommandIdException(String message) {
      super(message);
    }
  }

  static void requireQueueEncodingSafe(String value, String fieldName) {
    if (value != null && value.indexOf('|') >= 0) {
      throw new IllegalArgumentException(fieldName + " cannot contain '|'");
    }
  }

  String lockKey(Long tenantId, Long sessionId) {
    return TICK_LOCK_KEY_PREFIX + tenantId + ":" + sessionId;
  }

  String mutationLockKey(Long tenantId, Long sessionId) {
    return "gamesession:tick:mutation-lock:" + tenantId + ":" + sessionId;
  }

  String stateKey(Long tenantId, Long sessionId) {
    return "session:" + tenantId + ":" + sessionId;
  }

  String pendingKey(Long tenantId, Long sessionId) {
    return "gamesession:tick:pending:" + tenantId + ":" + sessionId;
  }

  String commandIndexKey(Long tenantId, Long sessionId) {
    return "gamesession:tick:command-index:" + tenantId + ":" + sessionId;
  }

  String commandIndexMarkerKey(Long tenantId, Long sessionId) {
    return "gamesession:tick:command-index-ready:" + tenantId + ":" + sessionId;
  }

  /**
   * Materializes one command into the queue exactly once across the queue and pending projections.
   * The production path is one fenced Redis script; the direct-list fallback exists only for unit
   * tests that provide a serializer-free mock template.
   */
  private boolean materializeQueuePayload(
      Long tenantId, Long queueTargetId, String payload, QueueLockLease lease) {
    return materializeQueuePayload(tenantId, queueTargetId, payload, lease, false);
  }

  void requeueCommand(
      QueueLockLease lease,
      Long tenantId,
      Long queueTargetId,
      String commandId,
      String command,
      boolean requiresSoloTick) {
    if (lease == null) {
      throw new IllegalArgumentException("Active tick lease is required for queue recovery");
    }
    String payload = queuePayload(requiresSoloTick, commandId, command);
    lease.requireOwned();
    materializeQueuePayload(tenantId, queueTargetId, payload, lease, true);
    lease.requireOwned();
  }

  void ensureCommandIndex(QueueLockLease lease, Long tenantId, Long queueTargetId) {
    if (lease == null) {
      throw new IllegalArgumentException("Active tick lease is required for command index setup");
    }
    RedisTemplate<String, Object> scriptTemplate = fencedScriptTemplate();
    if (scriptTemplate == null || scriptTemplate.getValueSerializer() == null) {
      return;
    }
    lease.requireOwned();
    Long result =
        scriptTemplate.execute(
            ENSURE_COMMAND_INDEX_SCRIPT,
            new EnqueueScriptArgumentSerializer(scriptTemplate.getValueSerializer()),
            new org.springframework.data.redis.serializer.GenericToStringSerializer<>(Long.class),
            List.of(
                queueKey(tenantId, queueTargetId),
                pendingKey(tenantId, queueTargetId),
                commandIndexKey(tenantId, queueTargetId),
                commandIndexMarkerKey(tenantId, queueTargetId),
                lease.key()),
            lease.token());
    if (result == null || result == -1L) {
      lease.markLost();
      throw new QueueUnavailableException(
          "Lost tick lock " + lease.key() + " during Redis command index setup");
    }
    if (result == -2L) {
      throw new IllegalArgumentException(
          "Conflicting gameplay command identities prevent Redis command index setup");
    }
    if (result == -3L) {
      throw new IllegalArgumentException(
          "Malformed gameplay command queue data prevents Redis command index setup");
    }
    if (result != 1L) {
      throw new QueueUnavailableException("Invalid Redis command index setup result");
    }
  }

  private boolean materializeQueuePayload(
      Long tenantId, Long queueTargetId, String payload, QueueLockLease lease, boolean pushLeft) {
    RedisTemplate<String, Object> scriptTemplate = fencedScriptTemplate();
    if (scriptTemplate == null || scriptTemplate.getValueSerializer() == null) {
      return materializeQueuePayloadWithoutScript(tenantId, queueTargetId, payload, pushLeft);
    }
    Long result =
        scriptTemplate.execute(
            ENQUEUE_IF_ABSENT_SCRIPT,
            new EnqueueScriptArgumentSerializer(scriptTemplate.getValueSerializer()),
            new org.springframework.data.redis.serializer.GenericToStringSerializer<>(Long.class),
            List.of(
                queueKey(tenantId, queueTargetId),
                pendingKey(tenantId, queueTargetId),
                commandIndexKey(tenantId, queueTargetId),
                commandIndexMarkerKey(tenantId, queueTargetId),
                lease.key()),
            lease.token(),
            new QueuePayloadArgument(payload),
            pushLeft ? "LEFT" : "RIGHT");
    if (result == null || result == -1L) {
      lease.markLost();
      throw new QueueUnavailableException(
          "Lost enqueue lock " + lease.key() + " during Redis materialization");
    }
    if (result == -2L) {
      throw new IllegalArgumentException(
          "Gameplay command identity was reused with a conflicting queue payload");
    }
    if (result == -3L) {
      throw new IllegalArgumentException("Invalid gameplay command queue payload");
    }
    if (result == -4L) {
      throw new IllegalArgumentException("Invalid gameplay command queue direction");
    }
    if (result == 0L) {
      return false;
    }
    if (result == 1L) {
      return true;
    }
    lease.markLost();
    throw new QueueUnavailableException("Invalid Redis materialization result");
  }

  private RedisTemplate<String, Object> fencedScriptTemplate() {
    RedisTemplate<String, Object> template = fencedScriptRedisTemplate;
    if (template != null) {
      return template;
    }
    synchronized (this) {
      if (fencedScriptRedisTemplate == null) {
        fencedScriptRedisTemplate = FencedRedisScriptSupport.createTemplate(redisTemplate);
      }
      return fencedScriptRedisTemplate;
    }
  }

  private boolean materializeQueuePayloadWithoutScript(
      Long tenantId, Long queueTargetId, String payload, boolean pushLeft) {
    String commandId = commandIdFromQueuePayload(payload);
    String queueKey = queueKey(tenantId, queueTargetId);
    String pendingKey = pendingKey(tenantId, queueTargetId);
    boolean foundExact = false;
    for (String key : List.of(queueKey, pendingKey)) {
      List<Object> entries = redisTemplate.opsForList().range(key, 0, -1);
      if (entries == null) {
        continue;
      }
      for (Object entry : entries) {
        String existingPayload = entry == null ? null : entry.toString();
        if (existingPayload == null
            || !commandId.equals(commandIdFromQueuePayload(existingPayload))) {
          continue;
        }
        if (!Objects.equals(existingPayload, payload)) {
          throw new IllegalArgumentException(
              "Gameplay command identity was reused with a conflicting queue payload");
        }
        foundExact = true;
      }
    }
    if (foundExact) {
      return false;
    }
    if (pushLeft) {
      redisTemplate.opsForList().leftPush(queueKey, payload);
    } else {
      redisTemplate.opsForList().rightPush(queueKey, payload);
    }
    return true;
  }

  private static String commandIdFromQueuePayload(String payload) {
    if (payload == null
        || payload.length() < 4
        || (payload.charAt(0) != 'N' && payload.charAt(0) != 'S')
        || payload.charAt(1) != '|') {
      throw new IllegalArgumentException("Invalid gameplay command queue payload");
    }
    int idEnd = payload.indexOf('|', 2);
    if (idEnd <= 2) {
      throw new IllegalArgumentException("Invalid gameplay command queue payload");
    }
    if (payload.substring(idEnd + 1).isBlank()) {
      throw new IllegalArgumentException("Invalid gameplay command queue payload");
    }
    String commandId = payload.substring(2, idEnd);
    requireDurableCommandIdWireSafe(commandId, "command_id");
    return commandId;
  }

  private record QueuePayloadArgument(String value) {}

  private static final class EnqueueScriptArgumentSerializer implements RedisSerializer<Object> {
    private static final StringRedisSerializer RAW_STRING_SERIALIZER = new StringRedisSerializer();

    @SuppressWarnings("unchecked")
    private EnqueueScriptArgumentSerializer(RedisSerializer<?> valueSerializer) {
      this.valueSerializer = (RedisSerializer<Object>) valueSerializer;
    }

    private final RedisSerializer<Object> valueSerializer;

    @Override
    public byte[] serialize(Object value) {
      if (value instanceof QueuePayloadArgument payload) {
        return valueSerializer.serialize(payload.value());
      }
      if (value instanceof String string) {
        return RAW_STRING_SERIALIZER.serialize(string);
      }
      throw new IllegalArgumentException(
          "Enqueue script arguments must be strings or explicit queue payloads");
    }

    @Override
    public Object deserialize(byte[] bytes) {
      return RAW_STRING_SERIALIZER.deserialize(bytes);
    }
  }

  Optional<QueueLockLease> tryAcquireTickLease(
      Long tenantId, Long queueTargetId, String purpose, Logger logger) {
    return tryAcquireLease(lockKey(tenantId, queueTargetId), purpose, logger);
  }

  private QueueMutationLease acquireQueueMutationLease(
      Long tenantId, Long queueTargetId, String purpose) {
    QueueLockLease mutationLease =
        acquireLease(mutationLockKey(tenantId, queueTargetId), purpose + " mutation");
    try {
      QueueLockLease tickLease = acquireLease(lockKey(tenantId, queueTargetId), purpose + " tick");
      return new QueueMutationLease(mutationLease, tickLease);
    } catch (RuntimeException ex) {
      mutationLease.close();
      throw ex;
    }
  }

  private QueueLockLease acquireLease(String key, String purpose) {
    long deadline = System.nanoTime() + QUEUE_LOCK_WAIT.toNanos();
    do {
      Optional<QueueLockLease> acquired = tryAcquireLease(key, purpose, null);
      if (acquired.isPresent()) {
        return acquired.orElseThrow();
      }
      try {
        Thread.sleep(QUEUE_LOCK_RETRY.toMillis());
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new QueueUnavailableException("Interrupted while acquiring " + purpose + " lock");
      }
    } while (System.nanoTime() < deadline);
    throw new QueueUnavailableException("Timed out acquiring " + purpose + " lock");
  }

  private Optional<QueueLockLease> tryAcquireLease(
      String key, String purpose, Logger contentionLogger) {
    String token = UUID.randomUUID().toString();
    Boolean acquired = lockRedisTemplate.opsForValue().setIfAbsent(key, token, QUEUE_LOCK_TTL);
    if (!Boolean.TRUE.equals(acquired)) {
      if (contentionLogger != null) {
        contentionLogger.debug("Could not acquire {} lock {}", purpose, key);
      }
      return Optional.empty();
    }
    try {
      return Optional.of(new QueueLockLease(key, token, purpose));
    } catch (RuntimeException ex) {
      releaseLease(key, token, purpose);
      throw ex;
    }
  }

  private long purgeQueuedCommands(
      Long tenantId,
      Long gameInstanceId,
      Supplier<List<GameplayCommand>> commandSupplier,
      String reason,
      Logger logger) {
    QueueMutationLease leases = acquireQueueMutationLease(tenantId, gameInstanceId, "purge");
    boolean completionRegistered = false;
    try {
      ensureCommandIndex(leases.tickLease(), tenantId, gameInstanceId);
      List<PurgeCandidate> candidates =
          commandSupplier.get().stream()
              .map(this::purgeCandidate)
              .filter(Optional::isPresent)
              .map(Optional::orElseThrow)
              .toList();
      if (candidates.isEmpty()) {
        return 0L;
      }
      List<GameplayCommand> commands = candidates.stream().map(PurgeCandidate::command).toList();
      completionRegistered =
          registerPurgeCompletion(leases, tenantId, gameInstanceId, commands, logger);
      leases.requireOwned();
      Instant now = Instant.now();
      for (PurgeCandidate candidate : candidates) {
        GameplayCommand command = candidate.command();
        command.setExecutionOutcome(candidate.batchBound() ? "ABANDONED" : "LOST_BEFORE_STAGING");
        command.setGameplayResult("NOT_APPLIED");
        command.setCompletedAt(now);
        command.setLastAttemptAt(now);
        command.setFailureCode(PURGED_FAILURE_CODE);
        command.setFailureMessage(truncate(reason, 500));
      }
      gameplayCommandRepository.saveAll(commands);
      leases.requireOwned();
      if (!completionRegistered) {
        removePurgedPayloads(tenantId, gameInstanceId, commands, logger, leases.tickLease());
      }
      logger.info(
          "Purged {} queued automation commands tenantId={} gameInstanceId={}",
          commands.size(),
          tenantId,
          gameInstanceId);
      return commands.size();
    } finally {
      if (!completionRegistered) {
        leases.close();
      }
    }
  }

  private Optional<PurgeCandidate> purgeCandidate(GameplayCommand command) {
    boolean batchBound = gameplayCommandRepository.hasDurableTickEffect(command.getCommandId());
    if (batchBound && !"RETRY_QUEUED".equals(command.getExecutionOutcome())) {
      return Optional.empty();
    }
    return Optional.of(new PurgeCandidate(command, batchBound));
  }

  private boolean registerPurgeCompletion(
      QueueMutationLease leases,
      Long tenantId,
      Long gameInstanceId,
      List<GameplayCommand> commands,
      Logger logger) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return false;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void beforeCommit(boolean readOnly) {
            leases.requireOwned();
          }

          @Override
          public void afterCommit() {
            removePurgedPayloads(tenantId, gameInstanceId, commands, logger, leases.tickLease());
          }

          @Override
          public void afterCompletion(int status) {
            leases.close();
          }
        });
    return true;
  }

  private void removePurgedPayloads(
      Long tenantId,
      Long gameInstanceId,
      List<GameplayCommand> commands,
      Logger logger,
      QueueLockLease lease) {
    for (GameplayCommand command : commands) {
      try {
        String payload =
            queuePayload(
                command.isRequiresSoloTick(), command.getCommandId(), command.getCommandText());
        removePayloadIfOwned(tenantId, gameInstanceId, payload, lease);
      } catch (RuntimeException cleanupFailure) {
        logger.warn(
            "Durable purge committed but Redis cleanup failed tenantId={} gameInstanceId={} commandId={}",
            tenantId,
            gameInstanceId,
            command.getCommandId(),
            cleanupFailure);
        if (!lease.isOwned()) {
          break;
        }
      }
    }
  }

  private boolean registerEnqueueCompletion(
      QueueMutationLease leases,
      Long tenantId,
      Long queueTargetId,
      String payload,
      AtomicBoolean pushed) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return false;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void beforeCommit(boolean readOnly) {
            leases.requireOwned();
          }

          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED && pushed.get()) {
              removeLatestQueuePayload(tenantId, queueTargetId, payload, leases.tickLease());
            }
            leases.close();
          }
        });
    return true;
  }

  private void removeLatestQueuePayload(
      Long tenantId, Long queueTargetId, String payload, QueueLockLease lease) {
    try {
      RedisTemplate<String, Object> scriptTemplate = fencedScriptTemplate();
      if (scriptTemplate == null || scriptTemplate.getValueSerializer() == null) {
        redisTemplate.opsForList().remove(queueKey(tenantId, queueTargetId), -1, payload);
        return;
      }
      removePayloadIfOwned(tenantId, queueTargetId, payload, lease);
    } catch (RuntimeException cleanupFailure) {
      // An ACCEPTED durable row is not executable, so a stale payload is safe to discard later.
      classLogger.warn(
          "Failed to remove rolled-back queue payload tenantId={} gameInstanceId={}",
          tenantId,
          queueTargetId,
          cleanupFailure);
    }
  }

  private void removePayloadIfOwned(
      Long tenantId, Long queueTargetId, String payload, QueueLockLease lease) {
    if (lease == null) {
      throw new IllegalArgumentException("Active tick lease is required for queue cleanup");
    }
    RedisTemplate<String, Object> scriptTemplate = fencedScriptTemplate();
    if (scriptTemplate == null || scriptTemplate.getValueSerializer() == null) {
      redisTemplate.opsForList().remove(queueKey(tenantId, queueTargetId), 0, payload);
      redisTemplate.opsForList().remove(pendingKey(tenantId, queueTargetId), 0, payload);
      return;
    }
    Long result =
        scriptTemplate.execute(
            REMOVE_PAYLOAD_IF_OWNED_SCRIPT,
            new EnqueueScriptArgumentSerializer(scriptTemplate.getValueSerializer()),
            new org.springframework.data.redis.serializer.GenericToStringSerializer<>(Long.class),
            List.of(
                queueKey(tenantId, queueTargetId),
                pendingKey(tenantId, queueTargetId),
                commandIndexKey(tenantId, queueTargetId),
                lease.key()),
            lease.token(),
            new QueuePayloadArgument(payload));
    if (result == null || result == -1L) {
      lease.markLost();
      throw new QueueUnavailableException(
          "Lost cleanup lock " + lease.key() + " during Redis materialization cleanup");
    }
    if (result == -2L) {
      throw new IllegalArgumentException("Invalid gameplay command queue payload");
    }
    if (result == -3L) {
      throw new IllegalArgumentException(
          "Gameplay command identity was reused with a conflicting queue payload");
    }
  }

  final class QueueLockLease implements AutoCloseable {
    private final String key;
    private final String token;
    private final String purpose;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean lost = new AtomicBoolean(false);
    private final AtomicBoolean renewalCancelled = new AtomicBoolean(false);
    private final ScheduledFuture<?> renewal;

    private QueueLockLease(String key, String token, String purpose) {
      this.key = key;
      this.token = token;
      this.purpose = purpose;
      long renewalPeriodMs = Math.max(1L, QUEUE_LOCK_TTL.toMillis() / 3L);
      this.renewal =
          queueLockRenewalExecutor.scheduleAtFixedRate(
              this::renew, renewalPeriodMs, renewalPeriodMs, TimeUnit.MILLISECONDS);
    }

    void requireOwned() {
      if (!isOwned() || !renewOwnership()) {
        throw new QueueUnavailableException("Lost " + purpose + " lock " + key);
      }
    }

    boolean isOwned() {
      return !lost.get() && !closed.get();
    }

    String key() {
      return key;
    }

    String token() {
      return token;
    }

    void markLost() {
      lost.set(true);
      cancelRenewal();
    }

    private void renew() {
      renewOwnership();
    }

    private boolean renewOwnership() {
      if (closed.get() || lost.get()) {
        return false;
      }
      try {
        Long renewed =
            lockRedisTemplate.execute(
                RENEW_IF_OWNED_SCRIPT,
                List.of(key),
                token,
                String.valueOf(QUEUE_LOCK_TTL.toMillis()));
        if (renewed == null || renewed != 1L) {
          markLost();
          classLogger.error("Lost {} lock {} during renewal", purpose, key);
          return false;
        }
        return true;
      } catch (RuntimeException renewalFailure) {
        markLost();
        classLogger.error("Failed to renew {} lock {}", purpose, key, renewalFailure);
        return false;
      }
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      cancelRenewal();
      releaseLease(key, token, purpose);
    }

    private void cancelRenewal() {
      if (renewal != null && renewalCancelled.compareAndSet(false, true)) {
        renewal.cancel(false);
      }
    }
  }

  private void releaseLease(String key, String token, String purpose) {
    try {
      lockRedisTemplate.execute(UNLOCK_IF_OWNED_SCRIPT, List.of(key), token);
    } catch (RuntimeException releaseFailure) {
      classLogger.warn("Failed to release {} lock {}", purpose, key, releaseFailure);
    }
  }

  private record QueueMutationLease(QueueLockLease mutationLease, QueueLockLease tickLease)
      implements AutoCloseable {
    private void requireOwned() {
      mutationLease.requireOwned();
      tickLease.requireOwned();
    }

    @Override
    public void close() {
      tickLease.close();
      mutationLease.close();
    }
  }

  private record PurgeCandidate(GameplayCommand command, boolean batchBound) {}

  private void bumpOwnershipEpoch(Long tenantId, Long gameInstanceId, boolean paused) {
    try (QueueMutationLease leases =
        acquireQueueMutationLease(tenantId, gameInstanceId, paused ? "pause" : "resume")) {
      leases.requireOwned();
      RuntimeRegionStatus status = new RuntimeRegionStatus();
      status.setTenantId(tenantId);
      status.setGameInstanceId(gameInstanceId);
      status.setRegionId(defaultCurrentBoundaryRegionId(gameInstanceId));
      status.setExecutorFence(leases.tickLease().token());
      status.setOwnerService(runtimeIdentity.service());
      status.setOwnerInstanceId(runtimeIdentity.serviceInstanceId());
      status.setPaused(paused);
      status.setUpdatedAt(Instant.now());
      runtimeRegionStatusRepository.advanceOwnershipEpoch(status);
      leases.requireOwned();
    }
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
