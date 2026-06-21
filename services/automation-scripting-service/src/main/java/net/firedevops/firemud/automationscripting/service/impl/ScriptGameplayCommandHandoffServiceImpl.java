package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.entity.ScriptHandoffEvent;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptEventAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptHandoffEventRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.AutomationAdmissionStateService;
import net.firedevops.firemud.automationscripting.service.ScriptGameplayCommandHandoffService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchInstanceRolloutProjectionService;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.EnqueueAutomationCommandIfAbsentRequest;
import net.firedevops.firemud.gamesession.v1.EnqueueAutomationCommandIfAbsentResponse;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import net.firedevops.firemud.gamesession.v1.ScheduleRemoteFollowupRequest;
import net.firedevops.firemud.gamesession.v1.ScheduleRemoteFollowupResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected collaborators are framework-managed and retained internally")
public class ScriptGameplayCommandHandoffServiceImpl
    implements ScriptGameplayCommandHandoffService {
  private static final String STATUS_HANDOFF_IN_FLIGHT = "HANDOFF_IN_FLIGHT";
  private static final String STATUS_HANDED_OFF = "HANDED_OFF";
  private static final String STATUS_CANCELED = "CANCELED";
  private static final String STATUS_DEAD_LETTERED = "DEAD_LETTERED";
  private static final String REASON_RUNTIME_REGION_SCOPE_ADVANCED =
      "runtime_region_scope_advanced";
  private static final String REMOTE_LATE_RESULT_POLICY = "late_result_safe_to_ignore";
  private static final String ERROR_REMOTE_RESPONSE_INVALID = "REMOTE_RESPONSE_INVALID";

  private final GameSessionControlPlaneClient gameSessionClient;
  private final ScriptWorkItemRepository workItemRepository;
  private final ScriptEventAuditRepository auditRepository;
  private final ScriptHandoffEventRepository handoffEventRepository;
  private final AutomationAdmissionStateService automationAdmissionStateService;
  private final ScriptPatchInstanceRolloutProjectionService rolloutProjectionService;

  public ScriptGameplayCommandHandoffServiceImpl(
      GameSessionControlPlaneClient gameSessionClient,
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository auditRepository,
      ScriptHandoffEventRepository handoffEventRepository,
      AutomationAdmissionStateService automationAdmissionStateService,
      ScriptPatchInstanceRolloutProjectionService rolloutProjectionService) {
    this.gameSessionClient = gameSessionClient;
    this.workItemRepository = workItemRepository;
    this.auditRepository = auditRepository;
    this.handoffEventRepository = handoffEventRepository;
    this.automationAdmissionStateService = automationAdmissionStateService;
    this.rolloutProjectionService = rolloutProjectionService;
  }

  @Override
  @Transactional
  public HandoffResult handoff(ScriptWorkItem workItem, EmittedCommand command) {
    requireWorkItem(workItem);
    requireCommand(command);
    String dispatchId = dispatchId(workItem, command.ordinal());
    if (isEpochAdvanced(workItem)) {
      Instant now = Instant.now();
      cancelForRollbackEpochAdvance(workItem, command, dispatchId, now);
      return new HandoffResult(false, "rollback_epoch_advanced", "", "", "", "");
    }
    if (isRuntimeRegionScopeAdvanced(workItem)) {
      Instant now = Instant.now();
      cancelForRuntimeRegionScopeAdvance(workItem, command, dispatchId, now);
      return new HandoffResult(false, REASON_RUNTIME_REGION_SCOPE_ADVANCED, "", "", "", "");
    }
    Instant now = Instant.now();
    workItem.setStatus(STATUS_HANDOFF_IN_FLIGHT);
    workItem.setUpdatedAt(now);
    workItemRepository.save(workItem);
    rolloutProjectionService.refreshForWorkItem(workItem);

    EnqueueAutomationCommandIfAbsentResponse response =
        requiresRemoteHandoff(workItem, command)
            ? null
            : gameSessionClient.enqueueAutomationCommandIfAbsent(
                toRequest(workItem, command, dispatchId));
    HandoffResult result;
    if (response != null) {
      result =
          new HandoffResult(
              response.getAccepted(),
              response.getAdmissionOutcome(),
              response.getCommandId(),
              "",
              "",
              response.hasError() ? response.getError().getCode() : "");
    } else {
      ScheduleRemoteFollowupResponse remoteResponse =
          gameSessionClient.scheduleRemoteFollowup(
              toRemoteScheduleRequest(workItem, command, dispatchId));
      result = remoteHandoffResult(remoteResponse);
    }
    applyOutcome(workItem, command, dispatchId, result, now);
    return result;
  }

  private static HandoffResult remoteHandoffResult(ScheduleRemoteFollowupResponse remoteResponse) {
    String remoteCoordinatorId = remoteResponse.getCoordinatorId();
    String remoteFollowupId = remoteResponse.getFollowupId();
    if (remoteResponse.hasError()) {
      return new HandoffResult(
          false,
          "REMOTE_REJECTED",
          "",
          remoteCoordinatorId,
          remoteFollowupId,
          remoteResponse.getError().getCode());
    }
    boolean hasDurableIds =
        remoteCoordinatorId != null
            && !remoteCoordinatorId.isBlank()
            && remoteFollowupId != null
            && !remoteFollowupId.isBlank();
    return new HandoffResult(
        hasDurableIds,
        hasDurableIds ? "REMOTE_SCHEDULED" : "REMOTE_REJECTED",
        "",
        remoteCoordinatorId,
        remoteFollowupId,
        hasDurableIds ? "" : ERROR_REMOTE_RESPONSE_INVALID);
  }

  private boolean isEpochAdvanced(ScriptWorkItem workItem) {
    if (workItem.getGameInstanceId() == null || workItem.getGameInstanceId().isBlank()) {
      return false;
    }
    AutomationAdmissionStateService.AdmissionStateSummary state =
        automationAdmissionStateService.getState(
            workItem.getTenantId(), workItem.getGameInstanceId(), workItem.getRegionId());
    return state.admissionEpoch() != workItem.getAdmissionEpoch();
  }

  private void cancelForRollbackEpochAdvance(
      ScriptWorkItem workItem, EmittedCommand command, String dispatchId, Instant now) {
    workItem.setStatus(STATUS_CANCELED);
    workItem.setCancelReason("rollback_epoch_advanced");
    workItem.setUpdatedAt(now);
    workItemRepository.save(workItem);
    rolloutProjectionService.refreshForWorkItem(workItem);
    appendHandoffEvent(
        workItem,
        command,
        dispatchId,
        new HandoffResult(false, "rollback_epoch_advanced", "", "", "", "rollback_epoch_advanced"),
        now);
    updateAudit(workItem, "HANDOFF", "canceled", "rollback_epoch_advanced", now);
  }

  private boolean isRuntimeRegionScopeAdvanced(ScriptWorkItem workItem) {
    if (workItem.getTenantId() == null
        || workItem.getTenantId().isBlank()
        || workItem.getGameInstanceId() == null
        || workItem.getGameInstanceId().isBlank()) {
      return false;
    }
    GetGameInstanceRuntimeStateResponse runtimeState =
        gameSessionClient.getGameInstanceRuntimeState(
            workItem.getTenantId(), workItem.getGameInstanceId(), workItem.getRegionId());
    if (runtimeState == null
        || runtimeState.hasError()
        || !runtimeState.hasRuntimeState()
        || runtimeState.getRuntimeState().getRegionId().isBlank()
        || runtimeState.getRuntimeState().getRegionEpoch() <= 0) {
      return false;
    }
    return !runtimeState.getRuntimeState().getRegionId().equals(normalize(workItem.getRegionId()))
        || runtimeState.getRuntimeState().getRegionEpoch() != workItem.getRegionEpoch();
  }

  private void cancelForRuntimeRegionScopeAdvance(
      ScriptWorkItem workItem, EmittedCommand command, String dispatchId, Instant now) {
    workItem.setStatus(STATUS_CANCELED);
    workItem.setCancelReason(REASON_RUNTIME_REGION_SCOPE_ADVANCED);
    workItem.setUpdatedAt(now);
    workItemRepository.save(workItem);
    rolloutProjectionService.refreshForWorkItem(workItem);
    appendHandoffEvent(
        workItem,
        command,
        dispatchId,
        new HandoffResult(
            false,
            REASON_RUNTIME_REGION_SCOPE_ADVANCED,
            "",
            "",
            "",
            REASON_RUNTIME_REGION_SCOPE_ADVANCED),
        now);
    updateAudit(workItem, "HANDOFF", "canceled", REASON_RUNTIME_REGION_SCOPE_ADVANCED, now);
  }

  private EnqueueAutomationCommandIfAbsentRequest toRequest(
      ScriptWorkItem workItem, EmittedCommand command, String dispatchId) {
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.normalize(
            workItem.getWorldSlug(), workItem.getRealmSlug(), workItem.getPointerVersion());
    return EnqueueAutomationCommandIfAbsentRequest.newBuilder()
        .setTenantId(workItem.getTenantId())
        .setGameInstanceId(workItem.getGameInstanceId())
        .setRegionId(workItem.getRegionId())
        .setRegionEpoch(workItem.getRegionEpoch())
        .setDueTickId(command.dueTickId())
        .setAutomationDispatchId(dispatchId)
        .setAutomationWorkItemId(workItem.getId().toString())
        .setScriptId(workItem.getScriptId())
        .setScriptPatchVersion(workItem.getScriptPatchVersion())
        .setPluginId(normalize(workItem.getPluginId()))
        .setPluginVersionId(normalize(workItem.getPluginVersionId()))
        .setPlayableStateScope(toPlayableStateScope(workItem.getPlayableStateScope()))
        .setWorldSlug(routingBundle.worldSlug())
        .setRealmSlug(routingBundle.realmSlug())
        .setPointerVersion(routingBundle.pointerVersion())
        .setOriginSourceKind(normalize(workItem.getSourceKind()))
        .setOriginSourceState(normalize(workItem.getSourceState()))
        .setOriginSourceOrdinal(zeroIfNull(workItem.getSourceOrdinal()))
        .setOriginSourceDueTickId(zeroIfNull(workItem.getSourceDueTickId()))
        .setOriginSourceDueAtMs(zeroIfNull(workItem.getSourceDueAtMs()))
        .setTargetEntityId(command.targetEntityId())
        .setCommand(command.commandText())
        .setRequiresSoloTick(command.requiresSoloTick())
        .build();
  }

  private ScheduleRemoteFollowupRequest toRemoteScheduleRequest(
      ScriptWorkItem workItem, EmittedCommand command, String dispatchId) {
    long targetDueTickId = command.dueTickId() > 0 ? command.dueTickId() : 0L;
    long originDeadlineTickId = originDeadlineTickId(workItem, command);
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.normalize(
            workItem.getWorldSlug(), workItem.getRealmSlug(), workItem.getPointerVersion());
    return ScheduleRemoteFollowupRequest.newBuilder()
        .setTenantId(workItem.getTenantId())
        .setCommandId(dispatchId)
        .setCoordinatorId("remote-coordinator:" + dispatchId)
        .setOriginGameInstanceId(workItem.getGameInstanceId())
        .setOriginRegionId(workItem.getRegionId())
        .setOriginRegionEpoch(workItem.getRegionEpoch())
        .setTargetGameInstanceId(normalize(command.targetGameInstanceId()))
        .setTargetRegionId(normalize(command.targetRegionId()))
        .setTargetRegionEpoch(zeroIfNull(command.targetRegionEpoch()))
        .setTargetDueTickId(targetDueTickId)
        .setOriginDeadlineRegionEpoch(workItem.getRegionEpoch())
        .setOriginDeadlineTickId(originDeadlineTickId)
        .setLateResultPolicy(REMOTE_LATE_RESULT_POLICY)
        .setFollowupId("remote-followup:" + dispatchId)
        .setEffectKey("remote-followup:" + dispatchId)
        .setTargetEntityId(command.targetEntityId())
        .setPayloadKind("enqueue_automation_command")
        .setRequestedCommand(command.commandText())
        .setRequiresSoloTick(command.requiresSoloTick())
        .setPlayableStateScope(toPlayableStateScope(workItem.getPlayableStateScope()))
        .setWorldSlug(routingBundle.worldSlug())
        .setRealmSlug(routingBundle.realmSlug())
        .setPointerVersion(routingBundle.parsedPointerVersion())
        .setScriptPatchVersion(workItem.getScriptPatchVersion())
        .setPluginId(normalize(workItem.getPluginId()))
        .setPluginVersionId(normalize(workItem.getPluginVersionId()))
        .setAutomationDispatchId(dispatchId)
        .setAutomationWorkItemId(workItem.getId().toString())
        .setScriptId(workItem.getScriptId())
        .setOriginSourceKind(normalize(workItem.getSourceKind()))
        .setOriginSourceState(normalize(workItem.getSourceState()))
        .setOriginSourceOrdinal(zeroIfNull(workItem.getSourceOrdinal()))
        .setOriginSourceDueTickId(zeroIfNull(workItem.getSourceDueTickId()))
        .setOriginSourceDueAtMs(zeroIfNull(workItem.getSourceDueAtMs()))
        .build();
  }

  private void applyOutcome(
      ScriptWorkItem workItem,
      EmittedCommand command,
      String dispatchId,
      HandoffResult result,
      Instant now) {
    String outcome = result.outcome().toLowerCase(Locale.ROOT);
    String reason = handoffReason(result);
    appendHandoffEvent(workItem, command, dispatchId, result, now);
    if (result.accepted()) {
      workItem.setStatus(STATUS_HANDED_OFF);
      workItem.setUpdatedAt(now);
      workItemRepository.save(workItem);
      rolloutProjectionService.refreshForWorkItem(workItem);
      updateAudit(workItem, "HANDOFF", outcome, "game_session_accepted", now);
      return;
    }
    workItem.setStatus(STATUS_DEAD_LETTERED);
    workItem.setCancelReason(result.errorCode().isBlank() ? result.outcome() : result.errorCode());
    workItem.setUpdatedAt(now);
    workItemRepository.save(workItem);
    rolloutProjectionService.refreshForWorkItem(workItem);
    updateAudit(workItem, "HANDOFF", "handoff_failed", reason, now);
  }

  private void updateAudit(
      ScriptWorkItem workItem, String stage, String outcome, String reason, Instant now) {
    auditRepository
        .findByWorkItemId(workItem.getId())
        .ifPresent(
            audit -> {
              audit.setFinalStage(stage);
              audit.setFinalOutcome(outcome);
              audit.setFinalReason(reason);
              audit.setUpdatedAt(now);
              auditRepository.save(audit);
            });
  }

  private static String dispatchId(ScriptWorkItem workItem, int ordinal) {
    return "workItem:" + workItem.getId() + "#" + ordinal;
  }

  private void appendHandoffEvent(
      ScriptWorkItem workItem,
      EmittedCommand command,
      String dispatchId,
      HandoffResult result,
      Instant now) {
    String outcome = result.outcome().toLowerCase(Locale.ROOT);
    String reason = handoffReason(result);
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.normalize(
            workItem.getWorldSlug(), workItem.getRealmSlug(), workItem.getPointerVersion());
    ScriptHandoffEvent event = new ScriptHandoffEvent();
    event.setEventId("she-" + UUID.randomUUID());
    event.setTenantId(workItem.getTenantId());
    event.setGameInstanceId(workItem.getGameInstanceId());
    event.setScriptPatchVersion(workItem.getScriptPatchVersion());
    event.setScriptId(workItem.getScriptId());
    event.setPluginId(normalize(workItem.getPluginId()));
    event.setPluginVersionId(normalize(workItem.getPluginVersionId()));
    event.setWorkItemId(workItem.getId());
    event.setCommandOrdinal(command.ordinal());
    event.setAutomationDispatchId(dispatchId);
    event.setGameSessionCommandId(normalize(result.commandId()));
    event.setRemoteCoordinatorId(normalize(result.remoteCoordinatorId()));
    event.setRemoteFollowupId(normalize(result.remoteFollowupId()));
    event.setTargetEntityId(command.targetEntityId());
    event.setTargetGameInstanceId(normalize(command.targetGameInstanceId()));
    event.setTargetRegionId(normalize(command.targetRegionId()));
    event.setTargetRegionEpoch(zeroIfNull(command.targetRegionEpoch()));
    event.setPlayableStateScope(normalize(workItem.getPlayableStateScope()));
    event.setWorldSlug(routingBundle.worldSlug());
    event.setRealmSlug(routingBundle.realmSlug());
    event.setPointerVersion(routingBundle.pointerVersion());
    event.setSourceKind(normalize(workItem.getSourceKind()));
    event.setSourceState(normalize(workItem.getSourceState()));
    event.setSourceOrdinal(workItem.getSourceOrdinal());
    event.setSourceDueTickId(workItem.getSourceDueTickId());
    event.setSourceDueAtMs(workItem.getSourceDueAtMs());
    event.setEmittedCommandText(command.commandText());
    event.setHandoffOutcome(outcome);
    event.setHandoffReason(reason);
    event.setObservedAt(now);
    handoffEventRepository.save(event);
  }

  private static String handoffReason(HandoffResult result) {
    if (result.accepted()) {
      return result.remoteFollowupId().isBlank()
          ? "game_session_accepted"
          : "remote_followup_scheduled";
    }
    return result.errorCode().isBlank() ? result.outcome() : result.errorCode();
  }

  private static boolean requiresRemoteHandoff(ScriptWorkItem workItem, EmittedCommand command) {
    return !normalize(workItem.getGameInstanceId())
            .equals(normalize(command.targetGameInstanceId()))
        || !normalize(workItem.getRegionId()).equals(normalize(command.targetRegionId()))
        || workItem.getRegionEpoch() != zeroIfNull(command.targetRegionEpoch());
  }

  private static long originDeadlineTickId(ScriptWorkItem workItem, EmittedCommand command) {
    if (command.dueTickId() > 0) {
      return command.dueTickId() + 1;
    }
    if (workItem.getSourceDueTickId() != null && workItem.getSourceDueTickId() > 0) {
      return workItem.getSourceDueTickId() + 1;
    }
    return Long.MAX_VALUE;
  }

  private static String normalize(String value) {
    return value == null ? "" : value;
  }

  private static long zeroIfNull(Long value) {
    return value == null ? 0L : value;
  }

  private static PlayableStateScope toPlayableStateScope(String playableStateScope) {
    return switch (normalize(playableStateScope)) {
      case "SHARED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
      case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
      default -> PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED;
    };
  }

  private static void requireWorkItem(ScriptWorkItem workItem) {
    if (workItem == null || workItem.getId() == null) {
      throw new IllegalArgumentException("persisted work_item is required");
    }
  }

  private static void requireCommand(EmittedCommand command) {
    if (command == null || command.commandText() == null || command.commandText().isBlank()) {
      throw new IllegalArgumentException("command_text is required");
    }
    if (command.targetEntityId() == null || command.targetEntityId().isBlank()) {
      throw new IllegalArgumentException("target_entity_id is required");
    }
    if (command.targetGameInstanceId() == null || command.targetGameInstanceId().isBlank()) {
      throw new IllegalArgumentException("target_game_instance_id is required");
    }
    if (command.targetRegionId() == null || command.targetRegionId().isBlank()) {
      throw new IllegalArgumentException("target_region_id is required");
    }
    if (zeroIfNull(command.targetRegionEpoch()) <= 0) {
      throw new IllegalArgumentException("target_region_epoch must be positive");
    }
    if (command.ordinal() < 0) {
      throw new IllegalArgumentException("command ordinal must be non-negative");
    }
  }
}
