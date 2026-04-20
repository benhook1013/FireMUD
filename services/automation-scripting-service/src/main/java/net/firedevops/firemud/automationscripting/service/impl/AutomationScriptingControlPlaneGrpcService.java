package net.firedevops.firemud.automationscripting.service.impl;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.automationscripting.v1.AutomationScriptingControlPlaneServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPatchRequest;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPatchResponse;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchStatusResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchStatusesRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchStatusesResponse;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.AdminRoleGuard;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public final class AutomationScriptingControlPlaneGrpcService
    extends AutomationScriptingControlPlaneServiceGrpc
        .AutomationScriptingControlPlaneServiceImplBase {

  private static ErrorDetail notImplemented(String method) {
    return ErrorDetail.newBuilder()
        .setCode("NOT_IMPLEMENTED")
        .setMessage(method + " is not implemented yet")
        .build();
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
      response.setError(notImplemented("CancelPendingWorkItemsForPatch"));
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
}
