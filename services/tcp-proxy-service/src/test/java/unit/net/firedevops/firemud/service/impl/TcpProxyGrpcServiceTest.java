package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.service.PingService;
import net.firedevops.firemud.service.TcpProxyEventService;
import net.firedevops.firemud.tcpproxy.v1.PingRequest;
import net.firedevops.firemud.tcpproxy.v1.PingResponse;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectResponse;
import net.firedevops.firemud.tcpproxy.v1.PushBufferedInputResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TcpProxyGrpcServiceTest {
  @Test
  void pingReturnsPong() {
    PingService pingService = Mockito.mock(PingService.class);
    Mockito.when(pingService.ping()).thenReturn("pong");
    TcpProxyEventService eventService = Mockito.mock(TcpProxyEventService.class);
    TcpProxyGrpcService service = new TcpProxyGrpcService(pingService, eventService);

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
    Mockito.when(eventService.notifyDisconnect(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(upstream);

    TcpProxyGrpcService service = new TcpProxyGrpcService(pingService, eventService);
    AtomicReference<NotifyDisconnectResponse> ref = new AtomicReference<>();

    service.notifyDisconnect(
        net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectRequest.newBuilder()
            .setSessionId("abc")
            .setTenantId("tenant")
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
  void pushBufferedInputSurfacesErrorDetail() {
    PingService pingService = Mockito.mock(PingService.class);
    TcpProxyEventService eventService = Mockito.mock(TcpProxyEventService.class);
    PushBufferedInputResponse upstream =
        PushBufferedInputResponse.newBuilder()
            .setError(ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage("bad")
                .build())
            .build();
    Mockito.when(
            eventService.pushBufferedInput(
                Mockito.anyString(), Mockito.anyList(), Mockito.anyString()))
        .thenReturn(upstream);

    TcpProxyGrpcService service = new TcpProxyGrpcService(pingService, eventService);
    AtomicReference<PushBufferedInputResponse> ref = new AtomicReference<>();

    service.pushBufferedInput(
        net.firedevops.firemud.tcpproxy.v1.PushBufferedInputRequest.newBuilder()
            .setSessionId("abc")
            .setTenantId("tenant")
            .addCommands("look")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(PushBufferedInputResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
  }

  @Test
  void pushBufferedInputReturnsErrorDetailWithoutIncrementingGrpcCounter() {
    PingService pingService = Mockito.mock(PingService.class);
    TcpProxyEventService eventService = Mockito.mock(TcpProxyEventService.class);
    PushBufferedInputResponse upstream =
        PushBufferedInputResponse.newBuilder()
            .setError(
                ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage("bad").build())
            .build();
    Mockito.when(
            eventService.pushBufferedInput(
                Mockito.anyString(), Mockito.anyList(), Mockito.anyString()))
        .thenReturn(upstream);

    TcpProxyGrpcService service = new TcpProxyGrpcService(pingService, eventService);
    AtomicReference<PushBufferedInputResponse> ref = new AtomicReference<>();

    service.pushBufferedInput(
        net.firedevops.firemud.tcpproxy.v1.PushBufferedInputRequest.newBuilder()
            .setSessionId("abc")
            .setTenantId("tenant")
            .addCommands("look")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(PushBufferedInputResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
  }

  @Test
  void notifyDisconnectReturnsInternalOnFailure() {
    PingService pingService = Mockito.mock(PingService.class);
    TcpProxyEventService eventService = Mockito.mock(TcpProxyEventService.class);
    Mockito.when(eventService.notifyDisconnect(Mockito.anyString(), Mockito.anyString()))
        .thenThrow(new RuntimeException("boom"));

    TcpProxyGrpcService service = new TcpProxyGrpcService(pingService, eventService);
    AtomicReference<NotifyDisconnectResponse> ref = new AtomicReference<>();

    service.notifyDisconnect(
        net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectRequest.newBuilder()
            .setSessionId("abc")
            .setTenantId("tenant")
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
}
