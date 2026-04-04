package net.firedevops.firemud.springcloudgateway.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.gateway.v1.GatewayManagementServiceGrpc;
import net.firedevops.firemud.gateway.v1.PingRequest;
import net.firedevops.firemud.gateway.v1.PingResponse;
import net.firedevops.firemud.gateway.v1.RemoveRouteRequest;
import net.firedevops.firemud.gateway.v1.RemoveRouteResponse;
import net.firedevops.firemud.gateway.v1.UpsertRouteRequest;
import net.firedevops.firemud.gateway.v1.UpsertRouteResponse;
import net.firedevops.firemud.springcloudgateway.service.GatewayRoute;
import net.firedevops.firemud.springcloudgateway.service.GatewayRouteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;

/** gRPC implementation for remote gateway management. */
@GrpcService
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected route service is kept internal")
public class GatewayManagementGrpcService
    extends GatewayManagementServiceGrpc.GatewayManagementServiceImplBase {
  private static final Logger logger = LoggerFactory.getLogger(GatewayManagementGrpcService.class);
  private final GatewayRouteService routeService;
  private final MeterRegistry meterRegistry;

  public GatewayManagementGrpcService(
      GatewayRouteService routeService, MeterRegistry meterRegistry) {
    this.routeService = routeService;
    this.meterRegistry = meterRegistry;
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
    UpsertRouteResponse.Builder builder = UpsertRouteResponse.newBuilder();
    if (request.getRouteId().isBlank() || request.getUri().isBlank()) {
      builder.setSuccess(false);
      builder.setError(
          GrpcAppErrors.error(
              meterRegistry,
              logger,
              "UpsertRoute",
              "INVALID_ARGUMENT",
              "routeId and uri are required"));
      responseObserver.onNext(builder.build());
      responseObserver.onCompleted();
      return;
    }
    try {
      GatewayRoute route =
          new GatewayRoute(
              request.getRouteId(),
              request.getUri(),
              request.getPredicatesList(),
              request.getFiltersList());
      routeService
          .upsert(route)
          .map(
              ignored -> {
                builder.setSuccess(true);
                return builder.build();
              })
          .onErrorResume(
              IllegalArgumentException.class,
              ex ->
                  Mono.just(
                      builder
                          .setSuccess(false)
                          .setError(
                              GrpcAppErrors.error(
                                  meterRegistry,
                                  logger,
                                  "UpsertRoute",
                                  "INVALID_ARGUMENT",
                                  ex.getMessage()))
                          .build()))
          .onErrorResume(
              Exception.class,
              ex ->
                  Mono.just(
                      builder
                          .setSuccess(false)
                          .setError(
                              GrpcAppErrors.internal(meterRegistry, logger, "UpsertRoute", ex))
                          .build()))
          .subscribe(
              response -> {
                responseObserver.onNext(response);
                responseObserver.onCompleted();
              },
              responseObserver::onError);
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          builder
              .setSuccess(false)
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "UpsertRoute", "INVALID_ARGUMENT", ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gatewayGrpc.removeRoute")
  public void removeRoute(
      RemoveRouteRequest request, StreamObserver<RemoveRouteResponse> responseObserver) {
    routeService
        .remove(request.getRouteId())
        .map(
            removed ->
                removed
                    ? RemoveRouteResponse.newBuilder().setSuccess(true).build()
                    : RemoveRouteResponse.newBuilder()
                        .setSuccess(false)
                        .setError(
                            GrpcAppErrors.error(
                                meterRegistry,
                                logger,
                                "RemoveRoute",
                                "NOT_FOUND",
                                "route not found"))
                        .build())
        .onErrorResume(
            Exception.class,
            ex ->
                Mono.just(
                    RemoveRouteResponse.newBuilder()
                        .setSuccess(false)
                        .setError(GrpcAppErrors.internal(meterRegistry, logger, "RemoveRoute", ex))
                        .build()))
        .subscribe(
            response -> {
              responseObserver.onNext(response);
              responseObserver.onCompleted();
            },
            responseObserver::onError);
  }
}
