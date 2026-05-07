package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
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
import net.firedevops.firemud.automationscripting.service.ScriptEventRegistryService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchPinProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptScheduleInstanceService;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import net.firedevops.firemud.automationscripting.v1.AutomationAdmissionMode;
import net.firedevops.firemud.automationscripting.v1.AutomationScriptingControlPlaneServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPatchRequest;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPatchResponse;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPluginVersionRequest;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPluginVersionResponse;
import net.firedevops.firemud.automationscripting.v1.DisablePluginRequest;
import net.firedevops.firemud.automationscripting.v1.DisablePluginResponse;
import net.firedevops.firemud.automationscripting.v1.DrainPluginRequest;
import net.firedevops.firemud.automationscripting.v1.DrainPluginResponse;
import net.firedevops.firemud.automationscripting.v1.GetAutomationDrainStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetAutomationDrainStatusResponse;
import net.firedevops.firemud.automationscripting.v1.GetAutomationPinConvergenceRequest;
import net.firedevops.firemud.automationscripting.v1.GetAutomationPinConvergenceResponse;
import net.firedevops.firemud.automationscripting.v1.GetPluginPolicyConvergenceRequest;
import net.firedevops.firemud.automationscripting.v1.GetPluginPolicyConvergenceResponse;
import net.firedevops.firemud.automationscripting.v1.GetPluginStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetPluginStatusResponse;
import net.firedevops.firemud.automationscripting.v1.GetScriptEventDefinitionRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptEventDefinitionResponse;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchInstanceRolloutStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchInstanceRolloutStatusResponse;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchStatusResponse;
import net.firedevops.firemud.automationscripting.v1.ListPluginRuntimeEventsRequest;
import net.firedevops.firemud.automationscripting.v1.ListPluginRuntimeEventsResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptDeadLettersRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptDeadLettersResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptEventDefinitionsRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptEventDefinitionsResponse;
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
import net.firedevops.firemud.automationscripting.v1.PluginPolicyViolation;
import net.firedevops.firemud.automationscripting.v1.PluginPublicationLink;
import net.firedevops.firemud.automationscripting.v1.PluginRuntimeEventEntry;
import net.firedevops.firemud.automationscripting.v1.ReplayDeadLetteredWorkItemsRequest;
import net.firedevops.firemud.automationscripting.v1.ReplayDeadLetteredWorkItemsResponse;
import net.firedevops.firemud.automationscripting.v1.ScriptDeadLetterEntry;
import net.firedevops.firemud.automationscripting.v1.ScriptEventDefinition;
import net.firedevops.firemud.automationscripting.v1.ScriptHandoffEventEntry;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchInstanceRolloutEntry;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchInstanceRolloutEventEntry;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchPublicationLink;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchStatusEntry;
import net.firedevops.firemud.automationscripting.v1.ScriptScheduleInstanceEntry;
import net.firedevops.firemud.automationscripting.v1.ScriptTimerAuditEventEntry;
import net.firedevops.firemud.automationscripting.v1.SetAutomationAdmissionModeRequest;
import net.firedevops.firemud.automationscripting.v1.SetAutomationAdmissionModeResponse;
import net.firedevops.firemud.automationscripting.v1.SetPluginActiveVersionRequest;
import net.firedevops.firemud.automationscripting.v1.SetPluginActiveVersionResponse;
import net.firedevops.firemud.automationscripting.v1.TriggerMode;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.AdminRoleGuard;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import net.firedevops.firemud.gamesession.v1.GetGameplayCommandStatusResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected Spring dependencies are not exposed externally")
public final class AutomationScriptingControlPlaneGrpcService
    extends AutomationScriptingControlPlaneServiceGrpc
        .AutomationScriptingControlPlaneServiceImplBase {

  private final ScriptEventRegistryService eventRegistryService;
  private final ScriptWorkItemService workItemService;
  private final PluginRuntimeStateService pluginRuntimeStateService;
  private final AutomationAdmissionStateService automationAdmissionStateService;
  private final ScriptPatchPinProjectionService scriptPatchPinProjectionService;
  private final ScriptScheduleInstanceService scriptScheduleInstanceService;
  private final GameDesignControlPlaneClient gameDesignControlPlaneClient;
  private final GameSessionControlPlaneClient gameSessionControlPlaneClient;
  private final ScriptRuntimeProperties runtimeProperties;

  public AutomationScriptingControlPlaneGrpcService(
      ScriptEventRegistryService eventRegistryService,
      ScriptWorkItemService workItemService,
      PluginRuntimeStateService pluginRuntimeStateService,
      AutomationAdmissionStateService automationAdmissionStateService,
      ScriptPatchPinProjectionService scriptPatchPinProjectionService,
      ScriptScheduleInstanceService scriptScheduleInstanceService,
      GameDesignControlPlaneClient gameDesignControlPlaneClient,
      GameSessionControlPlaneClient gameSessionControlPlaneClient) {
    this(
        eventRegistryService,
        workItemService,
        pluginRuntimeStateService,
        automationAdmissionStateService,
        scriptPatchPinProjectionService,
        scriptScheduleInstanceService,
        gameDesignControlPlaneClient,
        gameSessionControlPlaneClient,
        new ScriptRuntimeProperties());
  }

  @org.springframework.beans.factory.annotation.Autowired
  public AutomationScriptingControlPlaneGrpcService(
      ScriptEventRegistryService eventRegistryService,
      ScriptWorkItemService workItemService,
      PluginRuntimeStateService pluginRuntimeStateService,
      AutomationAdmissionStateService automationAdmissionStateService,
      ScriptPatchPinProjectionService scriptPatchPinProjectionService,
      ScriptScheduleInstanceService scriptScheduleInstanceService,
      GameDesignControlPlaneClient gameDesignControlPlaneClient,
      GameSessionControlPlaneClient gameSessionControlPlaneClient,
      ScriptRuntimeProperties runtimeProperties) {
    this.eventRegistryService = eventRegistryService;
    this.workItemService = workItemService;
    this.pluginRuntimeStateService = pluginRuntimeStateService;
    this.automationAdmissionStateService = automationAdmissionStateService;
    this.scriptPatchPinProjectionService = scriptPatchPinProjectionService;
    this.scriptScheduleInstanceService = scriptScheduleInstanceService;
    this.gameDesignControlPlaneClient = gameDesignControlPlaneClient;
    this.gameSessionControlPlaneClient = gameSessionControlPlaneClient;
    this.runtimeProperties = runtimeProperties;
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.getScriptEventDefinition")
  public void getScriptEventDefinition(
      GetScriptEventDefinitionRequest request,
      StreamObserver<GetScriptEventDefinitionResponse> responseObserver) {
    GetScriptEventDefinitionResponse.Builder response =
        GetScriptEventDefinitionResponse.newBuilder();
    try {
      requireAdminRole();
      eventRegistryService
          .getDefinition(
              request.getEventType(),
              request.getEventSchemaVersion().isBlank() ? "v1" : request.getEventSchemaVersion())
          .ifPresentOrElse(
              definition -> response.setDefinition(toProto(definition)),
              () -> response.setError(notFound("GetScriptEventDefinition", "event_not_found")));
    } catch (AdminAuthorizationException ex) {
      response.setError(authorizationError(ex));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.listScriptEventDefinitions")
  public void listScriptEventDefinitions(
      ListScriptEventDefinitionsRequest request,
      StreamObserver<ListScriptEventDefinitionsResponse> responseObserver) {
    ListScriptEventDefinitionsResponse.Builder response =
        ListScriptEventDefinitionsResponse.newBuilder();
    try {
      requireAdminRole();
      eventRegistryService.listDefinitions().stream()
          .filter(
              definition ->
                  request.getOwnerService().isBlank()
                      || definition.ownerService().equals(request.getOwnerService()))
          .map(AutomationScriptingControlPlaneGrpcService::toProto)
          .forEach(response::addDefinitions);
    } catch (AdminAuthorizationException ex) {
      response.setError(authorizationError(ex));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.getScriptPatchStatus")
  public void getScriptPatchStatus(
      GetScriptPatchStatusRequest request,
      StreamObserver<GetScriptPatchStatusResponse> responseObserver) {
    GetScriptPatchStatusResponse.Builder response = GetScriptPatchStatusResponse.newBuilder();
    try {
      requireAdminRole();
      workItemService
          .getPatchStatus(request.getTenantId(), request.getScriptPatchVersion())
          .ifPresentOrElse(
              summary ->
                  response
                      .setStatus(summary.status())
                      .setStatusReason(summary.statusReason())
                      .setSupersededByScriptPatchVersion(summary.supersededByScriptPatchVersion())
                      .setLastChangedAtMs(summary.lastChangedAtMs())
                      .setBaseVersionId(summary.baseVersionId())
                      .setAbilitySchemaDigest(summary.abilitySchemaDigest())
                      .setPublication(toProto(summary.publication())),
              () -> response.setError(notFound("GetScriptPatchStatus", "script_patch_not_found")));
    } catch (IllegalArgumentException ex) {
      response.setError(
          ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage(ex.getMessage()));
    } catch (AdminAuthorizationException ex) {
      response.setError(authorizationError(ex));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.listScriptPatchStatuses")
  public void listScriptPatchStatuses(
      ListScriptPatchStatusesRequest request,
      StreamObserver<ListScriptPatchStatusesResponse> responseObserver) {
    ListScriptPatchStatusesResponse.Builder response = ListScriptPatchStatusesResponse.newBuilder();
    try {
      requireAdminRole();
      workItemService
          .listPatchStatuses(
              request.getTenantId(),
              request.getStatus(),
              request.getChangedAfterMs(),
              request.getChangedBeforeMs())
          .stream()
          .map(AutomationScriptingControlPlaneGrpcService::toProto)
          .forEach(response::addPatches);
    } catch (IllegalArgumentException ex) {
      response.setError(
          ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage(ex.getMessage()));
    } catch (AdminAuthorizationException ex) {
      response.setError(authorizationError(ex));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.setAutomationAdmissionMode")
  public void setAutomationAdmissionMode(
      SetAutomationAdmissionModeRequest request,
      StreamObserver<SetAutomationAdmissionModeResponse> responseObserver) {
    SetAutomationAdmissionModeResponse.Builder response =
        SetAutomationAdmissionModeResponse.newBuilder();
    try {
      requireAdminRole();
      AutomationAdmissionStateService.AdmissionStateSummary summary =
          automationAdmissionStateService.setMode(
              new AutomationAdmissionStateService.SetAdmissionModeCommand(
                  request.getTenantId(),
                  request.getGameInstanceId(),
                  request.getRegionId(),
                  requireMode(request.getMode()),
                  request.getControlPlaneRequestId(),
                  request.getActorPrincipal(),
                  request.getReason()));
      response
          .setTenantId(summary.tenantId())
          .setGameInstanceId(summary.gameInstanceId())
          .setRegionId(summary.regionId())
          .setMode(toProtoMode(summary.mode()))
          .setAdmissionEpoch(summary.admissionEpoch())
          .setUpdatedAtMs(summary.updatedAtMs());
    } catch (IllegalArgumentException ex) {
      response.setError(
          ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage(ex.getMessage()));
    } catch (AdminAuthorizationException ex) {
      response.setError(authorizationError(ex));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.getAutomationDrainStatus")
  public void getAutomationDrainStatus(
      GetAutomationDrainStatusRequest request,
      StreamObserver<GetAutomationDrainStatusResponse> responseObserver) {
    GetAutomationDrainStatusResponse.Builder response =
        GetAutomationDrainStatusResponse.newBuilder();
    try {
      requireAdminRole();
      ScriptWorkItemService.AutomationDrainStatusSummary summary =
          workItemService.getAutomationDrainStatus(
              request.getTenantId(), request.getGameInstanceId(), request.getRegionId());
      response
          .setTenantId(summary.tenantId())
          .setGameInstanceId(summary.gameInstanceId())
          .setRegionId(summary.regionId())
          .setAdmissionMode(toProtoMode(summary.admissionMode()))
          .setAdmissionEpoch(summary.admissionEpoch())
          .setActiveExecutionCount(summary.activeExecutionCount())
          .setOldestActiveExecutionStartedAtMs(summary.oldestActiveExecutionStartedAtMs())
          .setPendingCancelableWorkItemCount(summary.pendingCancelableWorkItemCount())
          .setObservedAtMs(summary.observedAtMs())
          .setIsStale(isDrainStatusStale(summary.observedAtMs()));
    } catch (IllegalArgumentException ex) {
      response.setError(
          ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage(ex.getMessage()));
    } catch (AdminAuthorizationException ex) {
      response.setError(authorizationError(ex));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.getAutomationPinConvergence")
  public void getAutomationPinConvergence(
      GetAutomationPinConvergenceRequest request,
      StreamObserver<GetAutomationPinConvergenceResponse> responseObserver) {
    GetAutomationPinConvergenceResponse.Builder response =
        GetAutomationPinConvergenceResponse.newBuilder();
    try {
      requireAdminRole();
      ScriptPatchPinProjectionService.PinConvergenceLookup lookup =
          scriptPatchPinProjectionService.getPinConvergence(
              request.getTenantId(), request.getGameInstanceId());
      if (lookup.summary().isPresent()) {
        ScriptPatchPinProjectionService.PinConvergenceSummary summary = lookup.summary().get();
        response
            .setTenantId(summary.tenantId())
            .setGameInstanceId(summary.gameInstanceId())
            .setObservedPinnedScriptPatchVersion(summary.observedPinnedScriptPatchVersion())
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
            ErrorDetail.newBuilder().setCode(lookup.errorCode()).setMessage(lookup.errorMessage()));
      } else {
        response.setError(notFound("GetAutomationPinConvergence", "pin_projection_not_found"));
      }
    } catch (AdminAuthorizationException ex) {
      response.setError(authorizationError(ex));
    } catch (IllegalArgumentException ex) {
      response.setError(
          ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage(ex.getMessage()));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.getScriptPatchInstanceRolloutStatus")
  public void getScriptPatchInstanceRolloutStatus(
      GetScriptPatchInstanceRolloutStatusRequest request,
      StreamObserver<GetScriptPatchInstanceRolloutStatusResponse> responseObserver) {
    GetScriptPatchInstanceRolloutStatusResponse.Builder response =
        GetScriptPatchInstanceRolloutStatusResponse.newBuilder();
    try {
      requireAdminRole();
      workItemService
          .getPatchInstanceRolloutStatus(
              request.getTenantId(), request.getGameInstanceId(), request.getScriptPatchVersion())
          .ifPresentOrElse(
              summary ->
                  response
                      .setTenantId(summary.tenantId())
                      .setGameInstanceId(summary.gameInstanceId())
                      .setScriptPatchVersion(summary.scriptPatchVersion())
                      .setRolloutStatus(summary.rolloutStatus())
                      .setStatusReason(summary.statusReason())
                      .setLastChangedAtMs(summary.lastChangedAtMs())
                      .setProjectionAsOfMs(summary.projectionAsOfMs())
                      .setProjectionLagMs(summary.projectionLagMs())
                      .setIsProjectionStale(summary.projectionStale())
                      .setPublication(toProto(summary.publication())),
              () ->
                  response.setError(
                      notFound(
                          "GetScriptPatchInstanceRolloutStatus",
                          "script_patch_instance_rollout_not_found")));
    } catch (IllegalArgumentException ex) {
      response.setError(
          ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage(ex.getMessage()));
    } catch (AdminAuthorizationException ex) {
      response.setError(authorizationError(ex));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.listScriptScheduleInstances")
  public void listScriptScheduleInstances(
      ListScriptScheduleInstancesRequest request,
      StreamObserver<ListScriptScheduleInstancesResponse> responseObserver) {
    ListScriptScheduleInstancesResponse.Builder response =
        ListScriptScheduleInstancesResponse.newBuilder();
    try {
      requireAdminRole();
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
      summaries.stream()
          .map(summary -> toProto(summary, currentScopes.get(summary.gameInstanceId())))
          .forEach(response::addSchedules);
    } catch (IllegalArgumentException ex) {
      response.setError(
          ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage(ex.getMessage()));
    } catch (AdminAuthorizationException ex) {
      response.setError(authorizationError(ex));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.listScriptTimerAuditEvents")
  public void listScriptTimerAuditEvents(
      ListScriptTimerAuditEventsRequest request,
      StreamObserver<ListScriptTimerAuditEventsResponse> responseObserver) {
    ListScriptTimerAuditEventsResponse.Builder response =
        ListScriptTimerAuditEventsResponse.newBuilder();
    try {
      requireAdminRole();
      List<ScriptScheduleInstanceService.TimerAuditEventSummary> summaries =
          scriptScheduleInstanceService.listTimerAuditEvents(
              request.getTenantId(),
              request.getGameInstanceId(),
              request.getScriptPatchVersion(),
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
      summaries.stream()
          .map(summary -> toProto(summary, currentScopes.get(summary.gameInstanceId())))
          .forEach(response::addEvents);
    } catch (IllegalArgumentException ex) {
      response.setError(
          ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage(ex.getMessage()));
    } catch (AdminAuthorizationException ex) {
      response.setError(authorizationError(ex));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.listScriptPatchInstanceRollouts")
  public void listScriptPatchInstanceRollouts(
      ListScriptPatchInstanceRolloutsRequest request,
      StreamObserver<ListScriptPatchInstanceRolloutsResponse> responseObserver) {
    ListScriptPatchInstanceRolloutsResponse.Builder response =
        ListScriptPatchInstanceRolloutsResponse.newBuilder();
    try {
      requireAdminRole();
      workItemService
          .listPatchInstanceRollouts(
              request.getTenantId(),
              request.getGameInstanceId(),
              request.getScriptPatchVersion(),
              request.getRolloutStatus(),
              request.getChangedAfterMs(),
              request.getChangedBeforeMs())
          .stream()
          .map(AutomationScriptingControlPlaneGrpcService::toProto)
          .forEach(response::addRollouts);
    } catch (IllegalArgumentException ex) {
      response.setError(
          ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage(ex.getMessage()));
    } catch (AdminAuthorizationException ex) {
      response.setError(authorizationError(ex));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.listScriptPatchInstanceRolloutEvents")
  public void listScriptPatchInstanceRolloutEvents(
      ListScriptPatchInstanceRolloutEventsRequest request,
      StreamObserver<ListScriptPatchInstanceRolloutEventsResponse> responseObserver) {
    ListScriptPatchInstanceRolloutEventsResponse.Builder response =
        ListScriptPatchInstanceRolloutEventsResponse.newBuilder();
    try {
      requireAdminRole();
      workItemService
          .listPatchInstanceRolloutEvents(
              request.getTenantId(),
              request.getGameInstanceId(),
              request.getScriptPatchVersion(),
              request.getRolloutStatus(),
              request.getChangedAfterMs(),
              request.getChangedBeforeMs(),
              request.getLimit())
          .stream()
          .map(AutomationScriptingControlPlaneGrpcService::toProto)
          .forEach(response::addEvents);
    } catch (IllegalArgumentException ex) {
      response.setError(
          ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage(ex.getMessage()));
    } catch (AdminAuthorizationException ex) {
      response.setError(authorizationError(ex));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.listScriptHandoffEvents")
  public void listScriptHandoffEvents(
      ListScriptHandoffEventsRequest request,
      StreamObserver<ListScriptHandoffEventsResponse> responseObserver) {
    ListScriptHandoffEventsResponse.Builder response = ListScriptHandoffEventsResponse.newBuilder();
    try {
      requireAdminRole();
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
              normalizePlayableStateScope(request.getPlayableStateScope()),
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
      summaries.stream()
          .map(
              summary ->
                  toProto(
                      summary,
                      currentScopes.get(summary.targetGameInstanceId()),
                      commandStatuses.get(summary.gameSessionCommandId())))
          .forEach(response::addEvents);
    } catch (IllegalArgumentException ex) {
      response.setError(
          ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage(ex.getMessage()));
    } catch (AdminAuthorizationException ex) {
      response.setError(authorizationError(ex));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.listScriptDeadLetters")
  public void listScriptDeadLetters(
      ListScriptDeadLettersRequest request,
      StreamObserver<ListScriptDeadLettersResponse> responseObserver) {
    ListScriptDeadLettersResponse.Builder response = ListScriptDeadLettersResponse.newBuilder();
    try {
      requireAdminRole();
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
      summaries.stream()
          .map(summary -> toProto(summary, currentScopes.get(summary.gameInstanceId())))
          .forEach(response::addDeadLetters);
    } catch (IllegalArgumentException ex) {
      response.setError(
          ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage(ex.getMessage()));
    } catch (AdminAuthorizationException ex) {
      response.setError(authorizationError(ex));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.replayDeadLetteredWorkItems")
  public void replayDeadLetteredWorkItems(
      ReplayDeadLetteredWorkItemsRequest request,
      StreamObserver<ReplayDeadLetteredWorkItemsResponse> responseObserver) {
    ReplayDeadLetteredWorkItemsResponse.Builder response =
        ReplayDeadLetteredWorkItemsResponse.newBuilder();
    try {
      requireAdminRole();
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
      response.setReplayedCount(result.replayedCount()).setRejectedCount(result.rejectedCount());
    } catch (IllegalArgumentException ex) {
      response.setError(
          ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage(ex.getMessage()));
    } catch (AdminAuthorizationException ex) {
      response.setError(authorizationError(ex));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.cancelPendingWorkItemsForPatch")
  public void cancelPendingWorkItemsForPatch(
      CancelPendingWorkItemsForPatchRequest request,
      StreamObserver<CancelPendingWorkItemsForPatchResponse> responseObserver) {
    CancelPendingWorkItemsForPatchResponse.Builder response =
        CancelPendingWorkItemsForPatchResponse.newBuilder();
    try {
      requireAdminRole();
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
      response.setCanceledCount(canceled);
    } catch (IllegalArgumentException ex) {
      response.setError(
          ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage(ex.getMessage()));
    } catch (AdminAuthorizationException ex) {
      response.setError(authorizationError(ex));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.cancelPendingWorkItemsForPluginVersion")
  public void cancelPendingWorkItemsForPluginVersion(
      CancelPendingWorkItemsForPluginVersionRequest request,
      StreamObserver<CancelPendingWorkItemsForPluginVersionResponse> responseObserver) {
    CancelPendingWorkItemsForPluginVersionResponse.Builder response =
        CancelPendingWorkItemsForPluginVersionResponse.newBuilder();
    try {
      requireAdminRole();
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
      response.setCanceledCount(canceled);
    } catch (IllegalArgumentException ex) {
      response.setError(
          ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage(ex.getMessage()));
    } catch (AdminAuthorizationException ex) {
      response.setError(authorizationError(ex));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.getPluginStatus")
  public void getPluginStatus(
      GetPluginStatusRequest request, StreamObserver<GetPluginStatusResponse> responseObserver) {
    GetPluginStatusResponse.Builder response = GetPluginStatusResponse.newBuilder();
    try {
      requireAdminRole();
      pluginRuntimeStateService
          .getStatus(request.getTenantId(), request.getGameInstanceId(), request.getPluginId())
          .ifPresentOrElse(
              status -> {
                response
                    .setActivePluginVersionId(status.activePluginVersionId())
                    .setPendingPluginVersionId(status.pendingPluginVersionId())
                    .setRuntimeRegionId(status.runtimeRegionId())
                    .setRuntimeRegionEpoch(status.runtimeRegionEpoch())
                    .setPluginState(status.pluginState())
                    .setStatusReason(status.statusReason())
                    .setLastChangedAtMs(status.lastChangedAtMs())
                    .setControlPlaneRequestId(status.controlPlaneRequestId())
                    .setActorPrincipal(status.actorPrincipal())
                    .setLastPolicyCheckedAtMs(status.lastPolicyCheckedAtMs())
                    .setPolicyCheckStale(isPolicyCheckStale(status.lastPolicyCheckedAtMs()));
                if (status.activePublication() != null) {
                  response.setActivePublication(toProto(status.activePublication()));
                }
                if (status.pendingPublication() != null) {
                  response.setPendingPublication(toProto(status.pendingPublication()));
                }
              },
              () ->
                  response.setError(notFound("GetPluginStatus", "plugin_runtime_state_not_found")));
    } catch (IllegalArgumentException ex) {
      response.setError(
          ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage(ex.getMessage()));
    } catch (AdminAuthorizationException ex) {
      response.setError(authorizationError(ex));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.listPluginRuntimeEvents")
  public void listPluginRuntimeEvents(
      ListPluginRuntimeEventsRequest request,
      StreamObserver<ListPluginRuntimeEventsResponse> responseObserver) {
    ListPluginRuntimeEventsResponse.Builder response = ListPluginRuntimeEventsResponse.newBuilder();
    try {
      requireAdminRole();
      pluginRuntimeStateService
          .listEvents(
              request.getTenantId(),
              request.getGameInstanceId(),
              request.getPluginId(),
              request.getPluginState(),
              request.getActivePluginVersionId(),
              request.getChangedAfterMs(),
              request.getChangedBeforeMs(),
              request.getLimit())
          .stream()
          .map(AutomationScriptingControlPlaneGrpcService::toProto)
          .forEach(response::addEvents);
    } catch (IllegalArgumentException ex) {
      response.setError(
          ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage(ex.getMessage()));
    } catch (AdminAuthorizationException ex) {
      response.setError(authorizationError(ex));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.getPluginPolicyConvergence")
  public void getPluginPolicyConvergence(
      GetPluginPolicyConvergenceRequest request,
      StreamObserver<GetPluginPolicyConvergenceResponse> responseObserver) {
    GetPluginPolicyConvergenceResponse.Builder response =
        GetPluginPolicyConvergenceResponse.newBuilder();
    try {
      requireAdminRole();
      PluginRuntimeStateService.PluginPolicyConvergence convergence =
          pluginRuntimeStateService.getPluginPolicyConvergence(
              request.getTenantId(), request.getGameInstanceId(), request.getMaxResults());
      response
          .setInspectedCount(convergence.inspectedCount())
          .setFailClosedCount(convergence.failClosedCount())
          .setConverged(convergence.converged())
          .setEvaluatedAtMs(convergence.evaluatedAtMs())
          .setIsStale(isPolicyCheckStale(convergence.evaluatedAtMs()));
      convergence.violations().stream()
          .map(AutomationScriptingControlPlaneGrpcService::toProto)
          .forEach(response::addViolations);
    } catch (IllegalArgumentException ex) {
      response.setError(
          ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage(ex.getMessage()));
    } catch (AdminAuthorizationException ex) {
      response.setError(authorizationError(ex));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.setPluginActiveVersion")
  public void setPluginActiveVersion(
      SetPluginActiveVersionRequest request,
      StreamObserver<SetPluginActiveVersionResponse> responseObserver) {
    SetPluginActiveVersionResponse.Builder response = SetPluginActiveVersionResponse.newBuilder();
    try {
      requireAdminRole();
      PluginRuntimeStateService.ActivationResult result =
          pluginRuntimeStateService.setActiveVersion(
              new PluginRuntimeStateService.ActivationCommand(
                  request.getTenantId(),
                  request.getGameInstanceId(),
                  request.getPluginId(),
                  request.getTargetPluginVersionId(),
                  request.getControlPlaneRequestId(),
                  request.getActorPrincipal(),
                  request.getReason()));
      response
          .setPreviousPluginVersionId(result.previousPluginVersionId())
          .setActivePluginVersionId(result.activePluginVersionId())
          .setControlPlaneRequestId(result.controlPlaneRequestId());
    } catch (IllegalArgumentException ex) {
      response.setError(
          ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage(ex.getMessage()));
    } catch (AdminAuthorizationException ex) {
      response.setError(authorizationError(ex));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.disablePlugin")
  public void disablePlugin(
      DisablePluginRequest request, StreamObserver<DisablePluginResponse> responseObserver) {
    DisablePluginResponse.Builder response = DisablePluginResponse.newBuilder();
    try {
      requireAdminRole();
      boolean success =
          pluginRuntimeStateService.disable(
              new PluginRuntimeStateService.PluginStateCommand(
                  request.getTenantId(),
                  request.getGameInstanceId(),
                  request.getPluginId(),
                  request.getControlPlaneRequestId(),
                  request.getActorPrincipal(),
                  request.getReason()));
      response.setSuccess(success);
    } catch (IllegalArgumentException ex) {
      response.setError(
          ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage(ex.getMessage()));
    } catch (AdminAuthorizationException ex) {
      response.setError(authorizationError(ex));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.drainPlugin")
  public void drainPlugin(
      DrainPluginRequest request, StreamObserver<DrainPluginResponse> responseObserver) {
    DrainPluginResponse.Builder response = DrainPluginResponse.newBuilder();
    try {
      requireAdminRole();
      boolean success =
          pluginRuntimeStateService.drain(
              new PluginRuntimeStateService.PluginStateCommand(
                  request.getTenantId(),
                  request.getGameInstanceId(),
                  request.getPluginId(),
                  request.getControlPlaneRequestId(),
                  request.getActorPrincipal(),
                  request.getReason()));
      response.setSuccess(success);
    } catch (IllegalArgumentException ex) {
      response.setError(
          ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage(ex.getMessage()));
    } catch (AdminAuthorizationException ex) {
      response.setError(authorizationError(ex));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  private static void requireAdminRole() {
    AdminRoleGuard.requireAdminRole();
  }

  private static PluginPolicyViolation toProto(
      PluginRuntimeStateService.PluginPolicyViolation violation) {
    PluginPolicyViolation.Builder builder =
        PluginPolicyViolation.newBuilder()
            .setGameInstanceId(violation.gameInstanceId())
            .setRuntimeRegionId(violation.runtimeRegionId())
            .setRuntimeRegionEpoch(violation.runtimeRegionEpoch())
            .setPluginId(violation.pluginId())
            .setActivePluginVersionId(violation.activePluginVersionId())
            .setReason(violation.reason())
            .setLastChangedAtMs(violation.lastChangedAtMs());
    if (violation.activePublication() != null) {
      builder.setActivePublication(toProto(violation.activePublication()));
    }
    return builder.build();
  }

  private boolean isPolicyCheckStale(long lastPolicyCheckedAtMs) {
    long ageMs = Instant.now().toEpochMilli() - lastPolicyCheckedAtMs;
    return ageMs > runtimeProperties.getPluginPolicyStaleThresholdSeconds() * 1_000L;
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

  private static ErrorDetail authorizationError(AdminAuthorizationException ex) {
    return ErrorDetail.newBuilder()
        .setCode("PERMISSION_DENIED")
        .setMessage(ex.getMessage())
        .build();
  }

  private static ErrorDetail notFound(String method, String reason) {
    return ErrorDetail.newBuilder()
        .setCode("NOT_FOUND")
        .setMessage(method + " failed: " + reason)
        .build();
  }

  private static ScriptEventDefinition toProto(
      ScriptEventRegistryService.EventDefinition definition) {
    return ScriptEventDefinition.newBuilder()
        .setEventType(definition.eventType())
        .setEventSchemaVersion(definition.eventSchemaVersion())
        .setOwnerService(definition.ownerService())
        .addAllAllowedProducerPrincipals(definition.allowedProducerPrincipals())
        .addAllRequiredTriggerIdentityFields(definition.requiredTriggerIdentityFields())
        .setSnapshotAuthority(definition.snapshotAuthority())
        .setConsistencyClass(definition.consistencyClass())
        .setQuotaClass(definition.quotaClass())
        .setReplaySemantics(definition.replaySemantics())
        .addAllAllowedBindingScopes(definition.allowedBindingScopes())
        .setDryRunSupport(definition.dryRunSupport())
        .setDeprecationStatus(definition.deprecationStatus())
        .setPayloadSchemaRef(definition.payloadSchemaRef())
        .build();
  }

  private static ScriptPatchStatusEntry toProto(ScriptWorkItemService.PatchStatusSummary summary) {
    return ScriptPatchStatusEntry.newBuilder()
        .setScriptPatchVersion(summary.scriptPatchVersion())
        .setStatus(summary.status())
        .setStatusReason(summary.statusReason())
        .setSupersededByScriptPatchVersion(summary.supersededByScriptPatchVersion())
        .setLastChangedAtMs(summary.lastChangedAtMs())
        .setBaseVersionId(summary.baseVersionId())
        .setAbilitySchemaDigest(summary.abilitySchemaDigest())
        .setPublication(toProto(summary.publication()))
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
          .setScriptPatchVersion(normalize(scriptPatchVersion))
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

  private static PluginRuntimeEventEntry toProto(
      PluginRuntimeStateService.PluginRuntimeEventSummary summary) {
    PluginRuntimeEventEntry.Builder builder =
        PluginRuntimeEventEntry.newBuilder()
            .setEventId(summary.eventId())
            .setTenantId(summary.tenantId())
            .setGameInstanceId(summary.gameInstanceId())
            .setRuntimeRegionId(summary.runtimeRegionId())
            .setRuntimeRegionEpoch(summary.runtimeRegionEpoch())
            .setPluginId(summary.pluginId())
            .setPreviousPluginVersionId(summary.previousPluginVersionId())
            .setActivePluginVersionId(summary.activePluginVersionId())
            .setPluginState(summary.pluginState())
            .setStatusReason(summary.statusReason())
            .setControlPlaneRequestId(summary.controlPlaneRequestId())
            .setActorPrincipal(summary.actorPrincipal())
            .setObservedAtMs(summary.observedAtMs());
    if (summary.previousPublication() != null) {
      builder.setPreviousPublication(toProto(summary.previousPublication()));
    }
    if (summary.activePublication() != null) {
      builder.setActivePublication(toProto(summary.activePublication()));
    }
    return builder.build();
  }

  private static AutomationAdmissionMode toProtoMode(String mode) {
    return switch (mode) {
      case "NORMAL" -> AutomationAdmissionMode.AUTOMATION_ADMISSION_MODE_NORMAL;
      case "PAUSED_FOR_ROLLBACK" ->
          AutomationAdmissionMode.AUTOMATION_ADMISSION_MODE_PAUSED_FOR_ROLLBACK;
      default -> AutomationAdmissionMode.AUTOMATION_ADMISSION_MODE_UNSPECIFIED;
    };
  }

  private static TriggerMode toTriggerMode(String triggerMode) {
    return switch (triggerMode) {
      case "TRIGGER_MODE_CATCH_UP" -> TriggerMode.TRIGGER_MODE_CATCH_UP;
      case "TRIGGER_MODE_NORMAL" -> TriggerMode.TRIGGER_MODE_NORMAL;
      default -> TriggerMode.TRIGGER_MODE_UNSPECIFIED;
    };
  }

  private static String requireMode(AutomationAdmissionMode mode) {
    return switch (mode) {
      case AUTOMATION_ADMISSION_MODE_NORMAL -> "NORMAL";
      case AUTOMATION_ADMISSION_MODE_PAUSED_FOR_ROLLBACK -> "PAUSED_FOR_ROLLBACK";
      case UNRECOGNIZED, AUTOMATION_ADMISSION_MODE_UNSPECIFIED ->
          throw new IllegalArgumentException("mode is required");
    };
  }

  private static ScriptDeadLetterEntry toProto(
      ScriptWorkItemService.DeadLetterSummary summary, CurrentRuntimeScope currentScope) {
    ScriptDeadLetterEntry.Builder builder =
        ScriptDeadLetterEntry.newBuilder()
            .setWorkItemId(summary.workItemId())
            .setTenantId(summary.tenantId())
            .setGameInstanceId(summary.gameInstanceId())
            .setRegionId(summary.regionId())
            .setRegionEpoch(summary.regionEpoch())
            .setEntityId(summary.entityId())
            .setPlayableStateScope(toPlayableStateScope(summary.playableStateScope()))
            .setWorldSlug(summary.worldSlug())
            .setRealmSlug(summary.realmSlug())
            .setPointerVersion(summary.pointerVersion())
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
              toPlayableStateScope(currentScope.playableStateScope()))
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
    ScriptScheduleInstanceEntry.Builder builder =
        ScriptScheduleInstanceEntry.newBuilder()
            .setTenantId(summary.tenantId())
            .setGameInstanceId(summary.gameInstanceId())
            .setScriptPatchVersion(summary.scriptPatchVersion())
            .setScriptId(summary.scriptId())
            .setPlayableStateScope(toPlayableStateScope(summary.playableStateScope()))
            .setWorldSlug(summary.worldSlug())
            .setRealmSlug(summary.realmSlug())
            .setPointerVersion(summary.pointerVersion())
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
              toPlayableStateScope(currentScope.playableStateScope()))
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
    ScriptTimerAuditEventEntry.Builder builder =
        ScriptTimerAuditEventEntry.newBuilder()
            .setTenantId(summary.tenantId())
            .setGameInstanceId(summary.gameInstanceId())
            .setRegionId(summary.regionId())
            .setRegionEpoch(summary.regionEpoch())
            .setEntityId(summary.entityId())
            .setPlayableStateScope(toPlayableStateScope(summary.playableStateScope()))
            .setWorldSlug(summary.worldSlug())
            .setRealmSlug(summary.realmSlug())
            .setPointerVersion(summary.pointerVersion())
            .setScriptId(summary.scriptId())
            .setPluginId(summary.pluginId())
            .setPluginVersionId(summary.pluginVersionId())
            .setEventType(summary.eventType())
            .setScriptPatchVersion(summary.scriptPatchVersion())
            .setScriptEventId(summary.scriptEventId())
            .setTriggerMode(toTriggerMode(summary.triggerMode()))
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
              toPlayableStateScope(currentScope.playableStateScope()))
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

  private ScriptHandoffEventEntry toProto(
      ScriptWorkItemService.HandoffEventSummary summary,
      CurrentTargetRuntimeScope currentScope,
      GameplayCommandStatusView commandStatus) {
    ScriptHandoffEventEntry.Builder builder =
        ScriptHandoffEventEntry.newBuilder()
            .setEventId(summary.eventId())
            .setTenantId(summary.tenantId())
            .setGameInstanceId(summary.gameInstanceId())
            .setScriptPatchVersion(summary.scriptPatchVersion())
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
            .setPlayableStateScope(toPlayableStateScope(summary.playableStateScope()))
            .setWorldSlug(summary.worldSlug())
            .setRealmSlug(summary.realmSlug())
            .setPointerVersion(summary.pointerVersion())
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
              toPlayableStateScope(currentScope.playableStateScope()))
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
      String targetGameInstanceId = emptyIfNull(summary.targetGameInstanceId());
      if (targetGameInstanceId.isBlank() || scopes.containsKey(targetGameInstanceId)) {
        continue;
      }
      GetGameInstanceRuntimeStateResponse runtime =
          gameSessionControlPlaneClient.getGameInstanceRuntimeState(tenantId, targetGameInstanceId);
      if (runtime == null
          || runtime.hasError()
          || emptyIfNull(runtime.getRuntimeState().getGameInstanceId()).isBlank()) {
        continue;
      }
      scopes.put(
          targetGameInstanceId,
          new CurrentTargetRuntimeScope(
              emptyIfNull(runtime.getRuntimeState().getGameInstanceId()),
              emptyIfNull(runtime.getRuntimeState().getRegionId()),
              runtime.getRuntimeState().getRegionEpoch(),
              normalizePlayableStateScope(runtime.getRuntimeState().getPlayableStateScope()),
              emptyIfNull(runtime.getRuntimeState().getWorldSlug()),
              emptyIfNull(runtime.getRuntimeState().getRealmSlug()),
              Long.toString(runtime.getRuntimeState().getPointerVersion())));
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
      String gameInstanceId = emptyIfNull(gameInstanceIdExtractor.apply(summary));
      if (gameInstanceId.isBlank() || scopes.containsKey(gameInstanceId)) {
        continue;
      }
      GetGameInstanceRuntimeStateResponse runtime =
          gameSessionControlPlaneClient.getGameInstanceRuntimeState(
              tenantId, gameInstanceId, emptyIfNull(preferredRegionIdExtractor.apply(summary)));
      if (runtime == null
          || runtime.hasError()
          || emptyIfNull(runtime.getRuntimeState().getGameInstanceId()).isBlank()) {
        continue;
      }
      scopes.put(
          gameInstanceId,
          new CurrentRuntimeScope(
              emptyIfNull(runtime.getRuntimeState().getGameInstanceId()),
              emptyIfNull(runtime.getRuntimeState().getRegionId()),
              runtime.getRuntimeState().getRegionEpoch(),
              normalizePlayableStateScope(runtime.getRuntimeState().getPlayableStateScope()),
              emptyIfNull(runtime.getRuntimeState().getWorldSlug()),
              emptyIfNull(runtime.getRuntimeState().getRealmSlug()),
              Long.toString(runtime.getRuntimeState().getPointerVersion())));
    }
    return scopes;
  }

  private Map<String, GameplayCommandStatusView> loadGameplayCommandStatuses(
      String tenantId, List<ScriptWorkItemService.HandoffEventSummary> summaries) {
    Map<String, GameplayCommandStatusView> statuses = new LinkedHashMap<>();
    for (ScriptWorkItemService.HandoffEventSummary summary : summaries) {
      String commandId = emptyIfNull(summary.gameSessionCommandId());
      if (commandId.isBlank() || statuses.containsKey(commandId)) {
        continue;
      }
      GetGameplayCommandStatusResponse response =
          gameSessionControlPlaneClient.getGameplayCommandStatus(tenantId, commandId);
      if (response == null
          || response.hasError()
          || emptyIfNull(response.getCommand().getCommandId()).isBlank()) {
        continue;
      }
      statuses.put(
          commandId,
          new GameplayCommandStatusView(
              emptyIfNull(response.getCommand().getExecutionOutcome()),
              emptyIfNull(response.getCommand().getGameplayResult()),
              emptyIfNull(response.getCommand().getFailureCode()),
              emptyIfNull(response.getCommand().getFailureMessage()),
              emptyIfNull(response.getCommand().getRemoteState()),
              emptyIfNull(response.getCommand().getRemoteTargetCommandExecutionOutcome()),
              emptyIfNull(response.getCommand().getRemoteTargetCommandGameplayResult())));
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
    String regionId = emptyIfNull(persistedRegionId);
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
    String playableStateScope = normalize(persistedPlayableStateScope);
    if (!playableStateScope.isBlank()
        && !playableStateScope.equals(normalize(currentScope.playableStateScope()))) {
      return true;
    }
    String worldSlug = emptyIfNull(persistedWorldSlug);
    if (!worldSlug.isBlank() && !worldSlug.equals(currentScope.worldSlug())) {
      return true;
    }
    String realmSlug = emptyIfNull(persistedRealmSlug);
    if (!realmSlug.isBlank() && !realmSlug.equals(currentScope.realmSlug())) {
      return true;
    }
    String pointerVersion = emptyIfNull(persistedPointerVersion);
    return !pointerVersion.isBlank() && !pointerVersion.equals(currentScope.pointerVersion());
  }

  private static PlayableStateScope toPlayableStateScope(String playableStateScope) {
    return switch (normalize(playableStateScope)) {
      case "SHARED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
      case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
      default -> PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED;
    };
  }

  private static String normalizePlayableStateScope(PlayableStateScope playableStateScope) {
    return switch (playableStateScope) {
      case PLAYABLE_STATE_SCOPE_SHARED -> "SHARED";
      case PLAYABLE_STATE_SCOPE_ISOLATED -> "ISOLATED";
      default -> "";
    };
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
  }

  private static String emptyIfNull(String value) {
    return value == null ? "" : value;
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
