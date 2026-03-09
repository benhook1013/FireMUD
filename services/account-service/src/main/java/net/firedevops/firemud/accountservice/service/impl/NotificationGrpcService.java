package net.firedevops.firemud.accountservice.service.impl;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.account.v1.NotificationServiceGrpc;
import net.firedevops.firemud.account.v1.SendNotificationRequest;
import net.firedevops.firemud.account.v1.SendNotificationResponse;
import net.firedevops.firemud.common.security.RequireAdminRole;
import net.firedevops.firemud.accountservice.service.NotificationService;
import org.lognet.springboot.grpc.GRpcService;

@GRpcService
public class NotificationGrpcService extends NotificationServiceGrpc.NotificationServiceImplBase {
  private final NotificationService notificationService;

  public NotificationGrpcService(NotificationService notificationService) {
    this.notificationService = notificationService;
  }

  @Override
  @RequireAdminRole
  @Timed(value = "notificationGrpc.sendNotification")
  public void sendNotification(
      SendNotificationRequest request, StreamObserver<SendNotificationResponse> responseObserver) {
    try {
      notificationService.sendNotification(
          Long.valueOf(request.getTenantId()),
          Long.valueOf(request.getAccountId()),
          request.getMessage());
      SendNotificationResponse response =
          SendNotificationResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      SendNotificationResponse response =
          SendNotificationResponse.newBuilder()
              .setSuccess(false)
              .setError(
                  net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                      .setCode("INVALID_ARGUMENT")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }
}
