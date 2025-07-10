package net.firedevops.firemud.service.impl;

import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.gateway.v1.GatewayManagementServiceGrpc;
import net.firedevops.firemud.gateway.v1.PingRequest;
import net.firedevops.firemud.gateway.v1.PingResponse;
import net.firedevops.firemud.gateway.v1.RouteDefinition;
import net.firedevops.firemud.gateway.v1.RouteRequest;
import net.firedevops.firemud.gateway.v1.RouteResponse;
import net.firedevops.firemud.service.GatewayRoute;
import net.firedevops.firemud.service.GatewayRouteService;
import org.lognet.springboot.grpc.GRpcService;

/** gRPC implementation for remote gateway management. */
@GRpcService
public class GatewayManagementGrpcService
    extends GatewayManagementServiceGrpc.GatewayManagementServiceImplBase {
  private final GatewayRouteService routeService;

  public GatewayManagementGrpcService(GatewayRouteService routeService) {
    this.routeService = routeService;
  }

  @Override
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    PingResponse response = PingResponse.newBuilder().setMessage("pong").build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void upsertRoute(RouteDefinition request, StreamObserver<RouteResponse> responseObserver) {
    GatewayRoute route =
        new GatewayRoute(
            request.getRouteId(),
            request.getUri(),
            request.getPredicatesList(),
            request.getFiltersList());
    routeService.upsert(route);
    RouteResponse response = RouteResponse.newBuilder().setSuccess(true).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void removeRoute(RouteRequest request, StreamObserver<RouteResponse> responseObserver) {
    routeService.remove(request.getRouteId());
    RouteResponse response = RouteResponse.newBuilder().setSuccess(true).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }
}
