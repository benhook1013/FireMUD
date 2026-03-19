package net.firedevops.firemud.springcloudgateway.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.gateway.v1.PingRequest;
import net.firedevops.firemud.gateway.v1.PingResponse;
import net.firedevops.firemud.gateway.v1.RemoveRouteRequest;
import net.firedevops.firemud.gateway.v1.RemoveRouteResponse;
import net.firedevops.firemud.gateway.v1.UpsertRouteRequest;
import net.firedevops.firemud.gateway.v1.UpsertRouteResponse;
import net.firedevops.firemud.springcloudgateway.service.GatewayRoute;
import net.firedevops.firemud.springcloudgateway.service.GatewayRouteService;
import org.junit.jupiter.api.Test;

class GatewayManagementGrpcServiceTest {

  @Test
  void pingReturnsPong() {
    GatewayRouteService routeService = mock(GatewayRouteService.class);
    GatewayManagementGrpcService service =
        new GatewayManagementGrpcService(routeService, new SimpleMeterRegistry());

    AtomicReference<PingResponse> ref = new AtomicReference<>();
    service.ping(
        PingRequest.getDefaultInstance(),
        new StreamObserver<>() {
          @Override
          public void onNext(PingResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertEquals("pong", ref.get().getMessage());
  }

  @Test
  void upsertRouteValidCallsService() {
    GatewayRouteService routeService = mock(GatewayRouteService.class);
    when(routeService.upsert(any())).thenReturn(new GatewayRoute("id", "http://u", null, null));
    GatewayManagementGrpcService service =
        new GatewayManagementGrpcService(routeService, new SimpleMeterRegistry());

    UpsertRouteRequest req =
        UpsertRouteRequest.newBuilder().setRouteId("id").setUri("http://u").build();
    AtomicReference<UpsertRouteResponse> ref = new AtomicReference<>();
    service.upsertRoute(
        req,
        new StreamObserver<>() {
          @Override
          public void onNext(UpsertRouteResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    verify(routeService).upsert(any());
    assertTrue(ref.get().getSuccess());
  }

  @Test
  void upsertRouteMissingFieldsReturnsError() {
    GatewayRouteService routeService = mock(GatewayRouteService.class);
    GatewayManagementGrpcService service =
        new GatewayManagementGrpcService(routeService, new SimpleMeterRegistry());

    UpsertRouteRequest req = UpsertRouteRequest.newBuilder().build();
    AtomicReference<UpsertRouteResponse> ref = new AtomicReference<>();
    service.upsertRoute(
        req,
        new StreamObserver<>() {
          @Override
          public void onNext(UpsertRouteResponse value) {
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
  }

  @Test
  void removeRouteNotFoundReturnsErrorDetail() {
    GatewayRouteService routeService = mock(GatewayRouteService.class);
    when(routeService.remove("missing")).thenReturn(false);
    GatewayManagementGrpcService service =
        new GatewayManagementGrpcService(routeService, new SimpleMeterRegistry());

    RemoveRouteRequest req = RemoveRouteRequest.newBuilder().setRouteId("missing").build();
    AtomicReference<RemoveRouteResponse> ref = new AtomicReference<>();
    service.removeRoute(
        req,
        new StreamObserver<>() {
          @Override
          public void onNext(RemoveRouteResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertFalse(ref.get().getSuccess());
    assertEquals("NOT_FOUND", ref.get().getError().getCode());
  }
}
