package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService;
import net.firedevops.firemud.automationscripting.service.ScriptEventRegistryService;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import net.firedevops.firemud.automationscripting.v1.AutomationScriptingControlPlaneServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPatchRequest;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPatchResponse;
import net.firedevops.firemud.automationscripting.v1.DisablePluginRequest;
import net.firedevops.firemud.automationscripting.v1.DisablePluginResponse;
import net.firedevops.firemud.automationscripting.v1.DrainPluginRequest;
import net.firedevops.firemud.automationscripting.v1.DrainPluginResponse;
import net.firedevops.firemud.automationscripting.v1.GetAutomationDrainStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetAutomationDrainStatusResponse;
import net.firedevops.firemud.automationscripting.v1.GetAutomationPinConvergenceRequest;
import net.firedevops.firemud.automationscripting.v1.GetAutomationPinConvergenceResponse;
import net.firedevops.firemud.automationscripting.v1.GetPluginStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetPluginStatusResponse;
import net.firedevops.firemud.automationscripting.v1.GetScriptEventDefinitionRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptEventDefinitionResponse;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchInstanceRolloutStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchInstanceRolloutStatusResponse;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchStatusResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptDeadLettersRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptDeadLettersResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptEventDefinitionsRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptEventDefinitionsResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchInstanceRolloutsRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchInstanceRolloutsResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchStatusesRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchStatusesResponse;
import net.firedevops.firemud.automationscripting.v1.ReplayDeadLetteredWorkItemsRequest;
import net.firedevops.firemud.automationscripting.v1.ReplayDeadLetteredWorkItemsResponse;
import net.firedevops.firemud.automationscripting.v1.ScriptDeadLetterEntry;
import net.firedevops.firemud.automationscripting.v1.ScriptEventDefinition;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchInstanceRolloutEntry;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchStatusEntry;
import net.firedevops.firemud.automationscripting.v1.SetPluginActiveVersionRequest;
import net.firedevops.firemud.automationscripting.v1.SetPluginActiveVersionResponse;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.AdminRoleGuard;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
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
  private final GameSessionControlPlaneClient gameSessionControlPlaneClient;

  public AutomationScriptingControlPlaneGrpcService(
      ScriptEventRegistryService eventRegistryService,
      ScriptWorkItemService workItemService,
      PluginRuntimeStateService pluginRuntimeStateService,
      GameSessionControlPlaneClient gameSessionControlPlaneClient) {
    this.eventRegistryService = eventRegistryService;
    this.workItemService = workItemService;
    this.pluginRuntimeStateService = pluginRuntimeStateService;
    this.gameSessionControlPlaneClient = gameSessionControlPlaneClient;
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
                      .setLastChangedAtMs(summary.lastChangedAtMs()),
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
          .setAdmissionEpoch(summary.admissionEpoch())
          .setActiveExecutionCount(summary.activeExecutionCount())
          .setOldestActiveExecutionStartedAtMs(summary.oldestActiveExecutionStartedAtMs())
          .setPendingCancelableWorkItemCount(summary.pendingCancelableWorkItemCount())
          .setObservedAtMs(summary.observedAtMs());
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
      GetGameInstanceRuntimeStateResponse runtime =
          gameSessionControlPlaneClient.getGameInstanceRuntimeState(
              request.getTenantId(), request.getGameInstanceId());
      if (runtime.hasError() && !runtime.getError().getCode().isBlank()) {
        response.setError(runtime.getError());
      } else if (!runtime.hasRuntimeState()) {
        response.setError(
            notFound("GetAutomationPinConvergence", "game_instance_runtime_state_not_found"));
      } else {
        response
            .setTenantId(runtime.getRuntimeState().getTenantId())
            .setGameInstanceId(runtime.getRuntimeState().getGameInstanceId())
            .setObservedPinnedScriptPatchVersion(
                runtime.getRuntimeState().getPinnedScriptPatchVersion())
            .setLastObservedControlPlaneRequestId("")
            .setObservedAtMs(runtime.getRuntimeState().getScriptPatchPinnedAtMs());
      }
    } catch (AdminAuthorizationException ex) {
      response.setError(authorizationError(ex));
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
                      .setIsProjectionStale(summary.projectionStale()),
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
  @Timed(value = "automationGrpc.controlPlane.listScriptDeadLetters")
  public void listScriptDeadLetters(
      ListScriptDeadLettersRequest request,
      StreamObserver<ListScriptDeadLettersResponse> responseObserver) {
    ListScriptDeadLettersResponse.Builder response = ListScriptDeadLettersResponse.newBuilder();
    try {
      requireAdminRole();
      workItemService
          .listDeadLetters(
              request.getTenantId(),
              request.getGameInstanceId(),
              request.getScriptPatchVersion(),
              request.getLimit())
          .stream()
          .map(AutomationScriptingControlPlaneGrpcService::toProto)
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
  @Timed(value = "automationGrpc.controlPlane.getPluginStatus")
  public void getPluginStatus(
      GetPluginStatusRequest request, StreamObserver<GetPluginStatusResponse> responseObserver) {
    GetPluginStatusResponse.Builder response = GetPluginStatusResponse.newBuilder();
    try {
      requireAdminRole();
      pluginRuntimeStateService
          .getStatus(request.getTenantId(), request.getGameInstanceId(), request.getPluginId())
          .ifPresentOrElse(
              status ->
                  response
                      .setActivePluginVersionId(status.activePluginVersionId())
                      .setPendingPluginVersionId(status.pendingPluginVersionId())
                      .setPluginState(status.pluginState())
                      .setStatusReason(status.statusReason())
                      .setLastChangedAtMs(status.lastChangedAtMs())
                      .setControlPlaneRequestId(status.controlPlaneRequestId())
                      .setActorPrincipal(status.actorPrincipal()),
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
        .setLastChangedAtMs(summary.lastChangedAtMs())
        .build();
  }

  private static ScriptDeadLetterEntry toProto(ScriptWorkItemService.DeadLetterSummary summary) {
    return ScriptDeadLetterEntry.newBuilder()
        .setWorkItemId(summary.workItemId())
        .setTenantId(summary.tenantId())
        .setGameInstanceId(summary.gameInstanceId())
        .setRegionId(summary.regionId())
        .setRegionEpoch(summary.regionEpoch())
        .setEntityId(summary.entityId())
        .setScriptId(summary.scriptId())
        .setEventType(summary.eventType())
        .setScriptPatchVersion(summary.scriptPatchVersion())
        .setScriptEventId(summary.scriptEventId())
        .setStatus(summary.status())
        .setReason(summary.reason())
        .setCreatedAtMs(summary.createdAtMs())
        .setUpdatedAtMs(summary.updatedAtMs())
        .build();
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
        .build();
  }
}
