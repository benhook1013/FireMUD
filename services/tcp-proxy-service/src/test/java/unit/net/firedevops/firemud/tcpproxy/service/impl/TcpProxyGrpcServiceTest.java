package net.firedevops.firemud.tcpproxy.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.tcpproxy.service.PingService;
import net.firedevops.firemud.tcpproxy.service.TcpProxyEventService;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectResponse;
import net.firedevops.firemud.tcpproxy.v1.PingRequest;
import net.firedevops.firemud.tcpproxy.v1.PingResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.util.StringUtils;

class TcpProxyGrpcServiceTest {
  private static TcpProxyGrpcService newService(
      PingService pingService, TcpProxyEventService eventService) {
    return new TcpProxyGrpcService(pingService, eventService, new SimpleMeterRegistry());
  }

  @Test
  void pingReturnsPong() {
    PingService pingService = Mockito.mock(PingService.class);
    Mockito.when(pingService.ping()).thenReturn("pong");
    TcpProxyEventService eventService = Mockito.mock(TcpProxyEventService.class);
    TcpProxyGrpcService service = newService(pingService, eventService);

    AtomicReference<PingResponse> ref = new AtomicReference<>();
    service.ping(
        PingRequest.getDefaultInstance(),
        new StreamObserver<PingResponse>() {
          @Override
          public void onNext(PingResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("pong", ref.get().getMessage());
  }

  @Test
  void notifyDisconnectReturnsErrorDetail() {
    PingService pingService = Mockito.mock(PingService.class);
    TcpProxyEventService eventService = Mockito.mock(TcpProxyEventService.class);
    NotifyDisconnectResponse upstream =
        NotifyDisconnectResponse.newBuilder()
            .setError(ErrorDetail.newBuilder().setCode("OK").setMessage("ok").build())
            .build();
    Mockito.when(
            eventService.notifyDisconnect(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyLong()))
        .thenReturn(upstream);

    TcpProxyGrpcService service = newService(pingService, eventService);
    AtomicReference<NotifyDisconnectResponse> ref = new AtomicReference<>();

    service.notifyDisconnect(
        net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectRequest.newBuilder()
            .setSessionId("abc")
            .setTenantId("tenant")
            .setProxyConnectionId("conn-1")
            .setDisconnectSequence(1)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(NotifyDisconnectResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("OK", ref.get().getError().getCode());
  }

  @Test
  void notifyDisconnectReturnsInternalOnFailure() {
    PingService pingService = Mockito.mock(PingService.class);
    TcpProxyEventService eventService = Mockito.mock(TcpProxyEventService.class);
    Mockito.when(
            eventService.notifyDisconnect(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyLong()))
        .thenThrow(new RuntimeException("boom"));

    TcpProxyGrpcService service = newService(pingService, eventService);
    AtomicReference<NotifyDisconnectResponse> ref = new AtomicReference<>();

    service.notifyDisconnect(
        net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectRequest.newBuilder()
            .setSessionId("abc")
            .setTenantId("tenant")
            .setProxyConnectionId("conn-1")
            .setDisconnectSequence(1)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(NotifyDisconnectResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("INTERNAL", ref.get().getError().getCode());
  }

  @Test
  void notifyDisconnectInjectsErrorDetailWhenDomainSkipsIt() {
    PingService pingService = Mockito.mock(PingService.class);
    TcpProxyEventService eventService = Mockito.mock(TcpProxyEventService.class);
    Mockito.when(
            eventService.notifyDisconnect(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyLong()))
        .thenReturn(NotifyDisconnectResponse.getDefaultInstance());

    TcpProxyGrpcService service = newService(pingService, eventService);
    AtomicReference<NotifyDisconnectResponse> ref = new AtomicReference<>();

    service.notifyDisconnect(
        net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectRequest.newBuilder()
            .setSessionId("abc")
            .setTenantId("tenant")
            .setProxyConnectionId("conn-1")
            .setDisconnectSequence(1)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(NotifyDisconnectResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("OK", ref.get().getError().getCode());
  }

  @Test
  void notifyDisconnectForwardsExplicitGameInstanceAndTenant() {
    PingService pingService = Mockito.mock(PingService.class);
    TcpProxyEventService eventService = Mockito.mock(TcpProxyEventService.class);
    NotifyDisconnectResponse upstream =
        NotifyDisconnectResponse.newBuilder()
            .setError(ErrorDetail.newBuilder().setCode("OK").setMessage("ok").build())
            .build();
    Mockito.when(
            eventService.notifyDisconnect(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyLong()))
        .thenReturn(upstream);

    TcpProxyGrpcService service = newService(pingService, eventService);

    service.notifyDisconnect(
        net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectRequest.newBuilder()
            .setSessionId("sess-123")
            .setGameInstanceId("runtime-77")
            .setTenantId("tenant-xyz")
            .setProxyConnectionId("conn-1")
            .setDisconnectSequence(1)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(NotifyDisconnectResponse value) {}

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    Mockito.verify(eventService).notifyDisconnect("runtime-77", "tenant-xyz", "conn-1", 1L);
  }

  @Test
  void notifyDisconnectDoesNotFallbackFromMissingGameInstanceIdToSessionId() {
    PingService pingService = Mockito.mock(PingService.class);
    TcpProxyEventService eventService = Mockito.mock(TcpProxyEventService.class);
    NotifyDisconnectResponse upstream =
        NotifyDisconnectResponse.newBuilder()
            .setError(ErrorDetail.newBuilder().setCode("OK").setMessage("ok").build())
            .build();
    Mockito.when(
            eventService.notifyDisconnect(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyLong()))
        .thenReturn(upstream);

    TcpProxyGrpcService service = newService(pingService, eventService);

    service.notifyDisconnect(
        net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectRequest.newBuilder()
            .setSessionId("sess-123")
            .setTenantId("tenant-xyz")
            .setProxyConnectionId("conn-1")
            .setDisconnectSequence(1)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(NotifyDisconnectResponse value) {}

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    Mockito.verify(eventService).notifyDisconnect("", "tenant-xyz", "conn-1", 1L);
  }

  @Test
  void notifyDisconnectFillsMissingErrorFields() {
    PingService pingService = Mockito.mock(PingService.class);
    TcpProxyEventService eventService = Mockito.mock(TcpProxyEventService.class);
    NotifyDisconnectResponse upstream =
        NotifyDisconnectResponse.newBuilder()
            .setError(ErrorDetail.newBuilder().setMessage("").build())
            .build();
    Mockito.when(
            eventService.notifyDisconnect(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyLong()))
        .thenReturn(upstream);

    TcpProxyGrpcService service = newService(pingService, eventService);
    AtomicReference<NotifyDisconnectResponse> ref = new AtomicReference<>();

    service.notifyDisconnect(
        net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectRequest.newBuilder()
            .setSessionId("abc")
            .setTenantId("tenant")
            .setProxyConnectionId("conn-1")
            .setDisconnectSequence(1)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(NotifyDisconnectResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("UNKNOWN", ref.get().getError().getCode());
    assertTrue(StringUtils.hasText(ref.get().getError().getMessage()));
  }
}
