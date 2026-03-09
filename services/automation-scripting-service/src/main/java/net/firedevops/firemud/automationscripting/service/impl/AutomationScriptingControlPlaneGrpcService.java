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
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.lognet.springboot.grpc.GRpcService;

@GRpcService
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
    GetScriptPatchStatusResponse response =
        GetScriptPatchStatusResponse.newBuilder()
            .setError(notImplemented("GetScriptPatchStatus"))
            .build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.listScriptPatchStatuses")
  public void listScriptPatchStatuses(
      ListScriptPatchStatusesRequest request,
      StreamObserver<ListScriptPatchStatusesResponse> responseObserver) {
    ListScriptPatchStatusesResponse response =
        ListScriptPatchStatusesResponse.newBuilder()
            .setError(notImplemented("ListScriptPatchStatuses"))
            .build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.cancelPendingWorkItemsForPatch")
  public void cancelPendingWorkItemsForPatch(
      CancelPendingWorkItemsForPatchRequest request,
      StreamObserver<CancelPendingWorkItemsForPatchResponse> responseObserver) {
    CancelPendingWorkItemsForPatchResponse response =
        CancelPendingWorkItemsForPatchResponse.newBuilder()
            .setError(notImplemented("CancelPendingWorkItemsForPatch"))
            .build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }
}
