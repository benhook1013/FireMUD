package net.firedevops.firemud.service.impl;

import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.loggingadmin.v1.LoggingAdminServiceGrpc;
import net.firedevops.firemud.loggingadmin.v1.PingRequest;
import net.firedevops.firemud.loggingadmin.v1.PingResponse;
import net.firedevops.firemud.loggingadmin.v1.ToggleFeatureFlagRequest;
import net.firedevops.firemud.loggingadmin.v1.ToggleFeatureFlagResponse;
import net.firedevops.firemud.service.FeatureFlagService;
import org.lognet.springboot.grpc.GRpcService;

@GRpcService
public class LoggingAdminGrpcService extends LoggingAdminServiceGrpc.LoggingAdminServiceImplBase {

  private final FeatureFlagService featureFlagService;

  public LoggingAdminGrpcService(FeatureFlagService featureFlagService) {
    this.featureFlagService = featureFlagService;
  }

  @Override
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    PingResponse response = PingResponse.newBuilder().setMessage("pong").build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void toggleFeatureFlag(
      ToggleFeatureFlagRequest request,
      StreamObserver<ToggleFeatureFlagResponse> responseObserver) {
    featureFlagService.toggleFlag(
        new net.firedevops.firemud.dto.ToggleFeatureFlagRequest(
            Long.valueOf(request.getTenantId()), request.getName(), request.getEnabled()));
    ToggleFeatureFlagResponse response =
        ToggleFeatureFlagResponse.newBuilder().setSuccess(true).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }
}
