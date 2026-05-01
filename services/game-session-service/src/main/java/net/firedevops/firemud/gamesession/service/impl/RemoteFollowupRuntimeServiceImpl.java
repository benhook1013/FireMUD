package net.firedevops.firemud.gamesession.service.impl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.RemoteCommandCoordinator;
import net.firedevops.firemud.gamesession.entity.RemoteFollowup;
import net.firedevops.firemud.gamesession.entity.RemoteFollowupResult;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.RemoteCommandCoordinatorRepository;
import net.firedevops.firemud.gamesession.repository.RemoteFollowupRepository;
import net.firedevops.firemud.gamesession.repository.RemoteFollowupResultRepository;
import net.firedevops.firemud.gamesession.service.RemoteFollowupRuntimeService;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RemoteFollowupRuntimeServiceImpl implements RemoteFollowupRuntimeService {
  private static final Logger logger =
      LoggingUtil.getLogger(RemoteFollowupRuntimeServiceImpl.class);

  static final String COORDINATOR_PENDING_REMOTE = "PENDING_REMOTE";
  static final String COORDINATOR_REMOTE_APPLIED = "REMOTE_APPLIED";
  static final String COORDINATOR_REMOTE_ABANDONED = "REMOTE_ABANDONED";
  static final String COORDINATOR_REMOTE_TIMEOUT_ABANDONED = "REMOTE_TIMEOUT_ABANDONED";
  static final String COORDINATOR_LATE_RESULT_IGNORED = "LATE_RESULT_IGNORED";
  static final String COORDINATOR_LATE_RESULT_RECONCILED = "LATE_RESULT_RECONCILED";

  static final String FOLLOWUP_SCHEDULED = "SCHEDULED";
  static final String FOLLOWUP_APPLIED = "APPLIED";
  static final String FOLLOWUP_ABANDONED = "ABANDONED";

  static final String RESULT_APPLIED = "APPLIED";
  static final String LATE_RESULT_REQUIRES_RECONCILIATION = "late_result_requires_reconciliation";
  static final String COMMAND_PENDING_REMOTE = "PENDING_REMOTE";

  private final RemoteCommandCoordinatorRepository remoteCommandCoordinatorRepository;
  private final RemoteFollowupRepository remoteFollowupRepository;
  private final RemoteFollowupResultRepository remoteFollowupResultRepository;
  private final GameplayCommandRepository gameplayCommandRepository;
  private final RedisTemplate<String, Object> redisTemplate;
  private final Clock clock;
  private final Counter remoteFollowupScheduledCounter;
  private final Counter remoteFollowupTimeoutCounter;
  private final Counter lateResultIgnoredCounter;
  private final Counter lateResultReconciledCounter;

  @Autowired
  public RemoteFollowupRuntimeServiceImpl(
      RemoteCommandCoordinatorRepository remoteCommandCoordinatorRepository,
      RemoteFollowupRepository remoteFollowupRepository,
      RemoteFollowupResultRepository remoteFollowupResultRepository,
      GameplayCommandRepository gameplayCommandRepository,
      RedisTemplate<String, Object> redisTemplate,
      MeterRegistry meterRegistry) {
    this(
        remoteCommandCoordinatorRepository,
        remoteFollowupRepository,
        remoteFollowupResultRepository,
        gameplayCommandRepository,
        redisTemplate,
        meterRegistry.counter("gamesession_remote_followup_scheduled_total"),
        meterRegistry.counter("gamesession_remote_followup_timeout_total"),
        meterRegistry.counter("gamesession_remote_followup_late_result_total", "policy", "ignored"),
        meterRegistry.counter(
            "gamesession_remote_followup_late_result_total", "policy", "reconciled"),
        Clock.systemUTC());
  }

  RemoteFollowupRuntimeServiceImpl(
      RemoteCommandCoordinatorRepository remoteCommandCoordinatorRepository,
      RemoteFollowupRepository remoteFollowupRepository,
      RemoteFollowupResultRepository remoteFollowupResultRepository,
      GameplayCommandRepository gameplayCommandRepository,
      RedisTemplate<String, Object> redisTemplate,
      Counter remoteFollowupScheduledCounter,
      Counter remoteFollowupTimeoutCounter,
      Counter lateResultIgnoredCounter,
      Counter lateResultReconciledCounter,
      Clock clock) {
    this.remoteCommandCoordinatorRepository = remoteCommandCoordinatorRepository;
    this.remoteFollowupRepository = remoteFollowupRepository;
    this.remoteFollowupResultRepository = remoteFollowupResultRepository;
    this.gameplayCommandRepository = gameplayCommandRepository;
    this.redisTemplate = redisTemplate;
    this.remoteFollowupScheduledCounter = remoteFollowupScheduledCounter;
    this.remoteFollowupTimeoutCounter = remoteFollowupTimeoutCounter;
    this.lateResultIgnoredCounter = lateResultIgnoredCounter;
    this.lateResultReconciledCounter = lateResultReconciledCounter;
    this.clock = clock;
  }

  @Override
  @Transactional
  public ScheduleOutcome scheduleFollowup(ScheduleRequest request) {
    validateScheduleRequest(request);
    Instant now = Instant.now(clock);

    Optional<RemoteCommandCoordinator> existingCoordinator =
        remoteCommandCoordinatorRepository.findByTenantIdAndCommandId(
            request.tenantId(), request.commandId());
    RemoteCommandCoordinator coordinator =
        existingCoordinator.orElseGet(RemoteCommandCoordinator::new);
    boolean coordinatorCreated = existingCoordinator.isEmpty();
    existingCoordinator.ifPresent(existing -> validateExistingCoordinator(existing, request));
    populateCoordinator(coordinator, request, now);
    remoteCommandCoordinatorRepository.save(coordinator);
    mirrorCoordinatorToCommand(coordinator, now);

    Optional<RemoteFollowup> existingFollowup =
        remoteFollowupRepository.findByTenantIdAndTargetRegionIdAndTargetRegionEpochAndEffectKey(
            request.tenantId(),
            request.targetRegionId(),
            request.targetRegionEpoch(),
            request.effectKey());
    RemoteFollowup followup = existingFollowup.orElseGet(RemoteFollowup::new);
    boolean followupCreated = existingFollowup.isEmpty();
    existingFollowup.ifPresent(existing -> validateExistingFollowup(existing, request));
    populateFollowup(followup, request, now);
    remoteFollowupRepository.save(followup);
    writeRemoteHint(request.tenantId(), request.targetEntityId());
    remoteFollowupScheduledCounter.increment();

    logger.info(
        "Scheduled remote followup tenantId={} commandId={} coordinatorId={} followupId={} targetRegionId={}",
        request.tenantId(),
        request.commandId(),
        coordinator.getCoordinatorId(),
        followup.getFollowupId(),
        request.targetRegionId());
    return new ScheduleOutcome(
        coordinator.getCoordinatorId(),
        followup.getFollowupId(),
        coordinatorCreated,
        followupCreated);
  }

  @Override
  @Transactional
  public ResultOutcome recordResult(ResultRequest request) {
    validateResultRequest(request);

    RemoteCommandCoordinator coordinator =
        remoteCommandCoordinatorRepository
            .findByTenantIdAndCoordinatorId(request.tenantId(), request.coordinatorId())
            .orElseThrow(() -> new IllegalArgumentException("remote coordinator not found"));
    RemoteFollowup followup =
        remoteFollowupRepository
            .findByTenantIdAndFollowupId(request.tenantId(), request.followupId())
            .orElseThrow(() -> new IllegalArgumentException("remote followup not found"));
    validateResultScope(request, coordinator, followup);

    RemoteFollowupResult result =
        remoteFollowupResultRepository
            .findByTenantIdAndResultId(request.tenantId(), request.resultId())
            .orElseGet(RemoteFollowupResult::new);
    if (result.getId() != null) {
      validateExistingResult(result, request);
    }
    Instant now = Instant.now(clock);
    populateResult(result, request, now);
    remoteFollowupResultRepository.save(result);

    boolean lateResult = COORDINATOR_REMOTE_TIMEOUT_ABANDONED.equals(coordinator.getState());
    applyFollowupResultState(followup, request.outcome(), now);
    remoteFollowupRepository.save(followup);
    return new ResultOutcome(coordinator.getState(), followup.getStatus(), lateResult, false);
  }

  @Override
  @Transactional
  public void abandonFollowup(
      long tenantId, String followupId, String failureCode, String failureMessage) {
    requirePositive(tenantId, "tenant_id");
    requireNotBlank(followupId, "followup_id");
    RemoteFollowup followup =
        remoteFollowupRepository
            .findByTenantIdAndFollowupId(tenantId, followupId)
            .orElseThrow(() -> new IllegalArgumentException("remote followup not found"));
    followup.setStatus(FOLLOWUP_ABANDONED);
    followup.setClaimedTickBatchId(null);
    followup.setFailureCode(failureCode);
    followup.setFailureMessage(truncate(failureMessage));
    followup.setUpdatedAt(Instant.now(clock));
    remoteFollowupRepository.save(followup);
  }

  @Override
  @Transactional
  public int reconcileResults(long tenantId, String originRegionId, long currentOriginRegionEpoch) {
    if (originRegionId == null || originRegionId.isBlank()) {
      throw new IllegalArgumentException("origin_region_id is required");
    }
    Instant now = Instant.now(clock);
    int reconciled = 0;
    reconciled +=
        reconcilePendingResults(
            remoteCommandCoordinatorRepository
                .findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
                    tenantId, originRegionId, COORDINATOR_PENDING_REMOTE),
            currentOriginRegionEpoch,
            now);
    reconciled +=
        reconcileLateResults(
            remoteCommandCoordinatorRepository
                .findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
                    tenantId, originRegionId, COORDINATOR_REMOTE_TIMEOUT_ABANDONED),
            now);
    return reconciled;
  }

  @Override
  @Transactional
  public int reconcileTimeouts(
      long tenantId, String originRegionId, long currentOriginRegionEpoch, long currentTickId) {
    if (originRegionId == null || originRegionId.isBlank()) {
      throw new IllegalArgumentException("origin_region_id is required");
    }
    List<RemoteCommandCoordinator> pending =
        remoteCommandCoordinatorRepository
            .findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
                tenantId, originRegionId, COORDINATOR_PENDING_REMOTE);
    Instant now = Instant.now(clock);
    int updated = 0;
    for (RemoteCommandCoordinator coordinator : pending) {
      if (!deadlineReached(coordinator, currentOriginRegionEpoch, currentTickId)) {
        continue;
      }
      coordinator.setState(COORDINATOR_REMOTE_TIMEOUT_ABANDONED);
      coordinator.setExecutionOutcome(FOLLOWUP_ABANDONED);
      coordinator.setGameplayResult("TIMEOUT");
      coordinator.setUpdatedAt(now);
      remoteCommandCoordinatorRepository.save(coordinator);
      mirrorCoordinatorToCommand(coordinator, now);
      remoteFollowupTimeoutCounter.increment();
      updated++;
    }
    return updated;
  }

  private int reconcilePendingResults(
      List<RemoteCommandCoordinator> coordinators, long currentOriginRegionEpoch, Instant now) {
    int reconciled = 0;
    for (RemoteCommandCoordinator coordinator : coordinators) {
      if (coordinator.getOriginRegionEpoch() != currentOriginRegionEpoch) {
        continue;
      }
      RemoteFollowupResult result =
          latestResult(coordinator.getTenantId(), coordinator.getCoordinatorId()).orElse(null);
      if (result == null) {
        continue;
      }
      applyTerminalResult(coordinator, result.getOutcome(), now);
      remoteCommandCoordinatorRepository.save(coordinator);
      mirrorCoordinatorToCommand(coordinator, now);
      reconciled++;
    }
    return reconciled;
  }

  private int reconcileLateResults(List<RemoteCommandCoordinator> coordinators, Instant now) {
    int reconciled = 0;
    for (RemoteCommandCoordinator coordinator : coordinators) {
      RemoteFollowupResult result =
          latestResult(coordinator.getTenantId(), coordinator.getCoordinatorId()).orElse(null);
      if (result == null) {
        continue;
      }
      boolean requiresReconciliation =
          LATE_RESULT_REQUIRES_RECONCILIATION.equals(coordinator.getLateResultPolicy());
      if (requiresReconciliation) {
        applyTerminalResult(coordinator, result.getOutcome(), now);
        coordinator.setState(COORDINATOR_LATE_RESULT_RECONCILED);
        coordinator.setGameplayResult("PARTIAL");
      } else {
        coordinator.setState(COORDINATOR_LATE_RESULT_IGNORED);
      }
      coordinator.setUpdatedAt(now);
      remoteCommandCoordinatorRepository.save(coordinator);
      mirrorCoordinatorToCommand(coordinator, now);
      if (requiresReconciliation) {
        lateResultReconciledCounter.increment();
      } else {
        lateResultIgnoredCounter.increment();
      }
      reconciled++;
    }
    return reconciled;
  }

  private Optional<RemoteFollowupResult> latestResult(long tenantId, String coordinatorId) {
    return remoteFollowupResultRepository.findFirstByTenantIdAndCoordinatorIdOrderByObservedAtDesc(
        tenantId, coordinatorId);
  }

  private static boolean deadlineReached(
      RemoteCommandCoordinator coordinator, long currentOriginRegionEpoch, long currentTickId) {
    return currentOriginRegionEpoch > coordinator.getOriginDeadlineRegionEpoch()
        || (currentOriginRegionEpoch == coordinator.getOriginDeadlineRegionEpoch()
            && currentTickId >= coordinator.getOriginDeadlineTickId());
  }

  private static void validateScheduleRequest(ScheduleRequest request) {
    requirePositive(request.tenantId(), "tenant_id");
    requireNotBlank(request.commandId(), "command_id");
    requireNotBlank(request.coordinatorId(), "coordinator_id");
    requirePositive(request.originGameInstanceId(), "origin_game_instance_id");
    requireNotBlank(request.originRegionId(), "origin_region_id");
    requirePositive(request.targetGameInstanceId(), "target_game_instance_id");
    requireNotBlank(request.targetRegionId(), "target_region_id");
    requireNotBlank(request.followupId(), "followup_id");
    requireNotBlank(request.effectKey(), "effect_key");
    requireNotBlank(request.lateResultPolicy(), "late_result_policy");
  }

  private static void validateResultRequest(ResultRequest request) {
    requirePositive(request.tenantId(), "tenant_id");
    requireNotBlank(request.resultId(), "result_id");
    requireNotBlank(request.coordinatorId(), "coordinator_id");
    requireNotBlank(request.followupId(), "followup_id");
    requireNotBlank(request.originRegionId(), "origin_region_id");
    requireNotBlank(request.targetRegionId(), "target_region_id");
    requireNotBlank(request.outcome(), "outcome");
  }

  private static void validateResultScope(
      ResultRequest request, RemoteCommandCoordinator coordinator, RemoteFollowup followup) {
    if (!request.followupId().equals(coordinator.getFollowupId())) {
      throw new IllegalArgumentException("coordinator does not reference followup_id");
    }
    if (!request.originRegionId().equals(coordinator.getOriginRegionId())
        || request.originRegionEpoch() != coordinator.getOriginRegionEpoch()) {
      throw new IllegalArgumentException("origin scope does not match remote coordinator");
    }
    if (!request.targetRegionId().equals(coordinator.getTargetRegionId())
        || request.targetRegionEpoch() != coordinator.getTargetRegionEpoch()) {
      throw new IllegalArgumentException("target scope does not match remote coordinator");
    }
    if (!request.originRegionId().equals(followup.getOriginRegionId())
        || request.originRegionEpoch() != followup.getOriginRegionEpoch()) {
      throw new IllegalArgumentException("origin scope does not match remote followup");
    }
    if (!request.targetRegionId().equals(followup.getTargetRegionId())
        || request.targetRegionEpoch() != followup.getTargetRegionEpoch()) {
      throw new IllegalArgumentException("target scope does not match remote followup");
    }
  }

  private static void validateExistingResult(RemoteFollowupResult existing, ResultRequest request) {
    if (!request.coordinatorId().equals(existing.getCoordinatorId())
        || !request.followupId().equals(existing.getFollowupId())
        || !request.originRegionId().equals(existing.getOriginRegionId())
        || request.originRegionEpoch() != existing.getOriginRegionEpoch()
        || !request.targetRegionId().equals(existing.getTargetRegionId())
        || request.targetRegionEpoch() != existing.getTargetRegionEpoch()
        || !request.outcome().equals(existing.getOutcome())
        || !normalized(request.resultPayloadJson())
            .equals(normalized(existing.getResultPayloadJson()))) {
      throw new IllegalArgumentException("result_id already records a different remote outcome");
    }
  }

  private static void validateExistingCoordinator(
      RemoteCommandCoordinator existing, ScheduleRequest request) {
    if (!request.coordinatorId().equals(existing.getCoordinatorId())) {
      throw new IllegalArgumentException("command_id already maps to a different coordinator_id");
    }
    if (!request.followupId().equals(existing.getFollowupId())) {
      throw new IllegalArgumentException("command_id already maps to a different followup_id");
    }
    if (request.originGameInstanceId() != existing.getOriginGameInstanceId()
        || !request.originRegionId().equals(existing.getOriginRegionId())
        || request.originRegionEpoch() != existing.getOriginRegionEpoch()
        || request.targetGameInstanceId() != existing.getTargetGameInstanceId()
        || !request.targetRegionId().equals(existing.getTargetRegionId())
        || request.targetRegionEpoch() != existing.getTargetRegionEpoch()) {
      throw new IllegalArgumentException(
          "command_id already maps to a different remote execution scope");
    }
  }

  private static void validateExistingFollowup(RemoteFollowup existing, ScheduleRequest request) {
    if (!request.followupId().equals(existing.getFollowupId())) {
      throw new IllegalArgumentException(
          "effect_key already maps to a different followup_id on the target timeline");
    }
    if (request.originGameInstanceId() != existing.getOriginGameInstanceId()
        || !request.originRegionId().equals(existing.getOriginRegionId())
        || request.originRegionEpoch() != existing.getOriginRegionEpoch()
        || request.targetGameInstanceId() != existing.getTargetGameInstanceId()
        || !request.targetRegionId().equals(existing.getTargetRegionId())
        || request.targetRegionEpoch() != existing.getTargetRegionEpoch()) {
      throw new IllegalArgumentException(
          "effect_key already maps to a different remote execution scope");
    }
  }

  private static void requirePositive(long value, String field) {
    if (value <= 0) {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }

  private static void requireNotBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }

  private static void populateCoordinator(
      RemoteCommandCoordinator coordinator, ScheduleRequest request, Instant now) {
    coordinator.setCoordinatorId(request.coordinatorId());
    coordinator.setTenantId(request.tenantId());
    coordinator.setCommandId(request.commandId());
    coordinator.setFollowupId(request.followupId());
    coordinator.setOriginGameInstanceId(request.originGameInstanceId());
    coordinator.setOriginRegionId(request.originRegionId());
    coordinator.setOriginRegionEpoch(request.originRegionEpoch());
    coordinator.setTargetGameInstanceId(request.targetGameInstanceId());
    coordinator.setTargetRegionId(request.targetRegionId());
    coordinator.setTargetRegionEpoch(request.targetRegionEpoch());
    coordinator.setTargetDueTickId(request.targetDueTickId());
    coordinator.setOriginDeadlineRegionEpoch(request.originDeadlineRegionEpoch());
    coordinator.setOriginDeadlineTickId(request.originDeadlineTickId());
    coordinator.setLateResultPolicy(request.lateResultPolicy());
    if (coordinator.getState() == null
        || coordinator.getState().isBlank()
        || COORDINATOR_REMOTE_TIMEOUT_ABANDONED.equals(coordinator.getState())
        || COORDINATOR_LATE_RESULT_IGNORED.equals(coordinator.getState())
        || COORDINATOR_LATE_RESULT_RECONCILED.equals(coordinator.getState())) {
      coordinator.setState(COORDINATOR_PENDING_REMOTE);
      coordinator.setExecutionOutcome(COMMAND_PENDING_REMOTE);
      coordinator.setGameplayResult("PENDING");
    }
    coordinator.setUpdatedAt(now);
  }

  private static void populateFollowup(
      RemoteFollowup followup, ScheduleRequest request, Instant now) {
    followup.setFollowupId(request.followupId());
    followup.setTenantId(request.tenantId());
    followup.setOriginGameInstanceId(request.originGameInstanceId());
    followup.setOriginRegionId(request.originRegionId());
    followup.setOriginRegionEpoch(request.originRegionEpoch());
    followup.setTargetGameInstanceId(request.targetGameInstanceId());
    followup.setTargetRegionId(request.targetRegionId());
    followup.setTargetRegionEpoch(request.targetRegionEpoch());
    followup.setDueTickId(request.targetDueTickId());
    followup.setEffectKey(request.effectKey());
    followup.setTargetEntityId(blankToNull(request.targetEntityId()));
    followup.setPayloadJson(blankToNull(request.payloadJson()));
    followup.setStatus(FOLLOWUP_SCHEDULED);
    followup.setClaimedTickBatchId(null);
    followup.setFailureCode(null);
    followup.setFailureMessage(null);
    if (followup.getCreatedAt() == null) {
      followup.setCreatedAt(now);
    }
    followup.setUpdatedAt(now);
  }

  private static void populateResult(
      RemoteFollowupResult result, ResultRequest request, Instant now) {
    result.setResultId(request.resultId());
    result.setTenantId(request.tenantId());
    result.setCoordinatorId(request.coordinatorId());
    result.setFollowupId(request.followupId());
    result.setOriginRegionId(request.originRegionId());
    result.setOriginRegionEpoch(request.originRegionEpoch());
    result.setTargetRegionId(request.targetRegionId());
    result.setTargetRegionEpoch(request.targetRegionEpoch());
    result.setOutcome(request.outcome());
    result.setResultPayloadJson(blankToNull(request.resultPayloadJson()));
    result.setObservedAt(now);
  }

  private static void applyTerminalResult(
      RemoteCommandCoordinator coordinator, String outcome, Instant now) {
    if (RESULT_APPLIED.equalsIgnoreCase(outcome)) {
      coordinator.setState(COORDINATOR_REMOTE_APPLIED);
      coordinator.setExecutionOutcome(FOLLOWUP_APPLIED);
      coordinator.setGameplayResult("APPLIED");
    } else {
      coordinator.setState(COORDINATOR_REMOTE_ABANDONED);
      coordinator.setExecutionOutcome(FOLLOWUP_ABANDONED);
      coordinator.setGameplayResult("NOT_APPLIED");
    }
    coordinator.setUpdatedAt(now);
  }

  private static void applyFollowupResultState(
      RemoteFollowup followup, String outcome, Instant now) {
    if (RESULT_APPLIED.equalsIgnoreCase(outcome)) {
      followup.setStatus(FOLLOWUP_APPLIED);
      followup.setFailureCode(null);
      followup.setFailureMessage(null);
    } else {
      followup.setStatus(FOLLOWUP_ABANDONED);
      followup.setFailureCode("REMOTE_ABANDONED");
      followup.setFailureMessage("Target region reported terminal abandoned outcome");
    }
    followup.setClaimedTickBatchId(null);
    followup.setUpdatedAt(now);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String normalized(String value) {
    return value == null ? "" : value;
  }

  private static String truncate(String value) {
    if (value == null || value.length() <= 500) {
      return value;
    }
    return value.substring(0, 500);
  }

  private void mirrorCoordinatorToCommand(RemoteCommandCoordinator coordinator, Instant now) {
    GameplayCommand command =
        gameplayCommandRepository.findByCommandId(coordinator.getCommandId()).orElse(null);
    if (command == null) {
      return;
    }
    if (coordinator.getExecutionOutcome() != null && !coordinator.getExecutionOutcome().isBlank()) {
      command.setExecutionOutcome(coordinator.getExecutionOutcome());
    }
    if (coordinator.getGameplayResult() != null && !coordinator.getGameplayResult().isBlank()) {
      command.setGameplayResult(coordinator.getGameplayResult());
    }
    command.setLastAttemptAt(now);
    if (COMMAND_PENDING_REMOTE.equals(coordinator.getExecutionOutcome())) {
      command.setCompletedAt(null);
      command.setFailureCode(null);
      command.setFailureMessage(null);
    } else {
      command.setCompletedAt(now);
      if (FOLLOWUP_ABANDONED.equals(coordinator.getExecutionOutcome())) {
        command.setFailureCode(coordinator.getState());
        command.setFailureMessage("Cross-region remote followup did not apply successfully");
      } else {
        command.setFailureCode(null);
        command.setFailureMessage(null);
      }
    }
    gameplayCommandRepository.save(command);
  }

  private void writeRemoteHint(long tenantId, String targetEntityId) {
    if (targetEntityId == null || targetEntityId.isBlank()) {
      return;
    }
    redisTemplate
        .opsForValue()
        .set(
            "remote:" + tenantId + ":" + targetEntityId, "1", java.time.Duration.ofMillis(60_000L));
  }
}
