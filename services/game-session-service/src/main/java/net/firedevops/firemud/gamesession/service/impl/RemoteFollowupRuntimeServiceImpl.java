package net.firedevops.firemud.gamesession.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
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
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshots;
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
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  static final String COORDINATOR_PENDING_REMOTE = "PENDING_REMOTE";
  static final String COORDINATOR_REMOTE_APPLIED = "REMOTE_APPLIED";
  static final String COORDINATOR_REMOTE_ABANDONED = "REMOTE_ABANDONED";
  static final String COORDINATOR_REMOTE_TIMEOUT_ABANDONED = "REMOTE_TIMEOUT_ABANDONED";
  static final String COORDINATOR_LATE_RESULT_IGNORED = "LATE_RESULT_IGNORED";
  static final String COORDINATOR_LATE_RESULT_RECONCILED = "LATE_RESULT_RECONCILED";

  static final String FOLLOWUP_SCHEDULED = "SCHEDULED";
  static final String FOLLOWUP_QUEUE_SOURCE_KIND = "REMOTE_FOLLOWUP";
  static final String FOLLOWUP_QUEUE_SOURCE_STATE_SCHEDULED = "TARGET_REGION_SCHEDULED";
  static final String FOLLOWUP_QUEUE_SOURCE_STATE_CLAIMED = "TARGET_REGION_CLAIMED";
  static final String FOLLOWUP_QUEUE_SOURCE_STATE_APPLIED = "TARGET_REGION_APPLIED";
  static final String FOLLOWUP_QUEUE_SOURCE_STATE_ABANDONED = "TARGET_REGION_ABANDONED";
  static final String FOLLOWUP_APPLIED = "APPLIED";
  static final String FOLLOWUP_ABANDONED = "ABANDONED";

  static final String RESULT_APPLIED = "APPLIED";
  static final String LATE_RESULT_REQUIRES_RECONCILIATION = "late_result_requires_reconciliation";
  static final String COMMAND_PENDING_REMOTE = "PENDING_REMOTE";
  private static final String PAYLOAD_KIND_ENQUEUE_AUTOMATION_COMMAND =
      "enqueue_automation_command";
  private static final String PAYLOAD_KIND_ENQUEUE_GAMEPLAY_COMMAND = "enqueue_gameplay_command";
  private static final String PAYLOAD_KIND_TRIGGER_SCRIPT_EVENT = "trigger_script_event";

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
    GameplayCommand command =
        gameplayCommandRepository.findByCommandId(request.commandId()).orElse(null);

    Optional<RemoteCommandCoordinator> existingCoordinator =
        remoteCommandCoordinatorRepository.findByTenantIdAndCommandId(
            request.tenantId(), request.commandId());
    RemoteCommandCoordinator coordinator =
        existingCoordinator.orElseGet(RemoteCommandCoordinator::new);
    boolean coordinatorCreated = existingCoordinator.isEmpty();
    existingCoordinator.ifPresent(existing -> validateExistingCoordinator(existing, request));
    populateCoordinator(coordinator, request, command, now);
    remoteCommandCoordinatorRepository.save(coordinator);
    mirrorCoordinatorToCommand(coordinator, now);

    Optional<RemoteFollowup> existingFollowup =
        remoteFollowupRepository
            .findByTenantIdAndTargetGameInstanceIdAndTargetRegionIdAndTargetRegionEpochAndEffectKey(
                request.tenantId(),
                request.targetGameInstanceId(),
                request.targetRegionId(),
                request.targetRegionEpoch(),
                request.effectKey());
    RemoteFollowup followup = existingFollowup.orElseGet(RemoteFollowup::new);
    boolean followupCreated = existingFollowup.isEmpty();
    existingFollowup.ifPresent(existing -> validateExistingFollowup(existing, request));
    populateFollowup(followup, request, command, now);
    remoteFollowupRepository.save(followup);
    writeRemoteHint(request.tenantId(), request.targetGameInstanceId(), request.targetEntityId());
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
    boolean existingReplay = result.getId() != null;
    if (existingReplay) {
      validateExistingResult(result, request);
    } else {
      Instant observedAt = Instant.now(clock);
      populateResult(result, request, coordinator, followup, observedAt);
      remoteFollowupResultRepository.save(result);
    }

    ResultSummary requestSummary =
        resultSummary(
            request.resultPayloadJson(),
            request.resultCommandId(),
            request.resultErrorCode(),
            request.resultMessage());
    Instant now = Instant.now(clock);
    boolean lateResult = COORDINATOR_REMOTE_TIMEOUT_ABANDONED.equals(coordinator.getState());
    applyFollowupResultState(followup, request.outcome(), requestSummary, now);
    remoteFollowupRepository.save(followup);
    return new ResultOutcome(coordinator.getState(), followup.getStatus(), lateResult, false);
  }

  @Override
  @Transactional
  public void abandonFollowup(
      long tenantId, String followupId, String failureCode, String failureMessage) {
    ControlPlaneRequestParser.requirePositive(tenantId, "tenant_id");
    requireNotBlank(followupId, "followup_id");
    RemoteFollowup followup =
        remoteFollowupRepository
            .findByTenantIdAndFollowupId(tenantId, followupId)
            .orElseThrow(() -> new IllegalArgumentException("remote followup not found"));
    followup.setStatus(FOLLOWUP_ABANDONED);
    followup.setClaimedTickBatchId(null);
    followup.setQueueSourceKind(FOLLOWUP_QUEUE_SOURCE_KIND);
    followup.setQueueSourceState(FOLLOWUP_QUEUE_SOURCE_STATE_ABANDONED);
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
      GameplayCommand targetCommand =
          linkedTargetCommand(coordinator.getTenantId(), coordinator.getFollowupId());
      if (RESULT_APPLIED.equalsIgnoreCase(result.getOutcome())
          && !isTerminalTargetCommand(targetCommand)) {
        continue;
      }
      applyTerminalResult(coordinator, result.getOutcome(), targetCommand, now);
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
        GameplayCommand targetCommand =
            linkedTargetCommand(coordinator.getTenantId(), coordinator.getFollowupId());
        if (RESULT_APPLIED.equalsIgnoreCase(result.getOutcome())
            && !isTerminalTargetCommand(targetCommand)) {
          continue;
        }
        applyTerminalResult(coordinator, result.getOutcome(), targetCommand, now);
        coordinator.setState(COORDINATOR_LATE_RESULT_RECONCILED);
        if (targetCommand == null || isAppliedTargetCommand(targetCommand)) {
          coordinator.setGameplayResult("PARTIAL");
        }
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

  private GameplayCommand linkedTargetCommand(long tenantId, String followupId) {
    if (followupId == null || followupId.isBlank()) {
      return null;
    }
    return gameplayCommandRepository
        .findFirstByTenantIdAndRemoteFollowupId(tenantId, followupId)
        .orElse(null);
  }

  private static boolean deadlineReached(
      RemoteCommandCoordinator coordinator, long currentOriginRegionEpoch, long currentTickId) {
    return currentOriginRegionEpoch > coordinator.getOriginDeadlineRegionEpoch()
        || (currentOriginRegionEpoch == coordinator.getOriginDeadlineRegionEpoch()
            && currentTickId >= coordinator.getOriginDeadlineTickId());
  }

  private static void validateScheduleRequest(ScheduleRequest request) {
    ControlPlaneRequestParser.requirePositive(request.tenantId(), "tenant_id");
    requireNotBlank(request.commandId(), "command_id");
    requireNotBlank(request.coordinatorId(), "coordinator_id");
    ControlPlaneRequestParser.requirePositive(
        request.originGameInstanceId(), "origin_game_instance_id");
    requireNotBlank(request.originRegionId(), "origin_region_id");
    ControlPlaneRequestParser.requirePositive(request.originRegionEpoch(), "origin_region_epoch");
    ControlPlaneRequestParser.requirePositive(
        request.targetGameInstanceId(), "target_game_instance_id");
    requireNotBlank(request.targetRegionId(), "target_region_id");
    ControlPlaneRequestParser.requirePositive(request.targetRegionEpoch(), "target_region_epoch");
    requireNotBlank(request.followupId(), "followup_id");
    requireNotBlank(request.effectKey(), "effect_key");
    requireNotBlank(request.lateResultPolicy(), "late_result_policy");
    validateSchedulePayload(request);
  }

  private static void validateSchedulePayload(ScheduleRequest request) {
    PayloadSummary payloadSummary =
        payloadSummary(
            request.payloadJson(),
            request.payloadKind(),
            request.requestedCommand(),
            request.requiresSoloTick());
    if (payloadSummary.kind() == null) {
      throw new IllegalArgumentException("payload kind is required");
    }
    if (!PAYLOAD_KIND_ENQUEUE_AUTOMATION_COMMAND.equals(payloadSummary.kind())
        && !PAYLOAD_KIND_ENQUEUE_GAMEPLAY_COMMAND.equals(payloadSummary.kind())
        && !PAYLOAD_KIND_TRIGGER_SCRIPT_EVENT.equals(payloadSummary.kind())) {
      throw new IllegalArgumentException(
          "payload kind '%s' is not yet supported".formatted(payloadSummary.kind()));
    }
    if (PAYLOAD_KIND_TRIGGER_SCRIPT_EVENT.equals(payloadSummary.kind())) {
      triggerScriptEventSummary(
          request.payloadJson(),
          request.eventType(),
          request.eventSchemaVersion(),
          request.scriptEventId(),
          request.triggerMode(),
          request.readSnapshotToken(),
          request.eventPayloadJson(),
          request.targetEntityId());
      return;
    }
    if (payloadSummary.command() == null) {
      throw new IllegalArgumentException(
          "payload command is required for kind '%s'".formatted(payloadSummary.kind()));
    }
  }

  private static void validateResultRequest(ResultRequest request) {
    ControlPlaneRequestParser.requirePositive(request.tenantId(), "tenant_id");
    requireNotBlank(request.resultId(), "result_id");
    requireNotBlank(request.coordinatorId(), "coordinator_id");
    requireNotBlank(request.followupId(), "followup_id");
    requireNotBlank(request.originRegionId(), "origin_region_id");
    requireNotBlank(request.targetRegionId(), "target_region_id");
    requireNotBlank(request.outcome(), "outcome");
    resultSummary(
        request.resultPayloadJson(),
        request.resultCommandId(),
        request.resultErrorCode(),
        request.resultMessage());
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
        || !sameResultAuthority(existing, request)) {
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
    if (request.targetDueTickId() != existing.getTargetDueTickId()
        || request.originDeadlineRegionEpoch() != existing.getOriginDeadlineRegionEpoch()
        || request.originDeadlineTickId() != existing.getOriginDeadlineTickId()
        || !request.lateResultPolicy().equals(existing.getLateResultPolicy())
        || !sameSchedulingMetadata(
            existing.getPlayableStateScope(),
            existing.getWorldSlug(),
            existing.getRealmSlug(),
            existing.getPointerVersion(),
            existing.getScriptPatchVersion(),
            existing.getPluginId(),
            existing.getPluginVersionId(),
            existing.getAutomationDispatchId(),
            existing.getAutomationWorkItemId(),
            existing.getScriptId(),
            request)) {
      throw new IllegalArgumentException(
          "command_id already maps to different remote followup metadata");
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
    PayloadSummary payloadSummary =
        payloadSummary(
            request.payloadJson(),
            request.payloadKind(),
            request.requestedCommand(),
            request.requiresSoloTick());
    if (request.targetDueTickId() != existing.getDueTickId()
        || !normalized(blankToNull(request.targetEntityId()))
            .equals(normalized(existing.getTargetEntityId()))
        || !normalized(blankToNull(request.payloadJson()))
            .equals(normalized(existing.getPayloadJson()))
        || !samePayloadAuthority(existing, request, payloadSummary)
        || !sameSchedulingMetadata(
            existing.getPlayableStateScope(),
            existing.getWorldSlug(),
            existing.getRealmSlug(),
            existing.getPointerVersion(),
            existing.getScriptPatchVersion(),
            existing.getPluginId(),
            existing.getPluginVersionId(),
            existing.getAutomationDispatchId(),
            existing.getAutomationWorkItemId(),
            existing.getScriptId(),
            request)) {
      throw new IllegalArgumentException(
          "effect_key already maps to different remote followup metadata");
    }
  }

  private static boolean sameSchedulingMetadata(
      String playableStateScope,
      String worldSlug,
      String realmSlug,
      Long pointerVersion,
      String scriptPatchVersion,
      String pluginId,
      String pluginVersionId,
      String automationDispatchId,
      String automationWorkItemId,
      String scriptId,
      ScheduleRequest request) {
    RoutingMetadata requestRoutingMetadata =
        routingMetadataFromStoredValues(
            request.playableStateScope(),
            request.worldSlug(),
            request.realmSlug(),
            request.pointerVersion(),
            null,
            null,
            null,
            null);
    RoutingMetadata storedRoutingMetadata =
        routingMetadataFromStoredValues(
            playableStateScope, worldSlug, realmSlug, pointerVersion, null, null, null, null);
    return normalized(requestRoutingMetadata.playableStateScope())
            .equals(normalized(storedRoutingMetadata.playableStateScope()))
        && normalized(requestRoutingMetadata.worldSlug())
            .equals(normalized(storedRoutingMetadata.worldSlug()))
        && normalized(requestRoutingMetadata.realmSlug())
            .equals(normalized(storedRoutingMetadata.realmSlug()))
        && sameLong(storedRoutingMetadata.pointerVersion(), requestRoutingMetadata.pointerVersion())
        && normalized(blankToNull(request.scriptPatchVersion()))
            .equals(normalized(scriptPatchVersion))
        && normalized(blankToNull(request.pluginId())).equals(normalized(pluginId))
        && normalized(blankToNull(request.pluginVersionId())).equals(normalized(pluginVersionId))
        && normalized(blankToNull(request.automationDispatchId()))
            .equals(normalized(automationDispatchId))
        && normalized(blankToNull(request.automationWorkItemId()))
            .equals(normalized(automationWorkItemId))
        && normalized(blankToNull(request.scriptId())).equals(normalized(scriptId));
  }

  private static boolean sameLong(Long left, Long right) {
    return left == null ? right == null : left.equals(right);
  }

  private static void requireNotBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }

  private static void populateCoordinator(
      RemoteCommandCoordinator coordinator,
      ScheduleRequest request,
      GameplayCommand command,
      Instant now) {
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
    applySchedulingMetadata(coordinator, request, command);
    coordinator.setUpdatedAt(now);
  }

  private static void populateFollowup(
      RemoteFollowup followup, ScheduleRequest request, GameplayCommand command, Instant now) {
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
    followup.setClaimTargetAggregate(
        claimTargetAggregate(request.targetEntityId(), request.targetGameInstanceId()));
    followup.setPayloadJson(blankToNull(request.payloadJson()));
    PayloadSummary payloadSummary =
        payloadSummary(
            request.payloadJson(),
            request.payloadKind(),
            request.requestedCommand(),
            request.requiresSoloTick());
    followup.setPayloadKind(payloadSummary.kind());
    followup.setRequestedCommand(payloadSummary.command());
    followup.setRequiresSoloTick(payloadSummary.requiresSoloTick());
    followup.setOriginSourceKind(
        metadataValue(request.originSourceKind(), payloadSummary.originSourceKind()));
    followup.setOriginSourceState(
        metadataValue(request.originSourceState(), payloadSummary.originSourceState()));
    followup.setOriginSourceOrdinal(
        metadataLong(request.originSourceOrdinal(), payloadSummary.originSourceOrdinal()));
    followup.setOriginSourceDueTickId(
        metadataLong(request.originSourceDueTickId(), payloadSummary.originSourceDueTickId()));
    followup.setOriginSourceDueAtMs(
        metadataLong(request.originSourceDueAtMs(), payloadSummary.originSourceDueAtMs()));
    TriggerScriptEventSummary eventSummary =
        triggerScriptEventSummary(
            request.payloadJson(),
            request.eventType(),
            request.eventSchemaVersion(),
            request.scriptEventId(),
            request.triggerMode(),
            request.readSnapshotToken(),
            request.eventPayloadJson(),
            request.targetEntityId());
    followup.setEventType(eventSummary.eventType());
    followup.setEventSchemaVersion(eventSummary.eventSchemaVersion());
    followup.setScriptEventId(eventSummary.scriptEventId());
    followup.setTriggerMode(eventSummary.triggerMode());
    followup.setReadSnapshotToken(eventSummary.readSnapshotToken());
    followup.setEventPayloadJson(eventSummary.eventPayloadJson());
    followup.setStatus(FOLLOWUP_SCHEDULED);
    followup.setClaimedTickBatchId(null);
    followup.setClaimOrdinal(null);
    followup.setQueueSourceKind(FOLLOWUP_QUEUE_SOURCE_KIND);
    followup.setQueueSourceState(FOLLOWUP_QUEUE_SOURCE_STATE_SCHEDULED);
    followup.setQueueSourceOrdinal(null);
    followup.setQueueSourceDueTickId(request.targetDueTickId());
    followup.setQueueSourceDueAtMs(null);
    followup.setFailureCode(null);
    followup.setFailureMessage(null);
    applySchedulingMetadata(followup, request, command);
    if (followup.getCreatedAt() == null) {
      followup.setCreatedAt(now);
    }
    followup.setUpdatedAt(now);
  }

  private static void populateResult(
      RemoteFollowupResult result,
      ResultRequest request,
      RemoteCommandCoordinator coordinator,
      RemoteFollowup followup,
      Instant now) {
    result.setResultId(request.resultId());
    result.setTenantId(request.tenantId());
    result.setCoordinatorId(request.coordinatorId());
    result.setFollowupId(request.followupId());
    result.setOriginGameInstanceId(
        coordinator != null && coordinator.getOriginGameInstanceId() != null
            ? coordinator.getOriginGameInstanceId()
            : followup == null ? null : followup.getOriginGameInstanceId());
    result.setOriginRegionId(request.originRegionId());
    result.setOriginRegionEpoch(request.originRegionEpoch());
    result.setTargetGameInstanceId(
        coordinator != null && coordinator.getTargetGameInstanceId() != null
            ? coordinator.getTargetGameInstanceId()
            : followup == null ? null : followup.getTargetGameInstanceId());
    result.setTargetRegionId(request.targetRegionId());
    result.setTargetRegionEpoch(request.targetRegionEpoch());
    result.setOutcome(request.outcome());
    result.setResultPayloadJson(blankToNull(request.resultPayloadJson()));
    ResultSummary resultSummary =
        resultSummary(
            request.resultPayloadJson(),
            request.resultCommandId(),
            request.resultErrorCode(),
            request.resultMessage());
    result.setResultCommandId(metadataValue(request.resultCommandId(), resultSummary.commandId()));
    result.setResultErrorCode(metadataValue(request.resultErrorCode(), resultSummary.errorCode()));
    result.setResultMessage(
        truncate(metadataValue(request.resultMessage(), resultSummary.message())));
    RoutingMetadata resultRoutingMetadata =
        routingMetadataFromStoredValues(
            coordinator != null ? coordinator.getPlayableStateScope() : null,
            coordinator != null ? coordinator.getWorldSlug() : null,
            coordinator != null ? coordinator.getRealmSlug() : null,
            coordinator != null ? coordinator.getPointerVersion() : null,
            followup == null ? null : followup.getPlayableStateScope(),
            followup == null ? null : followup.getWorldSlug(),
            followup == null ? null : followup.getRealmSlug(),
            followup == null ? null : followup.getPointerVersion());
    result.setPlayableStateScope(resultRoutingMetadata.playableStateScope());
    result.setWorldSlug(resultRoutingMetadata.worldSlug());
    result.setRealmSlug(resultRoutingMetadata.realmSlug());
    result.setScriptPatchVersion(
        blankToNull(
            coordinator != null && coordinator.getScriptPatchVersion() != null
                ? coordinator.getScriptPatchVersion()
                : followup == null ? null : followup.getScriptPatchVersion()));
    result.setPluginId(
        blankToNull(
            coordinator != null && coordinator.getPluginId() != null
                ? coordinator.getPluginId()
                : followup == null ? null : followup.getPluginId()));
    result.setPluginVersionId(
        blankToNull(
            coordinator != null && coordinator.getPluginVersionId() != null
                ? coordinator.getPluginVersionId()
                : followup == null ? null : followup.getPluginVersionId()));
    result.setCommandId(
        blankToNull(
            coordinator != null && coordinator.getCommandId() != null
                ? coordinator.getCommandId()
                : followup == null ? null : followup.getCommandId()));
    result.setAutomationDispatchId(
        blankToNull(
            coordinator != null && coordinator.getAutomationDispatchId() != null
                ? coordinator.getAutomationDispatchId()
                : followup == null ? null : followup.getAutomationDispatchId()));
    result.setAutomationWorkItemId(
        blankToNull(
            coordinator != null && coordinator.getAutomationWorkItemId() != null
                ? coordinator.getAutomationWorkItemId()
                : followup == null ? null : followup.getAutomationWorkItemId()));
    result.setScriptId(
        blankToNull(
            coordinator != null && coordinator.getScriptId() != null
                ? coordinator.getScriptId()
                : followup == null ? null : followup.getScriptId()));
    result.setPointerVersion(resultRoutingMetadata.pointerVersion());
    result.setObservedAt(now);
  }

  private static void applySchedulingMetadata(
      RemoteCommandCoordinator coordinator, ScheduleRequest request, GameplayCommand command) {
    RoutingMetadata routingMetadata =
        routingMetadataFromRequestAndCommand(
            request.playableStateScope(),
            request.worldSlug(),
            request.realmSlug(),
            request.pointerVersion(),
            command == null ? null : command.getPlayableStateScope(),
            command == null ? null : command.getWorldSlug(),
            command == null ? null : command.getRealmSlug(),
            command == null ? null : command.getPointerVersion());
    coordinator.setPlayableStateScope(routingMetadata.playableStateScope());
    coordinator.setWorldSlug(routingMetadata.worldSlug());
    coordinator.setRealmSlug(routingMetadata.realmSlug());
    coordinator.setPointerVersion(routingMetadata.pointerVersion());
    coordinator.setScriptPatchVersion(
        metadataValue(
            request.scriptPatchVersion(),
            command == null ? null : command.getScriptPatchVersion()));
    coordinator.setPluginId(
        metadataValue(request.pluginId(), command == null ? null : command.getPluginId()));
    coordinator.setPluginVersionId(
        metadataValue(
            request.pluginVersionId(), command == null ? null : command.getPluginVersionId()));
    coordinator.setAutomationDispatchId(
        metadataValue(
            request.automationDispatchId(),
            command == null ? null : command.getAutomationDispatchId()));
    coordinator.setAutomationWorkItemId(
        metadataValue(
            request.automationWorkItemId(),
            command == null ? null : command.getAutomationWorkItemId()));
    coordinator.setScriptId(
        metadataValue(request.scriptId(), command == null ? null : command.getScriptId()));
  }

  private static void applySchedulingMetadata(
      RemoteFollowup followup, ScheduleRequest request, GameplayCommand command) {
    RoutingMetadata routingMetadata =
        routingMetadataFromRequestAndCommand(
            request.playableStateScope(),
            request.worldSlug(),
            request.realmSlug(),
            request.pointerVersion(),
            command == null ? null : command.getPlayableStateScope(),
            command == null ? null : command.getWorldSlug(),
            command == null ? null : command.getRealmSlug(),
            command == null ? null : command.getPointerVersion());
    followup.setPlayableStateScope(routingMetadata.playableStateScope());
    followup.setWorldSlug(routingMetadata.worldSlug());
    followup.setRealmSlug(routingMetadata.realmSlug());
    followup.setPointerVersion(routingMetadata.pointerVersion());
    followup.setScriptPatchVersion(
        metadataValue(
            request.scriptPatchVersion(),
            command == null ? null : command.getScriptPatchVersion()));
    followup.setPluginId(
        metadataValue(request.pluginId(), command == null ? null : command.getPluginId()));
    followup.setPluginVersionId(
        metadataValue(
            request.pluginVersionId(), command == null ? null : command.getPluginVersionId()));
    followup.setCommandId(
        metadataValue(request.commandId(), command == null ? null : command.getCommandId()));
    followup.setAutomationDispatchId(
        metadataValue(
            request.automationDispatchId(),
            command == null ? null : command.getAutomationDispatchId()));
    followup.setAutomationWorkItemId(
        metadataValue(
            request.automationWorkItemId(),
            command == null ? null : command.getAutomationWorkItemId()));
    followup.setScriptId(
        metadataValue(request.scriptId(), command == null ? null : command.getScriptId()));
  }

  private static PayloadSummary payloadSummary(
      String payloadJson, String requestPayloadKind, String requestCommand, boolean requestSolo) {
    PayloadSummary jsonSummary =
        payloadSummaryFromJson(payloadJson, requestPayloadKind, requestCommand, requestSolo);
    String payloadKind = metadataValue(requestPayloadKind, jsonSummary.kind());
    String command = metadataValue(requestCommand, jsonSummary.command());
    boolean requiresSoloTick = requestSolo || jsonSummary.requiresSoloTick();
    return new PayloadSummary(
        payloadKind,
        command,
        requiresSoloTick,
        jsonSummary.originSourceKind(),
        jsonSummary.originSourceState(),
        jsonSummary.originSourceOrdinal(),
        jsonSummary.originSourceDueTickId(),
        jsonSummary.originSourceDueAtMs());
  }

  private static PayloadSummary payloadSummaryFromJson(
      String payloadJson, String requestPayloadKind, String requestCommand, boolean requestSolo) {
    if (payloadJson == null || payloadJson.isBlank()) {
      return new PayloadSummary(null, null, false, null, null, null, null, null);
    }
    JsonNode root;
    try {
      root = OBJECT_MAPPER.readTree(payloadJson);
    } catch (IOException ex) {
      throw new IllegalArgumentException("payload_json must be valid JSON");
    }
    String jsonKind = blankToNull(root.path("kind").asText(""));
    String jsonCommand = blankToNull(root.path("command").asText(""));
    boolean jsonSolo = root.path("requiresSoloTick").asBoolean(false);
    if (requestConflict(requestPayloadKind, jsonKind)) {
      throw new IllegalArgumentException("payload_json kind does not match payload_kind");
    }
    if (requestConflict(requestCommand, jsonCommand)) {
      throw new IllegalArgumentException("payload_json command does not match requested_command");
    }
    if (requestSolo && root.has("requiresSoloTick") && !jsonSolo) {
      throw new IllegalArgumentException(
          "payload_json requiresSoloTick does not match requires_solo_tick");
    }
    return new PayloadSummary(
        jsonKind,
        jsonCommand,
        jsonSolo,
        blankToNull(root.path("originSourceKind").asText("")),
        blankToNull(root.path("originSourceState").asText("")),
        positiveLong(root.path("originSourceOrdinal")),
        positiveLong(root.path("originSourceDueTickId")),
        positiveLong(root.path("originSourceDueAtMs")));
  }

  private static TriggerScriptEventSummary triggerScriptEventSummary(
      String payloadJson,
      String requestEventType,
      String requestEventSchemaVersion,
      String requestScriptEventId,
      String requestTriggerMode,
      String requestReadSnapshotToken,
      String requestEventPayloadJson,
      String targetEntityId) {
    String payloadEventType = null;
    String payloadEventSchemaVersion = null;
    String payloadScriptEventId = null;
    String payloadTriggerMode = null;
    String payloadReadSnapshotToken = null;
    String payloadEventPayloadJson = null;
    if (payloadJson != null && !payloadJson.isBlank()) {
      JsonNode root;
      try {
        root = OBJECT_MAPPER.readTree(payloadJson);
      } catch (IOException ex) {
        throw new IllegalArgumentException("payload_json must be valid JSON");
      }
      payloadEventType = blankToNull(root.path("eventType").asText(""));
      payloadEventSchemaVersion = blankToNull(root.path("eventSchemaVersion").asText(""));
      payloadScriptEventId = blankToNull(root.path("scriptEventId").asText(""));
      payloadTriggerMode = blankToNull(root.path("triggerMode").asText(""));
      payloadReadSnapshotToken = blankToNull(root.path("readSnapshotToken").asText(""));
      JsonNode payloadNode = root.path("eventPayload");
      if (!payloadNode.isMissingNode() && !payloadNode.isNull()) {
        payloadEventPayloadJson = payloadNode.toString();
      }
      if (requestConflict(requestEventType, payloadEventType)) {
        throw new IllegalArgumentException("payload_json eventType does not match event_type");
      }
      if (requestConflict(requestEventSchemaVersion, payloadEventSchemaVersion)) {
        throw new IllegalArgumentException(
            "payload_json eventSchemaVersion does not match event_schema_version");
      }
      if (requestConflict(requestScriptEventId, payloadScriptEventId)) {
        throw new IllegalArgumentException(
            "payload_json scriptEventId does not match script_event_id");
      }
      if (requestConflict(requestTriggerMode, payloadTriggerMode)) {
        throw new IllegalArgumentException("payload_json triggerMode does not match trigger_mode");
      }
      if (requestConflict(requestReadSnapshotToken, payloadReadSnapshotToken)) {
        throw new IllegalArgumentException(
            "payload_json readSnapshotToken does not match read_snapshot_token");
      }
      if (requestConflict(
          normalizedJson(requestEventPayloadJson), normalizedJson(payloadEventPayloadJson))) {
        throw new IllegalArgumentException(
            "payload_json eventPayload does not match event_payload_json");
      }
    }
    String eventType = metadataValue(requestEventType, payloadEventType);
    String eventSchemaVersion = metadataValue(requestEventSchemaVersion, payloadEventSchemaVersion);
    String scriptEventId = metadataValue(requestScriptEventId, payloadScriptEventId);
    String triggerMode = metadataValue(requestTriggerMode, payloadTriggerMode);
    String readSnapshotToken = metadataValue(requestReadSnapshotToken, payloadReadSnapshotToken);
    String eventPayloadJson = metadataValue(requestEventPayloadJson, payloadEventPayloadJson);
    if (eventType == null
        && eventSchemaVersion == null
        && scriptEventId == null
        && triggerMode == null
        && readSnapshotToken == null
        && eventPayloadJson == null) {
      return new TriggerScriptEventSummary(null, null, null, null, null, null);
    }
    if (blankToNull(targetEntityId) == null) {
      throw new IllegalArgumentException(
          "target_entity_id is required for kind 'trigger_script_event'");
    }
    if (eventType == null) {
      throw new IllegalArgumentException("trigger_script_event event_type is required");
    }
    if (scriptEventId == null) {
      throw new IllegalArgumentException("trigger_script_event script_event_id is required");
    }
    if (readSnapshotToken == null) {
      throw new IllegalArgumentException("trigger_script_event read_snapshot_token is required");
    }
    if (eventPayloadJson == null) {
      throw new IllegalArgumentException("trigger_script_event event_payload_json is required");
    }
    try {
      JsonNode payloadNode = OBJECT_MAPPER.readTree(eventPayloadJson);
      if (!payloadNode.isObject() || payloadNode.isEmpty()) {
        throw new IllegalArgumentException(
            "trigger_script_event event_payload_json must be a non-empty JSON object");
      }
    } catch (IOException ex) {
      throw new IllegalArgumentException(
          "trigger_script_event event_payload_json must be valid JSON");
    }
    return new TriggerScriptEventSummary(
        eventType,
        eventSchemaVersion == null ? "v1" : eventSchemaVersion,
        scriptEventId,
        triggerMode,
        readSnapshotToken,
        eventPayloadJson);
  }

  private static boolean requestConflict(String requestValue, String jsonValue) {
    String normalizedRequest = blankToNull(requestValue);
    String normalizedJson = blankToNull(jsonValue);
    return normalizedRequest != null
        && normalizedJson != null
        && !normalizedRequest.equals(normalizedJson);
  }

  private static String normalizedJson(String value) {
    return value == null ? "" : value.replaceAll("\\s+", "");
  }

  private static boolean samePayloadAuthority(
      RemoteFollowup existing, ScheduleRequest request, PayloadSummary payload) {
    return normalized(payload.kind()).equals(normalized(existing.getPayloadKind()))
        && normalized(payload.command()).equals(normalized(existing.getRequestedCommand()))
        && payload.requiresSoloTick() == existing.isRequiresSoloTick()
        && normalized(metadataValue(request.originSourceKind(), payload.originSourceKind()))
            .equals(normalized(existing.getOriginSourceKind()))
        && normalized(metadataValue(request.originSourceState(), payload.originSourceState()))
            .equals(normalized(existing.getOriginSourceState()))
        && sameLong(
            existing.getOriginSourceOrdinal(),
            metadataLong(request.originSourceOrdinal(), payload.originSourceOrdinal()))
        && sameLong(
            existing.getOriginSourceDueTickId(),
            metadataLong(request.originSourceDueTickId(), payload.originSourceDueTickId()))
        && sameLong(
            existing.getOriginSourceDueAtMs(),
            metadataLong(request.originSourceDueAtMs(), payload.originSourceDueAtMs()))
        && sameTriggerScriptEventAuthority(existing, request);
  }

  private static boolean sameTriggerScriptEventAuthority(
      RemoteFollowup existing, ScheduleRequest request) {
    TriggerScriptEventSummary summary =
        triggerScriptEventSummary(
            request.payloadJson(),
            request.eventType(),
            request.eventSchemaVersion(),
            request.scriptEventId(),
            request.triggerMode(),
            request.readSnapshotToken(),
            request.eventPayloadJson(),
            request.targetEntityId());
    return normalized(summary.eventType()).equals(normalized(existing.getEventType()))
        && normalized(summary.eventSchemaVersion())
            .equals(normalized(existing.getEventSchemaVersion()))
        && normalized(summary.scriptEventId()).equals(normalized(existing.getScriptEventId()))
        && normalized(summary.triggerMode()).equals(normalized(existing.getTriggerMode()))
        && normalized(summary.readSnapshotToken())
            .equals(normalized(existing.getReadSnapshotToken()))
        && normalizedJson(summary.eventPayloadJson())
            .equals(normalizedJson(existing.getEventPayloadJson()));
  }

  private static boolean sameResultAuthority(RemoteFollowupResult existing, ResultRequest request) {
    ResultSummary summary =
        resultSummary(
            request.resultPayloadJson(),
            request.resultCommandId(),
            request.resultErrorCode(),
            request.resultMessage());
    return normalized(metadataValue(request.resultCommandId(), summary.commandId()))
            .equals(normalized(existing.getResultCommandId()))
        && normalized(metadataValue(request.resultErrorCode(), summary.errorCode()))
            .equals(normalized(existing.getResultErrorCode()))
        && normalized(metadataValue(request.resultMessage(), summary.message()))
            .equals(normalized(existing.getResultMessage()));
  }

  private static ResultSummary resultSummary(
      String payloadJson, String requestCommandId, String requestErrorCode, String requestMessage) {
    ResultSummary jsonSummary =
        resultSummaryFromJson(payloadJson, requestCommandId, requestErrorCode, requestMessage);
    return new ResultSummary(
        metadataValue(requestCommandId, jsonSummary.commandId()),
        metadataValue(requestErrorCode, jsonSummary.errorCode()),
        metadataValue(requestMessage, jsonSummary.message()));
  }

  private static ResultSummary resultSummaryFromJson(
      String payloadJson, String requestCommandId, String requestErrorCode, String requestMessage) {
    if (payloadJson == null || payloadJson.isBlank()) {
      return new ResultSummary(null, null, null);
    }
    try {
      JsonNode root = OBJECT_MAPPER.readTree(payloadJson);
      String jsonCommandId = blankToNull(root.path("commandId").asText(""));
      String jsonErrorCode = blankToNull(root.path("errorCode").asText(""));
      if (jsonErrorCode == null && root.has("failureCode")) {
        jsonErrorCode = blankToNull(root.path("failureCode").asText(""));
      }
      String jsonMessage = blankToNull(root.path("message").asText(""));
      if (requestConflict(requestCommandId, jsonCommandId)) {
        throw new IllegalArgumentException(
            "result_payload_json commandId does not match result_command_id");
      }
      if (requestConflict(requestErrorCode, jsonErrorCode)) {
        throw new IllegalArgumentException(
            "result_payload_json errorCode does not match result_error_code");
      }
      if (requestConflict(requestMessage, jsonMessage)) {
        throw new IllegalArgumentException(
            "result_payload_json message does not match result_message");
      }
      return new ResultSummary(jsonCommandId, jsonErrorCode, jsonMessage);
    } catch (IOException ex) {
      throw new IllegalArgumentException("result_payload_json must be valid JSON");
    }
  }

  private static String metadataValue(String requestValue, String commandValue) {
    String normalizedRequest = blankToNull(requestValue);
    return normalizedRequest != null ? normalizedRequest : blankToNull(commandValue);
  }

  private static Long metadataLong(Long requestValue, Long commandValue) {
    return requestValue != null ? requestValue : commandValue;
  }

  private static RoutingMetadata routingMetadataFromRequestAndCommand(
      String requestPlayableStateScope,
      String requestWorldSlug,
      String requestRealmSlug,
      Long requestPointerVersion,
      String commandPlayableStateScope,
      String commandWorldSlug,
      String commandRealmSlug,
      Long commandPointerVersion) {
    return routingMetadata(
        metadataValue(requestPlayableStateScope, commandPlayableStateScope),
        metadataValue(requestWorldSlug, commandWorldSlug),
        metadataValue(requestRealmSlug, commandRealmSlug),
        metadataLong(requestPointerVersion, commandPointerVersion));
  }

  private static RoutingMetadata routingMetadataFromStoredValues(
      String primaryPlayableStateScope,
      String primaryWorldSlug,
      String primaryRealmSlug,
      Long primaryPointerVersion,
      String fallbackPlayableStateScope,
      String fallbackWorldSlug,
      String fallbackRealmSlug,
      Long fallbackPointerVersion) {
    return routingMetadata(
        metadataValue(primaryPlayableStateScope, fallbackPlayableStateScope),
        metadataValue(primaryWorldSlug, fallbackWorldSlug),
        metadataValue(primaryRealmSlug, fallbackRealmSlug),
        metadataLong(primaryPointerVersion, fallbackPointerVersion));
  }

  private static RoutingMetadata routingMetadata(
      String playableStateScope, String worldSlug, String realmSlug, Long pointerVersion) {
    String normalizedPlayableStateScope = blankToNull(playableStateScope);
    GameplayAdmissionPointerSnapshots.RoutingBundle routingBundle =
        GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(
            worldSlug, realmSlug, pointerVersion);
    if (normalizedPlayableStateScope == null || routingBundle == null) {
      return RoutingMetadata.EMPTY;
    }
    return new RoutingMetadata(
        normalizedPlayableStateScope,
        routingBundle.worldSlug(),
        routingBundle.realmSlug(),
        routingBundle.pointerVersion());
  }

  private static void applyTerminalResult(
      RemoteCommandCoordinator coordinator,
      String outcome,
      GameplayCommand targetCommand,
      Instant now) {
    if (RESULT_APPLIED.equalsIgnoreCase(outcome) && isTerminalTargetCommand(targetCommand)) {
      if (isAppliedTargetCommand(targetCommand)) {
        coordinator.setState(COORDINATOR_REMOTE_APPLIED);
        coordinator.setExecutionOutcome(FOLLOWUP_APPLIED);
        coordinator.setGameplayResult(
            targetCommand.getGameplayResult() == null || targetCommand.getGameplayResult().isBlank()
                ? "APPLIED"
                : targetCommand.getGameplayResult());
      } else {
        coordinator.setState(COORDINATOR_REMOTE_ABANDONED);
        coordinator.setExecutionOutcome(FOLLOWUP_ABANDONED);
        coordinator.setGameplayResult(
            targetCommand.getGameplayResult() == null || targetCommand.getGameplayResult().isBlank()
                ? "NOT_APPLIED"
                : targetCommand.getGameplayResult());
      }
      coordinator.setUpdatedAt(now);
      return;
    }
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

  private static boolean isTerminalTargetCommand(GameplayCommand targetCommand) {
    if (targetCommand == null) {
      return false;
    }
    String executionOutcome = normalized(targetCommand.getExecutionOutcome());
    String gameplayResult = normalized(targetCommand.getGameplayResult());
    if ("PENDING".equals(gameplayResult)) {
      return false;
    }
    return !executionOutcome.isBlank()
        && !"ACCEPTED".equals(executionOutcome)
        && !"STAGED".equals(executionOutcome)
        && !"RETRY_QUEUED".equals(executionOutcome)
        && !COMMAND_PENDING_REMOTE.equals(executionOutcome);
  }

  private static boolean isAppliedTargetCommand(GameplayCommand targetCommand) {
    if (targetCommand == null) {
      return false;
    }
    String executionOutcome = normalized(targetCommand.getExecutionOutcome());
    String gameplayResult = normalized(targetCommand.getGameplayResult());
    return "APPLIED".equals(executionOutcome)
        || "REPLAY_NOOP".equals(executionOutcome)
        || "APPLIED".equals(gameplayResult)
        || "REPLAY_NOOP".equals(gameplayResult)
        || "SUCCESS".equals(gameplayResult)
        || "PARTIAL".equals(gameplayResult);
  }

  private static void applyFollowupResultState(
      RemoteFollowup followup, String outcome, ResultSummary resultSummary, Instant now) {
    if (RESULT_APPLIED.equalsIgnoreCase(outcome)) {
      followup.setStatus(FOLLOWUP_APPLIED);
      followup.setQueueSourceKind(FOLLOWUP_QUEUE_SOURCE_KIND);
      followup.setQueueSourceState(FOLLOWUP_QUEUE_SOURCE_STATE_APPLIED);
      followup.setFailureCode(null);
      followup.setFailureMessage(null);
    } else {
      followup.setStatus(FOLLOWUP_ABANDONED);
      followup.setQueueSourceKind(FOLLOWUP_QUEUE_SOURCE_KIND);
      followup.setQueueSourceState(FOLLOWUP_QUEUE_SOURCE_STATE_ABANDONED);
      followup.setFailureCode(
          resultSummary.errorCode() == null ? "REMOTE_ABANDONED" : resultSummary.errorCode());
      followup.setFailureMessage(
          resultSummary.message() == null
              ? "Target region reported terminal abandoned outcome"
              : resultSummary.message());
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

  private record RoutingMetadata(
      String playableStateScope, String worldSlug, String realmSlug, Long pointerVersion) {
    private static final RoutingMetadata EMPTY = new RoutingMetadata(null, null, null, null);
  }

  private static Long positiveLong(JsonNode node) {
    if (node == null || !node.isNumber()) {
      return null;
    }
    long value = node.asLong();
    return value > 0 ? value : null;
  }

  private void mirrorCoordinatorToCommand(RemoteCommandCoordinator coordinator, Instant now) {
    GameplayCommand command =
        gameplayCommandRepository.findByCommandId(coordinator.getCommandId()).orElse(null);
    GameplayCommand targetCommand =
        linkedTargetCommand(coordinator.getTenantId(), coordinator.getFollowupId());
    RemoteFollowupResult latestResult =
        latestResult(coordinator.getTenantId(), coordinator.getCoordinatorId()).orElse(null);
    ResultSummary resultSummary =
        latestResult == null
            ? new ResultSummary(null, null, null)
            : new ResultSummary(
                blankToNull(latestResult.getResultCommandId()),
                blankToNull(latestResult.getResultErrorCode()),
                blankToNull(latestResult.getResultMessage()) != null
                    ? blankToNull(latestResult.getResultMessage())
                    : resultSummary(latestResult.getResultPayloadJson(), null, null, null)
                        .message());
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
        if (COORDINATOR_LATE_RESULT_IGNORED.equals(coordinator.getState())
            && command.getFailureCode() != null
            && !command.getFailureCode().isBlank()) {
          // Preserve the original terminal failure cause when a late result is ignored.
          command.setFailureMessage(
              command.getFailureMessage() == null || command.getFailureMessage().isBlank()
                  ? "Cross-region remote followup did not apply successfully"
                  : command.getFailureMessage());
        } else if (targetCommand != null
            && targetCommand.getFailureCode() != null
            && !targetCommand.getFailureCode().isBlank()) {
          command.setFailureCode(targetCommand.getFailureCode());
          command.setFailureMessage(
              targetCommand.getFailureMessage() == null
                      || targetCommand.getFailureMessage().isBlank()
                  ? "Cross-region remote followup did not apply successfully"
                  : targetCommand.getFailureMessage());
        } else if (resultSummary.errorCode() != null) {
          command.setFailureCode(resultSummary.errorCode());
          command.setFailureMessage(
              resultSummary.message() == null || resultSummary.message().isBlank()
                  ? "Cross-region remote followup did not apply successfully"
                  : resultSummary.message());
        } else {
          command.setFailureCode(coordinator.getState());
          command.setFailureMessage("Cross-region remote followup did not apply successfully");
        }
      } else {
        command.setFailureCode(null);
        command.setFailureMessage(null);
      }
    }
    gameplayCommandRepository.save(command);
  }

  private void writeRemoteHint(long tenantId, long gameInstanceId, String targetEntityId) {
    if (targetEntityId == null || targetEntityId.isBlank()) {
      return;
    }
    redisTemplate
        .opsForValue()
        .set(
            "remote:{tenant:" + tenantId + ":instance:" + gameInstanceId + "}:" + targetEntityId,
            "1",
            java.time.Duration.ofMillis(60_000L));
  }

  private static String claimTargetAggregate(String targetEntityId, long targetGameInstanceId) {
    String normalizedTargetEntityId = blankToNull(targetEntityId);
    if (normalizedTargetEntityId != null) {
      return "entity:" + normalizedTargetEntityId;
    }
    return "game-instance:" + targetGameInstanceId;
  }

  private record PayloadSummary(
      String kind,
      String command,
      boolean requiresSoloTick,
      String originSourceKind,
      String originSourceState,
      Long originSourceOrdinal,
      Long originSourceDueTickId,
      Long originSourceDueAtMs) {}

  private record TriggerScriptEventSummary(
      String eventType,
      String eventSchemaVersion,
      String scriptEventId,
      String triggerMode,
      String readSnapshotToken,
      String eventPayloadJson) {}

  private record ResultSummary(String commandId, String errorCode, String message) {}
}
