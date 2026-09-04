package net.firedevops.firemud.automationscripting.service.impl;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.firedevops.firemud.automationscripting.client.GameDesignControlPlaneClient;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.config.ScriptRuntimeProperties;
import net.firedevops.firemud.automationscripting.service.AutomationAdmissionStateService;
import net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchPinProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleInstanceService;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPatchRequest;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPatchResponse;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPluginVersionRequest;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPluginVersionResponse;
import net.firedevops.firemud.automationscripting.v1.GetAutomationDrainStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetAutomationDrainStatusResponse;
import net.firedevops.firemud.automationscripting.v1.GetAutomationPinConvergenceRequest;
import net.firedevops.firemud.automationscripting.v1.GetAutomationPinConvergenceResponse;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchInstanceRolloutStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchInstanceRolloutStatusResponse;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchStatusResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptDeadLettersRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptDeadLettersResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptHandoffEventsRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptHandoffEventsResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchInstanceRolloutEventsRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchInstanceRolloutEventsResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchInstanceRolloutsRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchInstanceRolloutsResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchStatusesRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchStatusesResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptScheduleInstancesRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptScheduleInstancesResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptTimerAuditEventsRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptTimerAuditEventsResponse;
import net.firedevops.firemud.automationscripting.v1.PluginPublicationLink;
import net.firedevops.firemud.automationscripting.v1.ReplayDeadLetteredWorkItemsRequest;
import net.firedevops.firemud.automationscripting.v1.ReplayDeadLetteredWorkItemsResponse;
import net.firedevops.firemud.automationscripting.v1.ScriptDeadLetterEntry;
import net.firedevops.firemud.automationscripting.v1.ScriptHandoffEventEntry;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchInstanceRolloutEntry;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchInstanceRolloutEventEntry;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchPublicationLink;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchStatusEntry;
import net.firedevops.firemud.automationscripting.v1.ScriptScheduleInstanceEntry;
import net.firedevops.firemud.automationscripting.v1.ScriptTimerAuditEventEntry;
import net.firedevops.firemud.automationscripting.v1.SetAutomationAdmissionModeRequest;
import net.firedevops.firemud.automationscripting.v1.SetAutomationAdmissionModeResponse;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import net.firedevops.firemud.gamesession.v1.GetGameplayCommandStatusResponse;
import org.springframework.stereotype.Service;

@Service
final class AutomationPatchControlPlaneService {
  private final ScriptWorkItemService workItemService;
  private final AutomationAdmissionStateService automationAdmissionStateService;
  private final ScriptPatchPinProjectionService scriptPatchPinProjectionService;
  private final ScriptScheduleInstanceService scriptScheduleInstanceService;
  private final GameDesignControlPlaneClient gameDesignControlPlaneClient;
  private final GameSessionControlPlaneClient gameSessionControlPlaneClient;
  private final ScriptRuntimeProperties runtimeProperties;
  private final TemporalScriptPatchReadinessWorkflowMetadataResolver workflowMetadataResolver;

  AutomationPatchControlPlaneService(
      ScriptWorkItemService workItemService,
      AutomationAdmissionStateService automationAdmissionStateService,
      ScriptPatchPinProjectionService scriptPatchPinProjectionService,
      ScriptScheduleInstanceService scriptScheduleInstanceService,
      GameDesignControlPlaneClient gameDesignControlPlaneClient,
      GameSessionControlPlaneClient gameSessionControlPlaneClient,
      ScriptRuntimeProperties runtimeProperties,
      TemporalScriptPatchReadinessWorkflowMetadataResolver workflowMetadataResolver) {
    this.workItemService = workItemService;
    this.automationAdmissionStateService = automationAdmissionStateService;
    this.scriptPatchPinProjectionService = scriptPatchPinProjectionService;
    this.scriptScheduleInstanceService = scriptScheduleInstanceService;
    this.gameDesignControlPlaneClient = gameDesignControlPlaneClient;
    this.gameSessionControlPlaneClient = gameSessionControlPlaneClient;
    this.runtimeProperties = runtimeProperties;
    this.workflowMetadataResolver = workflowMetadataResolver;
  }

  GetScriptPatchStatusResponse getScriptPatchStatus(GetScriptPatchStatusRequest request) {
    GetScriptPatchStatusResponse.Builder response = GetScriptPatchStatusResponse.newBuilder();
    workItemService
        .getPatchStatus(request.getTenantId(), request.getScriptPatchVersion())
        .ifPresentOrElse(
            summary -> {
              var workflowMetadata =
                  workflowMetadataResolver.resolve(
                      request.getTenantId(), request.getScriptPatchVersion());
              response
                  .setStatus(summary.status())
                  .setStatusReason(summary.statusReason())
                  .setSupersededByScriptPatchVersion(summary.supersededByScriptPatchVersion())
                  .setLastChangedAtMs(summary.lastChangedAtMs())
                  .setBaseVersionId(summary.baseVersionId())
                  .setAbilitySchemaDigest(summary.abilitySchemaDigest())
                  .setPublication(toProto(summary.publication()))
                  .setWorkflowId(workflowMetadata.workflowId())
                  .setWorkflowRunId(workflowMetadata.workflowRunId())
                  .setWorkflowStatus(workflowMetadata.workflowStatus())
                  .setWorkflowFamily(workflowMetadata.workflowFamily());
            },
            () ->
                response.setError(
                    AutomationControlPlaneSupport.notFound(
                        "GetScriptPatchStatus", "script_patch_not_found")));
    return response.build();
  }

  ListScriptPatchStatusesResponse listScriptPatchStatuses(ListScriptPatchStatusesRequest request) {
    ListScriptPatchStatusesResponse.Builder response = ListScriptPatchStatusesResponse.newBuilder();
    workItemService
        .listPatchStatuses(
            request.getTenantId(),
            request.getStatus(),
            request.getChangedAfterMs(),
            request.getChangedBeforeMs())
        .stream()
        .map(
            summary ->
                toProto(
                    summary,
                    workflowMetadataResolver.resolve(
                        request.getTenantId(), summary.scriptPatchVersion())))
        .forEach(response::addPatches);
    return response.build();
  }

  SetAutomationAdmissionModeResponse setAutomationAdmissionMode(
      SetAutomationAdmissionModeRequest request) {
    AutomationAdmissionStateService.AdmissionStateSummary summary =
        automationAdmissionStateService.setMode(
            new AutomationAdmissionStateService.SetAdmissionModeCommand(
                request.getTenantId(),
                request.getGameInstanceId(),
                request.getRegionId(),
                AutomationControlPlaneSupport.requireMode(request.getMode()),
                request.getControlPlaneRequestId(),
                request.getActorPrincipal(),
                request.getReason()));
    return SetAutomationAdmissionModeResponse.newBuilder()
        .setTenantId(summary.tenantId())
        .setGameInstanceId(summary.gameInstanceId())
        .setRegionId(summary.regionId())
        .setMode(AutomationControlPlaneSupport.toProtoMode(summary.mode()))
        .setAdmissionEpoch(summary.admissionEpoch())
        .setUpdatedAtMs(summary.updatedAtMs())
        .build();
  }

  GetAutomationDrainStatusResponse getAutomationDrainStatus(
      GetAutomationDrainStatusRequest request) {
    ScriptWorkItemService.AutomationDrainStatusSummary summary =
        workItemService.getAutomationDrainStatus(
            request.getTenantId(), request.getGameInstanceId(), request.getRegionId());
    return GetAutomationDrainStatusResponse.newBuilder()
        .setTenantId(summary.tenantId())
        .setGameInstanceId(summary.gameInstanceId())
        .setRegionId(summary.regionId())
        .setAdmissionMode(AutomationControlPlaneSupport.toProtoMode(summary.admissionMode()))
        .setAdmissionEpoch(summary.admissionEpoch())
        .setActiveExecutionCount(summary.activeExecutionCount())
        .setOldestActiveExecutionStartedAtMs(summary.oldestActiveExecutionStartedAtMs())
        .setPendingCancelableWorkItemCount(summary.pendingCancelableWorkItemCount())
        .setObservedAtMs(summary.observedAtMs())
        .setIsStale(isDrainStatusStale(summary.observedAtMs()))
        .build();
  }

  GetAutomationPinConvergenceResponse getAutomationPinConvergence(
      GetAutomationPinConvergenceRequest request) {
    GetAutomationPinConvergenceResponse.Builder response =
        GetAutomationPinConvergenceResponse.newBuilder();
    ScriptPatchPinProjectionService.PinConvergenceLookup lookup =
        scriptPatchPinProjectionService.getPinConvergence(
            request.getTenantId(), request.getGameInstanceId());
    if (lookup.summary().isPresent()) {
      ScriptPatchPinProjectionService.PinConvergenceSummary summary = lookup.summary().get();
      response
          .setTenantId(summary.tenantId())
          .setGameInstanceId(summary.gameInstanceId())
          .setObservedPinnedScriptPatchVersion(summary.observedPinnedScriptPatchVersion())
          .setObservedScriptPinEpoch(summary.scriptPinEpoch())
          .setLastObservedControlPlaneRequestId(summary.lastObservedControlPlaneRequestId())
          .setObservedAtMs(summary.observedAtMs())
          .setProjectionAsOfMs(summary.projectionAsOfMs())
          .setProjectionLagMs(summary.projectionLagMs())
          .setIsProjectionStale(summary.projectionStale())
          .setRegionId(summary.runtimeRegionId())
          .setRegionEpoch(summary.runtimeRegionEpoch())
          .setWorldSlug(summary.worldSlug())
          .setRealmSlug(summary.realmSlug())
          .setPointerVersion(summary.pointerVersion())
          .setPublication(
              scriptPatchPublicationLink(
                  request.getTenantId(), summary.observedPinnedScriptPatchVersion()));
    } else if (!lookup.errorCode().isBlank()) {
      response.setError(
          AutomationControlPlaneSupport.invalidArgument(
              lookup.errorCode() + ": " + lookup.errorMessage()));
    } else {
      response.setError(
          AutomationControlPlaneSupport.notFound(
              "GetAutomationPinConvergence", "pin_projection_not_found"));
    }
    return response.build();
  }

  GetScriptPatchInstanceRolloutStatusResponse getScriptPatchInstanceRolloutStatus(
      GetScriptPatchInstanceRolloutStatusRequest request) {
    requireCoherentScriptPinFilter(
        request.getScriptPinEpoch(), request.getLastObservedControlPlaneRequestId());
    GetScriptPatchInstanceRolloutStatusResponse.Builder response =
        GetScriptPatchInstanceRolloutStatusResponse.newBuilder();
    var rollout =
        request.getScriptPinEpoch() > 0
            ? workItemService.getPatchInstanceRolloutStatus(
                request.getTenantId(),
                request.getGameInstanceId(),
                request.getScriptPatchVersion(),
                request.getScriptPinEpoch(),
                request.getLastObservedControlPlaneRequestId())
            : workItemService.getPatchInstanceRolloutStatus(
                request.getTenantId(),
                request.getGameInstanceId(),
                request.getScriptPatchVersion(),
                0L,
                null);
    rollout.ifPresentOrElse(
        summary ->
            response
                .setTenantId(summary.tenantId())
                .setGameInstanceId(summary.gameInstanceId())
                .setScriptPatchVersion(summary.scriptPatchVersion())
                .setScriptPinEpoch(summary.scriptPinEpoch())
                .setLastObservedControlPlaneRequestId(summary.lastObservedControlPlaneRequestId())
                .setRolloutStatus(summary.rolloutStatus())
                .setStatusReason(summary.statusReason())
                .setLastChangedAtMs(summary.lastChangedAtMs())
                .setProjectionAsOfMs(summary.projectionAsOfMs())
                .setProjectionLagMs(summary.projectionLagMs())
                .setIsProjectionStale(summary.projectionStale())
                .setPublication(toProto(summary.publication())),
        () ->
            response.setError(
                AutomationControlPlaneSupport.notFound(
                    "GetScriptPatchInstanceRolloutStatus",
                    "script_patch_instance_rollout_not_found")));
    return response.build();
  }

  ListScriptScheduleInstancesResponse listScriptScheduleInstances(
      ListScriptScheduleInstancesRequest request) {
    List<ScriptScheduleInstanceService.ScheduleInstanceSummary> summaries =
        scriptScheduleInstanceService.listInstances(
            request.getTenantId(),
            request.getGameInstanceId(),
            request.getScriptPatchVersion(),
            request.getLimit());
    Map<String, CurrentRuntimeScope> currentScopes =
        loadCurrentRuntimeScopes(
            request.getTenantId(),
            summaries,
            ScriptScheduleInstanceService.ScheduleInstanceSummary::gameInstanceId,
            ScriptScheduleInstanceService.ScheduleInstanceSummary::runtimeRegionId);
    ListScriptScheduleInstancesResponse.Builder response =
        ListScriptScheduleInstancesResponse.newBuilder();
    summaries.stream()
        .map(summary -> toProto(summary, currentScopes.get(summary.gameInstanceId())))
        .forEach(response::addSchedules);
    return response.build();
  }

  ListScriptTimerAuditEventsResponse listScriptTimerAuditEvents(
      ListScriptTimerAuditEventsRequest request) {
    requireCoherentScriptPinFilter(
        request.getScriptPinEpoch(), request.getScriptPinControlPlaneRequestId());
    List<ScriptScheduleInstanceService.TimerAuditEventSummary> summaries =
        request.getScriptPinEpoch() > 0
            ? scriptScheduleInstanceService.listTimerAuditEvents(
                request.getTenantId(),
                request.getGameInstanceId(),
                request.getScriptPatchVersion(),
                request.getScriptPinEpoch(),
                request.getScriptPinControlPlaneRequestId(),
                request.getScriptId(),
                request.getEventType(),
                request.getFinalReason(),
                request.getChangedAfterMs(),
                request.getChangedBeforeMs(),
                request.getLimit())
            : scriptScheduleInstanceService.listTimerAuditEvents(
                request.getTenantId(),
                request.getGameInstanceId(),
                request.getScriptPatchVersion(),
                0L,
                null,
                request.getScriptId(),
                request.getEventType(),
                request.getFinalReason(),
                request.getChangedAfterMs(),
                request.getChangedBeforeMs(),
                request.getLimit());
    Map<String, CurrentRuntimeScope> currentScopes =
        loadCurrentRuntimeScopes(
            request.getTenantId(),
            summaries,
            ScriptScheduleInstanceService.TimerAuditEventSummary::gameInstanceId,
            ScriptScheduleInstanceService.TimerAuditEventSummary::regionId);
    ListScriptTimerAuditEventsResponse.Builder response =
        ListScriptTimerAuditEventsResponse.newBuilder();
    summaries.stream()
        .map(summary -> toProto(summary, currentScopes.get(summary.gameInstanceId())))
        .forEach(response::addEvents);
    return response.build();
  }

  ListScriptPatchInstanceRolloutsResponse listScriptPatchInstanceRollouts(
      ListScriptPatchInstanceRolloutsRequest request) {
    requireCoherentScriptPinFilter(
        request.getScriptPinEpoch(), request.getLastObservedControlPlaneRequestId());
    ListScriptPatchInstanceRolloutsResponse.Builder response =
        ListScriptPatchInstanceRolloutsResponse.newBuilder();
    var rollouts =
        request.getScriptPinEpoch() > 0
            ? workItemService.listPatchInstanceRollouts(
                request.getTenantId(),
                request.getGameInstanceId(),
                request.getScriptPatchVersion(),
                request.getScriptPinEpoch(),
                request.getLastObservedControlPlaneRequestId(),
                request.getRolloutStatus(),
                request.getChangedAfterMs(),
                request.getChangedBeforeMs())
            : workItemService.listPatchInstanceRollouts(
                request.getTenantId(),
                request.getGameInstanceId(),
                request.getScriptPatchVersion(),
                0L,
                null,
                request.getRolloutStatus(),
                request.getChangedAfterMs(),
                request.getChangedBeforeMs());
    rollouts.stream()
        .map(AutomationPatchControlPlaneService::toProto)
        .forEach(response::addRollouts);
    return response.build();
  }

  ListScriptPatchInstanceRolloutEventsResponse listScriptPatchInstanceRolloutEvents(
      ListScriptPatchInstanceRolloutEventsRequest request) {
    requireCoherentScriptPinFilter(
        request.getScriptPinEpoch(), request.getLastObservedControlPlaneRequestId());
    ListScriptPatchInstanceRolloutEventsResponse.Builder response =
        ListScriptPatchInstanceRolloutEventsResponse.newBuilder();
    var rolloutEvents =
        request.getScriptPinEpoch() > 0
            ? workItemService.listPatchInstanceRolloutEvents(
                request.getTenantId(),
                request.getGameInstanceId(),
                request.getScriptPatchVersion(),
                request.getScriptPinEpoch(),
                request.getLastObservedControlPlaneRequestId(),
                request.getRolloutStatus(),
                request.getChangedAfterMs(),
                request.getChangedBeforeMs(),
                request.getLimit())
            : workItemService.listPatchInstanceRolloutEvents(
                request.getTenantId(),
                request.getGameInstanceId(),
                request.getScriptPatchVersion(),
                0L,
                null,
                request.getRolloutStatus(),
                request.getChangedAfterMs(),
                request.getChangedBeforeMs(),
                request.getLimit());
    rolloutEvents.stream()
        .map(AutomationPatchControlPlaneService::toProto)
        .forEach(response::addEvents);
    return response.build();
  }

  ListScriptHandoffEventsResponse listScriptHandoffEvents(ListScriptHandoffEventsRequest request) {
    List<ScriptWorkItemService.HandoffEventSummary> summaries =
        workItemService.listHandoffEvents(
            request.getTenantId(),
            request.getGameInstanceId(),
            request.getScriptPatchVersion(),
            request.getWorkItemId(),
            request.getHandoffOutcome(),
            request.getTargetGameInstanceId(),
            request.getTargetRegionId(),
            request.getTargetRegionEpoch(),
            request.getRemoteCoordinatorId(),
            request.getRemoteFollowupId(),
            request.getScriptId(),
            request.getPluginId(),
            request.getAutomationDispatchId(),
            request.getGameSessionCommandId(),
            request.getTargetEntityId(),
            AutomationControlPlaneSupport.normalizePlayableStateScope(
                request.getPlayableStateScope()),
            request.getWorldSlug(),
            request.getRealmSlug(),
            request.getPointerVersion(),
            request.getSourceKind(),
            request.getSourceState(),
            request.getChangedAfterMs(),
            request.getChangedBeforeMs(),
            request.getLimit());
    Map<String, CurrentTargetRuntimeScope> currentScopes =
        loadCurrentTargetRuntimeScopes(request.getTenantId(), summaries);
    Map<String, GameplayCommandStatusView> commandStatuses =
        loadGameplayCommandStatuses(request.getTenantId(), summaries);
    ListScriptHandoffEventsResponse.Builder response = ListScriptHandoffEventsResponse.newBuilder();
    summaries.stream()
        .map(
            summary ->
                toProto(
                    summary,
                    currentScopes.get(summary.targetGameInstanceId()),
                    commandStatuses.get(summary.gameSessionCommandId())))
        .forEach(response::addEvents);
    return response.build();
  }

  ListScriptDeadLettersResponse listScriptDeadLetters(ListScriptDeadLettersRequest request) {
    List<ScriptWorkItemService.DeadLetterSummary> summaries =
        workItemService.listDeadLetters(
            request.getTenantId(),
            request.getGameInstanceId(),
            request.getScriptPatchVersion(),
            request.getLimit());
    Map<String, CurrentRuntimeScope> currentScopes =
        loadCurrentRuntimeScopes(
            request.getTenantId(),
            summaries,
            ScriptWorkItemService.DeadLetterSummary::gameInstanceId,
            ScriptWorkItemService.DeadLetterSummary::regionId);
    ListScriptDeadLettersResponse.Builder response = ListScriptDeadLettersResponse.newBuilder();
    summaries.stream()
        .map(summary -> toProto(summary, currentScopes.get(summary.gameInstanceId())))
        .forEach(response::addDeadLetters);
    return response.build();
  }

  ReplayDeadLetteredWorkItemsResponse replayDeadLetteredWorkItems(
      ReplayDeadLetteredWorkItemsRequest request) {
    ScriptWorkItemService.ReplayResult result =
        workItemService.replayDeadLetters(
            new ScriptWorkItemService.ReplayDeadLettersCommand(
                request.getTenantId(),
                request.getGameInstanceId(),
                request.getRegionId(),
                request.getWorkItemIdsList(),
                request.getScriptPatchVersion(),
                request.getCreatedAfterMs(),
                request.getCreatedBeforeMs(),
                request.getLimit(),
                request.getControlPlaneRequestId(),
                request.getActorPrincipal(),
                request.getReason()));
    return ReplayDeadLetteredWorkItemsResponse.newBuilder()
        .setReplayedCount(result.replayedCount())
        .setRejectedCount(result.rejectedCount())
        .build();
  }

  CancelPendingWorkItemsForPatchResponse cancelPendingWorkItemsForPatch(
      CancelPendingWorkItemsForPatchRequest request) {
    long canceled =
        workItemService.cancelPendingForPatch(
            new ScriptWorkItemService.CancelPendingForPatchCommand(
                request.getTenantId(),
                request.getScriptPatchVersion(),
                request.getGameInstanceId(),
                request.getRegionId(),
                request.getControlPlaneRequestId(),
                request.getActorPrincipal(),
                request.getReason()));
    return CancelPendingWorkItemsForPatchResponse.newBuilder().setCanceledCount(canceled).build();
  }

  CancelPendingWorkItemsForPluginVersionResponse cancelPendingWorkItemsForPluginVersion(
      CancelPendingWorkItemsForPluginVersionRequest request) {
    long canceled =
        workItemService.cancelPendingForPluginVersion(
            new ScriptWorkItemService.CancelPendingForPluginVersionCommand(
                request.getTenantId(),
                request.getPluginId(),
                request.getPluginVersionId(),
                request.getGameInstanceId(),
                request.getRegionId(),
                request.getControlPlaneRequestId(),
                request.getActorPrincipal(),
                request.getReason()));
    return CancelPendingWorkItemsForPluginVersionResponse.newBuilder()
        .setCanceledCount(canceled)
        .build();
  }

  private boolean isDrainStatusStale(long observedAtMs) {
    long ageMs = Instant.now().toEpochMilli() - observedAtMs;
    return ageMs > runtimeProperties.getDrainStatusStaleThresholdMs();
  }

  private boolean isSchedulePinStale(long pinObservedAtMs) {
    if (pinObservedAtMs <= 0) {
      return true;
    }
    long ageMs = Instant.now().toEpochMilli() - pinObservedAtMs;
    return ageMs > runtimeProperties.getPinProjectionStaleThresholdMs();
  }

  private boolean isScheduleRuntimeProgressStale(long lastRuntimeProgressObservedAtMs) {
    if (lastRuntimeProgressObservedAtMs <= 0) {
      return true;
    }
    long ageMs = Instant.now().toEpochMilli() - lastRuntimeProgressObservedAtMs;
    return ageMs > runtimeProperties.getScheduleRuntimeProgressStaleThresholdMs();
  }

  private static ScriptPatchStatusEntry toProto(
      ScriptWorkItemService.PatchStatusSummary summary,
      TemporalScriptPatchReadinessWorkflowMetadataResolver.WorkflowMetadata workflowMetadata) {
    return ScriptPatchStatusEntry.newBuilder()
        .setScriptPatchVersion(summary.scriptPatchVersion())
        .setStatus(summary.status())
        .setStatusReason(summary.statusReason())
        .setSupersededByScriptPatchVersion(summary.supersededByScriptPatchVersion())
        .setLastChangedAtMs(summary.lastChangedAtMs())
        .setBaseVersionId(summary.baseVersionId())
        .setAbilitySchemaDigest(summary.abilitySchemaDigest())
        .setPublication(toProto(summary.publication()))
        .setWorkflowId(workflowMetadata.workflowId())
        .setWorkflowRunId(workflowMetadata.workflowRunId())
        .setWorkflowStatus(workflowMetadata.workflowStatus())
        .setWorkflowFamily(workflowMetadata.workflowFamily())
        .build();
  }

  private static ScriptPatchPublicationLink toProto(
      ScriptWorkItemService.ScriptPatchPublicationLink link) {
    return ScriptPatchPublicationLink.newBuilder()
        .setScriptPatchVersion(link.scriptPatchVersion())
        .setVersionId(link.versionId())
        .setBaseVersionId(link.baseVersionId())
        .setPublicationState(link.publicationState())
        .setLastChangedAtMs(link.lastChangedAtMs())
        .setLookupErrorCode(link.lookupErrorCode())
        .setLookupErrorMessage(link.lookupErrorMessage())
        .build();
  }

  private ScriptPatchPublicationLink scriptPatchPublicationLink(
      String tenantId, String scriptPatchVersion) {
    GetPublishedScriptPatchVersionResponse response =
        gameDesignControlPlaneClient.getPublishedScriptPatchVersion(tenantId, scriptPatchVersion);
    if (response.hasError() && !response.getError().getCode().isBlank()) {
      return ScriptPatchPublicationLink.newBuilder()
          .setScriptPatchVersion(AutomationControlPlaneSupport.normalize(scriptPatchVersion))
          .setVersionId(0L)
          .setBaseVersionId(0L)
          .setPublicationState(VersionLifecycleState.VERSION_LIFECYCLE_STATE_UNSPECIFIED)
          .setLastChangedAtMs(0L)
          .setLookupErrorCode(response.getError().getCode())
          .setLookupErrorMessage(response.getError().getMessage())
          .build();
    }
    return ScriptPatchPublicationLink.newBuilder()
        .setScriptPatchVersion(response.getScriptPatch().getScriptPatchVersion())
        .setVersionId(response.getScriptPatch().getVersionId())
        .setBaseVersionId(response.getScriptPatch().getBaseVersionId())
        .setPublicationState(response.getScriptPatch().getPublicationState())
        .setLastChangedAtMs(response.getScriptPatch().getLastChangedAtMs())
        .build();
  }

  private static PluginPublicationLink toProto(
      PluginRuntimeStateService.PluginPublicationLink link) {
    return PluginPublicationLink.newBuilder()
        .setPluginVersionId(link.pluginVersionId())
        .setPublicationId(link.publicationId())
        .setPublicationState(link.publicationState())
        .setStatusReason(link.statusReason())
        .setLastChangedAtMs(link.lastChangedAtMs())
        .setLookupErrorCode(link.lookupErrorCode())
        .setLookupErrorMessage(link.lookupErrorMessage())
        .build();
  }

  private static ScriptDeadLetterEntry toProto(
      ScriptWorkItemService.DeadLetterSummary summary, CurrentRuntimeScope currentScope) {
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.normalize(
            summary.worldSlug(), summary.realmSlug(), summary.pointerVersion());
    ScriptDeadLetterEntry.Builder builder =
        ScriptDeadLetterEntry.newBuilder()
            .setWorkItemId(summary.workItemId())
            .setTenantId(summary.tenantId())
            .setGameInstanceId(summary.gameInstanceId())
            .setRegionId(summary.regionId())
            .setRegionEpoch(summary.regionEpoch())
            .setEntityId(summary.entityId())
            .setPlayableStateScope(
                AutomationControlPlaneSupport.toPlayableStateScope(summary.playableStateScope()))
            .setWorldSlug(routingBundle.worldSlug())
            .setRealmSlug(routingBundle.realmSlug())
            .setPointerVersion(routingBundle.pointerVersion())
            .setSourceKind(summary.sourceKind())
            .setSourceState(summary.sourceState())
            .setSourceOrdinal(summary.sourceOrdinal())
            .setSourceDueTickId(summary.sourceDueTickId())
            .setSourceDueAtMs(summary.sourceDueAtMs())
            .setScriptId(summary.scriptId())
            .setPluginId(summary.pluginId())
            .setPluginVersionId(summary.pluginVersionId())
            .setEventType(summary.eventType())
            .setScriptPatchVersion(summary.scriptPatchVersion())
            .setScriptPinEpoch(summary.scriptPinEpoch())
            .setScriptPinControlPlaneRequestId(summary.scriptPinControlPlaneRequestId())
            .setScriptEventId(summary.scriptEventId())
            .setStatus(summary.status())
            .setReason(summary.reason())
            .setCreatedAtMs(summary.createdAtMs())
            .setUpdatedAtMs(summary.updatedAtMs())
            .setPublication(toProto(summary.publication()));
    if (summary.pluginPublication() != null) {
      builder.setPluginPublication(toProto(summary.pluginPublication()));
    }
    if (currentScope != null) {
      builder
          .setCurrentRuntimeGameInstanceId(currentScope.gameInstanceId())
          .setCurrentRuntimeRegionId(currentScope.regionId())
          .setCurrentRuntimeRegionEpoch(currentScope.regionEpoch())
          .setCurrentRuntimePlayableStateScope(
              AutomationControlPlaneSupport.toPlayableStateScope(currentScope.playableStateScope()))
          .setCurrentRuntimeWorldSlug(currentScope.worldSlug())
          .setCurrentRuntimeRealmSlug(currentScope.realmSlug())
          .setCurrentRuntimePointerVersion(currentScope.pointerVersion())
          .setIsRoutingBundleStale(
              isRoutingBundleStale(
                  summary.playableStateScope(),
                  summary.worldSlug(),
                  summary.realmSlug(),
                  summary.pointerVersion(),
                  currentScope))
          .setIsRuntimeScopeStale(
              isRuntimeScopeStale(summary.regionId(), summary.regionEpoch(), currentScope));
    }
    return builder.build();
  }

  private static ScriptPatchInstanceRolloutEntry toProto(
      ScriptWorkItemService.PatchInstanceRolloutSummary summary) {
    return ScriptPatchInstanceRolloutEntry.newBuilder()
        .setTenantId(summary.tenantId())
        .setGameInstanceId(summary.gameInstanceId())
        .setScriptPatchVersion(summary.scriptPatchVersion())
        .setScriptPinEpoch(summary.scriptPinEpoch())
        .setLastObservedControlPlaneRequestId(summary.lastObservedControlPlaneRequestId())
        .setRolloutStatus(summary.rolloutStatus())
        .setStatusReason(summary.statusReason())
        .setLastChangedAtMs(summary.lastChangedAtMs())
        .setProjectionAsOfMs(summary.projectionAsOfMs())
        .setProjectionLagMs(summary.projectionLagMs())
        .setIsProjectionStale(summary.projectionStale())
        .setPublication(toProto(summary.publication()))
        .build();
  }

  private static ScriptPatchInstanceRolloutEventEntry toProto(
      ScriptWorkItemService.PatchInstanceRolloutEventSummary summary) {
    return ScriptPatchInstanceRolloutEventEntry.newBuilder()
        .setEventId(summary.eventId())
        .setTenantId(summary.tenantId())
        .setGameInstanceId(summary.gameInstanceId())
        .setScriptPatchVersion(summary.scriptPatchVersion())
        .setScriptPinEpoch(summary.scriptPinEpoch())
        .setLastObservedControlPlaneRequestId(summary.lastObservedControlPlaneRequestId())
        .setRolloutStatus(summary.rolloutStatus())
        .setStatusReason(summary.statusReason())
        .setObservedAtMs(summary.observedAtMs())
        .setProjectionAsOfMs(summary.projectionAsOfMs())
        .setPublication(toProto(summary.publication()))
        .build();
  }

  private ScriptScheduleInstanceEntry toProto(
      ScriptScheduleInstanceService.ScheduleInstanceSummary summary,
      CurrentRuntimeScope currentScope) {
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.normalize(
            summary.worldSlug(), summary.realmSlug(), summary.pointerVersion());
    ScriptScheduleInstanceEntry.Builder builder =
        ScriptScheduleInstanceEntry.newBuilder()
            .setTenantId(summary.tenantId())
            .setGameInstanceId(summary.gameInstanceId())
            .setScriptPatchVersion(summary.scriptPatchVersion())
            .setScriptPinEpoch(summary.scriptPinEpoch())
            .setScriptId(summary.scriptId())
            .setPlayableStateScope(
                AutomationControlPlaneSupport.toPlayableStateScope(summary.playableStateScope()))
            .setWorldSlug(routingBundle.worldSlug())
            .setRealmSlug(routingBundle.realmSlug())
            .setPointerVersion(routingBundle.pointerVersion())
            .setPluginId(summary.pluginId())
            .setPluginVersionId(summary.pluginVersionId())
            .setEventType(summary.eventType())
            .setScheduleDefinitionId(summary.scheduleDefinitionId())
            .setScheduleKind(summary.scheduleKind())
            .setCadenceValue(summary.cadenceValue())
            .setCadenceUnit(summary.cadenceUnit())
            .setPriorityTag(summary.priorityTag())
            .setTargetScopeType(summary.targetScopeType())
            .setTargetScopeId(summary.targetScopeId())
            .setBindingPriority(summary.bindingPriority())
            .setRequiresExclusiveEvent(summary.requiresExclusiveEvent())
            .setMaterializationStatus(summary.materializationStatus())
            .setNextDueAtMs(summary.nextDueAtMs())
            .setNextDueTickId(summary.nextDueTickId())
            .setObservedRuntimeVersionId(summary.observedRuntimeVersionId())
            .setLastObservedControlPlaneRequestId(summary.lastObservedControlPlaneRequestId())
            .setPinObservedAtMs(summary.pinObservedAtMs())
            .setMaterializedAtMs(summary.materializedAtMs())
            .setUpdatedAtMs(summary.updatedAtMs())
            .setRuntimeRegionId(summary.runtimeRegionId())
            .setRuntimeRegionEpoch(summary.runtimeRegionEpoch())
            .setLastObservedTickId(summary.lastObservedTickId())
            .setLastRuntimeProgressObservedAtMs(summary.lastRuntimeProgressObservedAtMs())
            .setIsPinStale(isSchedulePinStale(summary.pinObservedAtMs()))
            .setIsRuntimeProgressStale(
                isScheduleRuntimeProgressStale(summary.lastRuntimeProgressObservedAtMs()))
            .setPublication(toProto(summary.publication()));
    if (summary.pluginPublication() != null) {
      builder.setPluginPublication(toProto(summary.pluginPublication()));
    }
    if (currentScope != null) {
      builder
          .setCurrentRuntimeGameInstanceId(currentScope.gameInstanceId())
          .setCurrentRuntimeRegionId(currentScope.regionId())
          .setCurrentRuntimeRegionEpoch(currentScope.regionEpoch())
          .setCurrentRuntimePlayableStateScope(
              AutomationControlPlaneSupport.toPlayableStateScope(currentScope.playableStateScope()))
          .setCurrentRuntimeWorldSlug(currentScope.worldSlug())
          .setCurrentRuntimeRealmSlug(currentScope.realmSlug())
          .setCurrentRuntimePointerVersion(currentScope.pointerVersion())
          .setIsRoutingBundleStale(
              isRoutingBundleStale(
                  summary.playableStateScope(),
                  summary.worldSlug(),
                  summary.realmSlug(),
                  summary.pointerVersion(),
                  currentScope))
          .setIsRuntimeScopeStale(
              isRuntimeScopeStale(
                  summary.runtimeRegionId(), summary.runtimeRegionEpoch(), currentScope));
    }
    return builder.build();
  }

  private static ScriptTimerAuditEventEntry toProto(
      ScriptScheduleInstanceService.TimerAuditEventSummary summary,
      CurrentRuntimeScope currentScope) {
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.normalize(
            summary.worldSlug(), summary.realmSlug(), summary.pointerVersion());
    ScriptTimerAuditEventEntry.Builder builder =
        ScriptTimerAuditEventEntry.newBuilder()
            .setTenantId(summary.tenantId())
            .setGameInstanceId(summary.gameInstanceId())
            .setRegionId(summary.regionId())
            .setRegionEpoch(summary.regionEpoch())
            .setEntityId(summary.entityId())
            .setPlayableStateScope(
                AutomationControlPlaneSupport.toPlayableStateScope(summary.playableStateScope()))
            .setWorldSlug(routingBundle.worldSlug())
            .setRealmSlug(routingBundle.realmSlug())
            .setPointerVersion(routingBundle.pointerVersion())
            .setScriptId(summary.scriptId())
            .setPluginId(summary.pluginId())
            .setPluginVersionId(summary.pluginVersionId())
            .setEventType(summary.eventType())
            .setScriptPatchVersion(summary.scriptPatchVersion())
            .setScriptPinEpoch(summary.scriptPinEpoch())
            .setScriptPinControlPlaneRequestId(summary.scriptPinControlPlaneRequestId())
            .setScriptEventId(summary.scriptEventId())
            .setTriggerMode(AutomationControlPlaneSupport.toTriggerMode(summary.triggerMode()))
            .setSourceState(summary.sourceState())
            .setSourceOrdinal(summary.sourceOrdinal())
            .setSourceDueTickId(summary.sourceDueTickId())
            .setSourceDueAtMs(summary.sourceDueAtMs())
            .setFinalStage(summary.finalStage())
            .setFinalOutcome(summary.finalOutcome())
            .setFinalReason(summary.finalReason())
            .setCreatedAtMs(summary.createdAtMs())
            .setUpdatedAtMs(summary.updatedAtMs())
            .setPublication(toProto(summary.publication()));
    if (summary.pluginPublication() != null) {
      builder.setPluginPublication(toProto(summary.pluginPublication()));
    }
    if (summary.workItemId() > 0) {
      builder.setWorkItemId(Long.toString(summary.workItemId()));
    }
    if (currentScope != null) {
      builder
          .setCurrentRuntimeGameInstanceId(currentScope.gameInstanceId())
          .setCurrentRuntimeRegionId(currentScope.regionId())
          .setCurrentRuntimeRegionEpoch(currentScope.regionEpoch())
          .setCurrentRuntimePlayableStateScope(
              AutomationControlPlaneSupport.toPlayableStateScope(currentScope.playableStateScope()))
          .setCurrentRuntimeWorldSlug(currentScope.worldSlug())
          .setCurrentRuntimeRealmSlug(currentScope.realmSlug())
          .setCurrentRuntimePointerVersion(currentScope.pointerVersion())
          .setIsRoutingBundleStale(
              isRoutingBundleStale(
                  summary.playableStateScope(),
                  summary.worldSlug(),
                  summary.realmSlug(),
                  summary.pointerVersion(),
                  currentScope))
          .setIsRuntimeScopeStale(
              isRuntimeScopeStale(summary.regionId(), summary.regionEpoch(), currentScope));
    }
    return builder.build();
  }

  private static ScriptHandoffEventEntry toProto(
      ScriptWorkItemService.HandoffEventSummary summary,
      CurrentTargetRuntimeScope currentScope,
      GameplayCommandStatusView commandStatus) {
    RoutingBundleSupport.RoutingBundle routingBundle =
        RoutingBundleSupport.normalize(
            summary.worldSlug(), summary.realmSlug(), summary.pointerVersion());
    ScriptHandoffEventEntry.Builder builder =
        ScriptHandoffEventEntry.newBuilder()
            .setEventId(summary.eventId())
            .setTenantId(summary.tenantId())
            .setGameInstanceId(summary.gameInstanceId())
            .setScriptPatchVersion(summary.scriptPatchVersion())
            .setScriptPinEpoch(summary.scriptPinEpoch())
            .setScriptPinControlPlaneRequestId(summary.scriptPinControlPlaneRequestId())
            .setScriptId(summary.scriptId())
            .setPluginId(summary.pluginId())
            .setPluginVersionId(summary.pluginVersionId())
            .setWorkItemId(summary.workItemId())
            .setCommandOrdinal(summary.commandOrdinal())
            .setAutomationDispatchId(summary.automationDispatchId())
            .setGameSessionCommandId(summary.gameSessionCommandId())
            .setTargetGameInstanceId(summary.targetGameInstanceId())
            .setTargetRegionId(summary.targetRegionId())
            .setTargetRegionEpoch(summary.targetRegionEpoch())
            .setRemoteCoordinatorId(summary.remoteCoordinatorId())
            .setRemoteFollowupId(summary.remoteFollowupId())
            .setTargetEntityId(summary.targetEntityId())
            .setPlayableStateScope(
                AutomationControlPlaneSupport.toPlayableStateScope(summary.playableStateScope()))
            .setWorldSlug(routingBundle.worldSlug())
            .setRealmSlug(routingBundle.realmSlug())
            .setPointerVersion(routingBundle.pointerVersion())
            .setSourceKind(summary.sourceKind())
            .setSourceState(summary.sourceState())
            .setSourceOrdinal(summary.sourceOrdinal())
            .setSourceDueTickId(summary.sourceDueTickId())
            .setSourceDueAtMs(summary.sourceDueAtMs())
            .setEmittedCommandText(summary.emittedCommandText())
            .setHandoffOutcome(summary.handoffOutcome())
            .setHandoffReason(summary.handoffReason())
            .setObservedAtMs(summary.observedAtMs())
            .setPublication(toProto(summary.publication()));
    if (summary.pluginPublication() != null) {
      builder.setPluginPublication(toProto(summary.pluginPublication()));
    }
    if (currentScope != null) {
      builder
          .setCurrentTargetRuntimeGameInstanceId(currentScope.gameInstanceId())
          .setCurrentTargetRuntimeRegionId(currentScope.regionId())
          .setCurrentTargetRuntimeRegionEpoch(currentScope.regionEpoch())
          .setCurrentTargetRuntimePlayableStateScope(
              AutomationControlPlaneSupport.toPlayableStateScope(currentScope.playableStateScope()))
          .setCurrentTargetRuntimeWorldSlug(currentScope.worldSlug())
          .setCurrentTargetRuntimeRealmSlug(currentScope.realmSlug())
          .setCurrentTargetRuntimePointerVersion(currentScope.pointerVersion())
          .setIsTargetRoutingBundleStale(isTargetRoutingBundleStale(summary, currentScope))
          .setIsTargetRuntimeScopeStale(isTargetRuntimeScopeStale(summary, currentScope));
    }
    if (commandStatus != null) {
      builder
          .setGameplayCommandExecutionOutcome(commandStatus.executionOutcome())
          .setGameplayCommandGameplayResult(commandStatus.gameplayResult())
          .setGameplayCommandFailureCode(commandStatus.failureCode())
          .setGameplayCommandFailureMessage(commandStatus.failureMessage())
          .setGameplayRemoteState(commandStatus.remoteState())
          .setGameplayRemoteTargetCommandExecutionOutcome(
              commandStatus.remoteTargetCommandExecutionOutcome())
          .setGameplayRemoteTargetCommandGameplayResult(
              commandStatus.remoteTargetCommandGameplayResult());
    }
    return builder.build();
  }

  private Map<String, CurrentTargetRuntimeScope> loadCurrentTargetRuntimeScopes(
      String tenantId, List<ScriptWorkItemService.HandoffEventSummary> summaries) {
    Map<String, CurrentTargetRuntimeScope> scopes = new LinkedHashMap<>();
    for (ScriptWorkItemService.HandoffEventSummary summary : summaries) {
      String targetGameInstanceId =
          AutomationControlPlaneSupport.emptyIfNull(summary.targetGameInstanceId());
      if (targetGameInstanceId.isBlank() || scopes.containsKey(targetGameInstanceId)) {
        continue;
      }
      GetGameInstanceRuntimeStateResponse runtime =
          gameSessionControlPlaneClient.getGameInstanceRuntimeState(
              tenantId,
              targetGameInstanceId,
              AutomationControlPlaneSupport.emptyIfNull(summary.targetRegionId()));
      if (runtime == null
          || runtime.hasError()
          || AutomationControlPlaneSupport.emptyIfNull(
                  runtime.getRuntimeState().getGameInstanceId())
              .isBlank()) {
        continue;
      }
      RoutingBundleSupport.RoutingBundle routingBundle =
          RoutingBundleSupport.fromRuntimeState(runtime.getRuntimeState());
      scopes.put(
          targetGameInstanceId,
          new CurrentTargetRuntimeScope(
              AutomationControlPlaneSupport.emptyIfNull(
                  runtime.getRuntimeState().getGameInstanceId()),
              AutomationControlPlaneSupport.emptyIfNull(runtime.getRuntimeState().getRegionId()),
              runtime.getRuntimeState().getRegionEpoch(),
              AutomationControlPlaneSupport.normalizePlayableStateScope(
                  runtime.getRuntimeState().getPlayableStateScope()),
              routingBundle.worldSlug(),
              routingBundle.realmSlug(),
              routingBundle.pointerVersion()));
    }
    return scopes;
  }

  private <T> Map<String, CurrentRuntimeScope> loadCurrentRuntimeScopes(
      String tenantId,
      List<T> summaries,
      Function<T, String> gameInstanceIdExtractor,
      Function<T, String> preferredRegionIdExtractor) {
    Map<String, CurrentRuntimeScope> scopes = new LinkedHashMap<>();
    for (T summary : summaries) {
      String gameInstanceId =
          AutomationControlPlaneSupport.emptyIfNull(gameInstanceIdExtractor.apply(summary));
      if (gameInstanceId.isBlank() || scopes.containsKey(gameInstanceId)) {
        continue;
      }
      GetGameInstanceRuntimeStateResponse runtime =
          gameSessionControlPlaneClient.getGameInstanceRuntimeState(
              tenantId,
              gameInstanceId,
              AutomationControlPlaneSupport.emptyIfNull(preferredRegionIdExtractor.apply(summary)));
      if (runtime == null
          || runtime.hasError()
          || AutomationControlPlaneSupport.emptyIfNull(
                  runtime.getRuntimeState().getGameInstanceId())
              .isBlank()) {
        continue;
      }
      RoutingBundleSupport.RoutingBundle routingBundle =
          RoutingBundleSupport.fromRuntimeState(runtime.getRuntimeState());
      scopes.put(
          gameInstanceId,
          new CurrentRuntimeScope(
              AutomationControlPlaneSupport.emptyIfNull(
                  runtime.getRuntimeState().getGameInstanceId()),
              AutomationControlPlaneSupport.emptyIfNull(runtime.getRuntimeState().getRegionId()),
              runtime.getRuntimeState().getRegionEpoch(),
              AutomationControlPlaneSupport.normalizePlayableStateScope(
                  runtime.getRuntimeState().getPlayableStateScope()),
              routingBundle.worldSlug(),
              routingBundle.realmSlug(),
              routingBundle.pointerVersion()));
    }
    return scopes;
  }

  private Map<String, GameplayCommandStatusView> loadGameplayCommandStatuses(
      String tenantId, List<ScriptWorkItemService.HandoffEventSummary> summaries) {
    Map<String, GameplayCommandStatusView> statuses = new LinkedHashMap<>();
    for (ScriptWorkItemService.HandoffEventSummary summary : summaries) {
      String commandId = AutomationControlPlaneSupport.emptyIfNull(summary.gameSessionCommandId());
      String gameInstanceId =
          AutomationControlPlaneSupport.emptyIfNull(summary.targetGameInstanceId());
      if (commandId.isBlank() || gameInstanceId.isBlank() || statuses.containsKey(commandId)) {
        continue;
      }
      GetGameplayCommandStatusResponse response =
          gameSessionControlPlaneClient.getGameplayCommandStatus(
              tenantId, gameInstanceId, commandId);
      if (response == null
          || response.hasError()
          || AutomationControlPlaneSupport.emptyIfNull(response.getCommand().getCommandId())
              .isBlank()) {
        continue;
      }
      statuses.put(
          commandId,
          new GameplayCommandStatusView(
              AutomationControlPlaneSupport.emptyIfNull(
                  response.getCommand().getExecutionOutcome()),
              AutomationControlPlaneSupport.emptyIfNull(response.getCommand().getGameplayResult()),
              AutomationControlPlaneSupport.emptyIfNull(response.getCommand().getFailureCode()),
              AutomationControlPlaneSupport.emptyIfNull(response.getCommand().getFailureMessage()),
              AutomationControlPlaneSupport.emptyIfNull(response.getCommand().getRemoteState()),
              AutomationControlPlaneSupport.emptyIfNull(
                  response.getCommand().getRemoteTargetCommandExecutionOutcome()),
              AutomationControlPlaneSupport.emptyIfNull(
                  response.getCommand().getRemoteTargetCommandGameplayResult())));
    }
    return statuses;
  }

  private static boolean isTargetRuntimeScopeStale(
      ScriptWorkItemService.HandoffEventSummary summary, CurrentTargetRuntimeScope currentScope) {
    return isRuntimeScopeStale(
        summary.targetRegionId(),
        summary.targetRegionEpoch(),
        new CurrentRuntimeScope(
            currentScope.gameInstanceId(),
            currentScope.regionId(),
            currentScope.regionEpoch(),
            currentScope.playableStateScope(),
            currentScope.worldSlug(),
            currentScope.realmSlug(),
            currentScope.pointerVersion()));
  }

  private static boolean isTargetRoutingBundleStale(
      ScriptWorkItemService.HandoffEventSummary summary, CurrentTargetRuntimeScope currentScope) {
    return isRoutingBundleStale(
        summary.playableStateScope(),
        summary.worldSlug(),
        summary.realmSlug(),
        summary.pointerVersion(),
        new CurrentRuntimeScope(
            currentScope.gameInstanceId(),
            currentScope.regionId(),
            currentScope.regionEpoch(),
            currentScope.playableStateScope(),
            currentScope.worldSlug(),
            currentScope.realmSlug(),
            currentScope.pointerVersion()));
  }

  private static boolean isRuntimeScopeStale(
      String persistedRegionId, long persistedRegionEpoch, CurrentRuntimeScope currentScope) {
    if (currentScope == null) {
      return false;
    }
    String regionId = AutomationControlPlaneSupport.emptyIfNull(persistedRegionId);
    if (!regionId.isBlank() && !regionId.equals(currentScope.regionId())) {
      return true;
    }
    return persistedRegionEpoch > 0
        && currentScope.regionEpoch() > 0
        && persistedRegionEpoch != currentScope.regionEpoch();
  }

  private static boolean isRoutingBundleStale(
      String persistedPlayableStateScope,
      String persistedWorldSlug,
      String persistedRealmSlug,
      String persistedPointerVersion,
      CurrentRuntimeScope currentScope) {
    if (currentScope == null) {
      return false;
    }
    RoutingBundleSupport.RoutingBundle persistedRoutingBundle =
        RoutingBundleSupport.normalize(
            persistedWorldSlug, persistedRealmSlug, persistedPointerVersion);
    String playableStateScope =
        AutomationControlPlaneSupport.normalize(persistedPlayableStateScope);
    if (!playableStateScope.isBlank()
        && !playableStateScope.equals(
            AutomationControlPlaneSupport.normalize(currentScope.playableStateScope()))) {
      return true;
    }
    if (!persistedRoutingBundle.isPresent()) {
      return false;
    }
    if (!persistedRoutingBundle.worldSlug().equals(currentScope.worldSlug())) {
      return true;
    }
    if (!persistedRoutingBundle.realmSlug().equals(currentScope.realmSlug())) {
      return true;
    }
    return !persistedRoutingBundle.pointerVersion().equals(currentScope.pointerVersion());
  }

  private static void requireCoherentScriptPinFilter(long scriptPinEpoch, String requestId) {
    if (scriptPinEpoch < 0) {
      throw new IllegalArgumentException("script_pin_epoch must be non-negative");
    }
    boolean hasRequestId = requestId != null && !requestId.isBlank();
    if ((scriptPinEpoch > 0) != hasRequestId) {
      throw new IllegalArgumentException(
          "script_pin_epoch and control-plane request ID must be supplied together");
    }
  }

  private record CurrentTargetRuntimeScope(
      String gameInstanceId,
      String regionId,
      long regionEpoch,
      String playableStateScope,
      String worldSlug,
      String realmSlug,
      String pointerVersion) {}

  private record CurrentRuntimeScope(
      String gameInstanceId,
      String regionId,
      long regionEpoch,
      String playableStateScope,
      String worldSlug,
      String realmSlug,
      String pointerVersion) {}

  private record GameplayCommandStatusView(
      String executionOutcome,
      String gameplayResult,
      String failureCode,
      String failureMessage,
      String remoteState,
      String remoteTargetCommandExecutionOutcome,
      String remoteTargetCommandGameplayResult) {}
}
