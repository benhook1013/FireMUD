package net.firedevops.firemud.accountservice.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.SendNotificationRequest;
import net.firedevops.firemud.account.v1.SendNotificationResponse;
import net.firedevops.firemud.accountservice.service.NotificationService;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class NotificationGrpcServiceTest {
  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @Test
  void sendNotificationReturnsSuccess() {
    NotificationService notificationService = Mockito.mock(NotificationService.class);
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    NotificationGrpcService service =
        new NotificationGrpcService(notificationService, new SimpleMeterRegistry());

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

    assertTrue(ref.get().getSuccess());
  }

  @Test
  void sendNotificationErrorReturnsErrorDetail() {
    NotificationService notificationService = Mockito.mock(NotificationService.class);
    Mockito.doThrow(new IllegalArgumentException("bad"))
        .when(notificationService)
        .sendNotification(1L, 2L, "hi");
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    NotificationGrpcService service =
        new NotificationGrpcService(notificationService, new SimpleMeterRegistry());

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

  @Test
  void sendNotificationRuntimeFailureReturnsInternalErrorDetail() {
    NotificationService notificationService = Mockito.mock(NotificationService.class);
    Mockito.doThrow(new IllegalStateException("boom"))
        .when(notificationService)
        .sendNotification(1L, 2L, "hi");
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    NotificationGrpcService service =
        new NotificationGrpcService(notificationService, new SimpleMeterRegistry());

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
    assertEquals("INTERNAL", ref.get().getError().getCode());
  }

  @Test
  void sendNotificationRequiresAdminRole() {
    NotificationService notificationService = Mockito.mock(NotificationService.class);
    SessionContext.setContext("1", List.of("player"), Map.of());
    NotificationGrpcService service =
        new NotificationGrpcService(notificationService, new SimpleMeterRegistry());

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
    assertEquals("PERMISSION_DENIED", ref.get().getError().getCode());
  }

  @Test
  void sendNotificationRejectsZeroTenantIdBeforeDispatch() {
    NotificationService notificationService = Mockito.mock(NotificationService.class);
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    NotificationGrpcService service =
        new NotificationGrpcService(notificationService, new SimpleMeterRegistry());

    AtomicReference<SendNotificationResponse> ref = new AtomicReference<>();
    service.sendNotification(
        SendNotificationRequest.newBuilder()
            .setTenantId("0")
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
    assertFalse(ref.get().getSuccess());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("tenantId must be positive", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(notificationService);
  }
}
