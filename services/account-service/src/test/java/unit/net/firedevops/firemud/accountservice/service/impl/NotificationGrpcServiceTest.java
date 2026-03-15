package net.firedevops.firemud.accountservice.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.SendNotificationRequest;
import net.firedevops.firemud.account.v1.SendNotificationResponse;
import net.firedevops.firemud.accountservice.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class NotificationGrpcServiceTest {
  @Test
  void sendNotificationReturnsSuccess() {
    NotificationService notificationService = Mockito.mock(NotificationService.class);
    NotificationGrpcService service = new NotificationGrpcService(notificationService);

    AtomicReference<SendNotificationResponse> ref = new AtomicReference<>();
    service.sendNotification(
        SendNotificationRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setMessage("hi")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(SendNotificationResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals(true, ref.get().getSuccess());
  }

  @Test
  void sendNotificationErrorReturnsErrorDetail() {
    NotificationService notificationService = Mockito.mock(NotificationService.class);
    Mockito.doThrow(new IllegalArgumentException("bad"))
        .when(notificationService)
        .sendNotification(1L, 2L, "hi");
    NotificationGrpcService service = new NotificationGrpcService(notificationService);

    AtomicReference<SendNotificationResponse> ref = new AtomicReference<>();
    service.sendNotification(
        SendNotificationRequest.newBuilder()
            .setTenantId("1")
            .setAccountId("2")
            .setMessage("hi")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(SendNotificationResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
  }
}
