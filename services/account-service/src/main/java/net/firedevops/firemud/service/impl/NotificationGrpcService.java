package net.firedevops.firemud.service.impl;

import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.account.v1.NotificationServiceGrpc;
import net.firedevops.firemud.account.v1.SendNotificationRequest;
import net.firedevops.firemud.account.v1.SendNotificationResponse;
import net.firedevops.firemud.service.NotificationService;
import org.lognet.springboot.grpc.GRpcService;

@GRpcService
public class NotificationGrpcService extends NotificationServiceGrpc.NotificationServiceImplBase {
  private final NotificationService notificationService;

  public NotificationGrpcService(NotificationService notificationService) {
    this.notificationService = notificationService;
  }

  @Override
  public void sendNotification(
      SendNotificationRequest request, StreamObserver<SendNotificationResponse> responseObserver) {
    notificationService.sendNotification(
        Long.valueOf(request.getTenantId()),
        Long.valueOf(request.getAccountId()),
        request.getMessage());
    SendNotificationResponse response =
        SendNotificationResponse.newBuilder().setSuccess(true).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }
}
