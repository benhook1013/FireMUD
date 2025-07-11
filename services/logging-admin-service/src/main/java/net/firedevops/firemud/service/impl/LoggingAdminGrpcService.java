package net.firedevops.firemud.service.impl;

import io.grpc.stub.StreamObserver;
import java.util.List;
import net.firedevops.firemud.loggingadmin.v1.*;
import net.firedevops.firemud.service.FeatureFlagService;
import net.firedevops.firemud.service.LogQueryService;
import net.firedevops.firemud.service.ModerationService;
import org.lognet.springboot.grpc.GRpcService;

@GRpcService
public class LoggingAdminGrpcService extends LoggingAdminServiceGrpc.LoggingAdminServiceImplBase {

  private final FeatureFlagService featureFlagService;
  private final LogQueryService logQueryService;
  private final ModerationService moderationService;

  public LoggingAdminGrpcService(
      FeatureFlagService featureFlagService,
      LogQueryService logQueryService,
      ModerationService moderationService) {
    this.featureFlagService = featureFlagService;
    this.logQueryService = logQueryService;
    this.moderationService = moderationService;
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

  @Override
  public void queryLogs(
      QueryLogsRequest request, StreamObserver<QueryLogsResponse> responseObserver) {
    List<String> entries =
        logQueryService.queryLogs(
            new net.firedevops.firemud.dto.QueryLogsRequest(
                Long.valueOf(request.getTenantId()), request.getFilter()));
    QueryLogsResponse response = QueryLogsResponse.newBuilder().addAllEntries(entries).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void applyModerationAction(
      ApplyModerationActionRequest request,
      StreamObserver<ApplyModerationActionResponse> responseObserver) {
    moderationService.applyAction(
        new net.firedevops.firemud.dto.ApplyModerationActionRequest(
            Long.valueOf(request.getTenantId()),
            Long.valueOf(request.getAccountId()),
            request.getAction(),
            request.getReason()));
    ApplyModerationActionResponse response =
        ApplyModerationActionResponse.newBuilder().setSuccess(true).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }
}
