package net.firedevops.firemud.springcloudgateway.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.gateway.v1.GatewayManagementServiceGrpc;
import net.firedevops.firemud.gateway.v1.PingRequest;
import net.firedevops.firemud.gateway.v1.PingResponse;
import net.firedevops.firemud.gateway.v1.RemoveRouteRequest;
import net.firedevops.firemud.gateway.v1.RemoveRouteResponse;
import net.firedevops.firemud.gateway.v1.UpsertRouteRequest;
import net.firedevops.firemud.gateway.v1.UpsertRouteResponse;
import net.firedevops.firemud.springcloudgateway.service.GatewayRoute;
import net.firedevops.firemud.springcloudgateway.service.GatewayRouteService;
import org.springframework.grpc.server.service.GrpcService;

/** gRPC implementation for remote gateway management. */
@GrpcService
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected route service is kept internal")
public class GatewayManagementGrpcService
    extends GatewayManagementServiceGrpc.GatewayManagementServiceImplBase {
  private final GatewayRouteService routeService;

  public GatewayManagementGrpcService(GatewayRouteService routeService) {
    this.routeService = routeService;
  }

  @Override
  @Timed(value = "gatewayGrpc.ping")
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    PingResponse response = PingResponse.newBuilder().setMessage("pong").build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gatewayGrpc.upsertRoute")
  public void upsertRoute(
      UpsertRouteRequest request, StreamObserver<UpsertRouteResponse> responseObserver) {
    if (request.getRouteId().isBlank() || request.getUri().isBlank()) {
      responseObserver.onError(
          io.grpc.Status.INVALID_ARGUMENT
              .withDescription("routeId and uri are required")
              .asRuntimeException());
      return;
    }
    GatewayRoute route =
        new GatewayRoute(
            request.getRouteId(),
            request.getUri(),
            request.getPredicatesList(),
            request.getFiltersList());
    routeService.upsert(route);
    UpsertRouteResponse response = UpsertRouteResponse.newBuilder().setSuccess(true).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gatewayGrpc.removeRoute")
  public void removeRoute(
      RemoveRouteRequest request, StreamObserver<RemoveRouteResponse> responseObserver) {
    boolean removed = routeService.remove(request.getRouteId());
    if (removed) {
      RemoveRouteResponse response = RemoveRouteResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } else {
      RemoveRouteResponse response =
          RemoveRouteResponse.newBuilder()
              .setSuccess(false)
              .setError(
                  net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                      .setCode("NOT_FOUND")
                      .setMessage("route not found")
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }
}
