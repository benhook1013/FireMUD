package net.firedevops.firemud.automationscripting.service.impl;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.automationscripting.service.ScriptEventRegistryService;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import net.firedevops.firemud.automationscripting.v1.AutomationScriptingControlPlaneServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPatchRequest;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPatchResponse;
import net.firedevops.firemud.automationscripting.v1.GetScriptEventDefinitionRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptEventDefinitionResponse;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchStatusResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptEventDefinitionsRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptEventDefinitionsResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchStatusesRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchStatusesResponse;
import net.firedevops.firemud.automationscripting.v1.ScriptEventDefinition;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.AdminRoleGuard;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public final class AutomationScriptingControlPlaneGrpcService
    extends AutomationScriptingControlPlaneServiceGrpc
        .AutomationScriptingControlPlaneServiceImplBase {

  private final ScriptEventRegistryService eventRegistryService;
  private final ScriptWorkItemService workItemService;

  public AutomationScriptingControlPlaneGrpcService(
      ScriptEventRegistryService eventRegistryService, ScriptWorkItemService workItemService) {
    this.eventRegistryService = eventRegistryService;
    this.workItemService = workItemService;
  }

  private static ErrorDetail notImplemented(String method) {
    return ErrorDetail.newBuilder()
        .setCode("NOT_IMPLEMENTED")
        .setMessage(method + " is not implemented yet")
        .build();
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
      response.setError(notImplemented("GetScriptPatchStatus"));
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
      response.setError(notImplemented("ListScriptPatchStatuses"));
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
        .build();
  }
}
