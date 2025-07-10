package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.gateway.v1.PingRequest;
import net.firedevops.firemud.gateway.v1.PingResponse;
import net.firedevops.firemud.gateway.v1.RouteDefinition;
import net.firedevops.firemud.gateway.v1.RouteRequest;
import net.firedevops.firemud.gateway.v1.RouteResponse;
import net.firedevops.firemud.service.GatewayRoute;
import net.firedevops.firemud.service.GatewayRouteService;
import org.junit.jupiter.api.Test;

class GatewayManagementGrpcServiceTest {

  @Test
  void pingReturnsPong() {
    GatewayRouteService routeService = mock(GatewayRouteService.class);
    GatewayManagementGrpcService service = new GatewayManagementGrpcService(routeService);

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
    GatewayManagementGrpcService service = new GatewayManagementGrpcService(routeService);

    RouteDefinition req = RouteDefinition.newBuilder().setRouteId("id").setUri("http://u").build();
    AtomicReference<RouteResponse> ref = new AtomicReference<>();
    service.upsertRoute(
        req,
        new StreamObserver<>() {
          @Override
          public void onNext(RouteResponse value) {
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
    GatewayManagementGrpcService service = new GatewayManagementGrpcService(routeService);

    RouteDefinition req = RouteDefinition.newBuilder().build();
    AtomicReference<Throwable> err = new AtomicReference<>();
    service.upsertRoute(
        req,
        new StreamObserver<>() {
          @Override
          public void onNext(RouteResponse value) {}

          @Override
          public void onError(Throwable t) {
            err.set(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertNotNull(err.get());
    StatusRuntimeException ex = (StatusRuntimeException) err.get();
    assertEquals(io.grpc.Status.INVALID_ARGUMENT.getCode(), ex.getStatus().getCode());
  }

  @Test
  void removeRouteNotFoundReturnsErrorDetail() {
    GatewayRouteService routeService = mock(GatewayRouteService.class);
    when(routeService.remove("missing")).thenReturn(false);
    GatewayManagementGrpcService service = new GatewayManagementGrpcService(routeService);

    RouteRequest req = RouteRequest.newBuilder().setRouteId("missing").build();
    AtomicReference<RouteResponse> ref = new AtomicReference<>();
    service.removeRoute(
        req,
        new StreamObserver<>() {
          @Override
          public void onNext(RouteResponse value) {
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
