package net.firedevops.firemud.accountservice.service.impl;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import net.firedevops.firemud.account.v1.NotificationServiceGrpc;
import net.firedevops.firemud.account.v1.SendNotificationRequest;
import net.firedevops.firemud.account.v1.SendNotificationResponse;
import net.firedevops.firemud.accountservice.service.NotificationService;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.AdminRoleGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public class NotificationGrpcService extends NotificationServiceGrpc.NotificationServiceImplBase {
  private static final Logger logger = LoggerFactory.getLogger(NotificationGrpcService.class);
  private final NotificationService notificationService;
  private final MeterRegistry meterRegistry;

  public NotificationGrpcService(NotificationService notificationService) {
    this(notificationService, null);
  }

  public NotificationGrpcService(
      NotificationService notificationService, MeterRegistry meterRegistry) {
    this.notificationService = notificationService;
    this.meterRegistry = meterRegistry;
  }

  @Override
  @Timed(value = "notificationGrpc.sendNotification")
  public void sendNotification(
      SendNotificationRequest request, StreamObserver<SendNotificationResponse> responseObserver) {
    try {
      AdminRoleGuard.requireAdminRole();
      notificationService.sendNotification(
          Long.valueOf(request.getTenantId()),
          Long.valueOf(request.getAccountId()),
          request.getMessage());
      SendNotificationResponse response =
          SendNotificationResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      SendNotificationResponse response =
          SendNotificationResponse.newBuilder()
              .setSuccess(false)
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "SendNotification",
                      "PERMISSION_DENIED",
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      SendNotificationResponse response =
          SendNotificationResponse.newBuilder()
              .setSuccess(false)
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "SendNotification",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      SendNotificationResponse response =
          SendNotificationResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "SendNotification", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }
}
