package net.firedevops.firemud.springcloudgateway.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.CompletionException;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.security.RequireAdminRole;
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
  @RequireAdminRole
  public void upsertRoute(
      UpsertRouteRequest request, StreamObserver<UpsertRouteResponse> responseObserver) {
    respond(buildUpsertRouteResponse(request), responseObserver);
  }

  @Override
  @Timed(value = "gatewayGrpc.removeRoute")
  @RequireAdminRole
  public void removeRoute(
      RemoveRouteRequest request, StreamObserver<RemoveRouteResponse> responseObserver) {
    respond(buildRemoveRouteResponse(request), responseObserver);
  }

  private Mono<UpsertRouteResponse> buildUpsertRouteResponse(UpsertRouteRequest request) {
    return Mono.defer(
        () -> {
          if (request.getRouteId().isBlank() || request.getUri().isBlank()) {
            return Mono.just(
                UpsertRouteResponse.newBuilder()
                    .setSuccess(false)
                    .setError(
                        GrpcAppErrors.error(
                            meterRegistry,
                            logger,
                            "UpsertRoute",
                            "INVALID_ARGUMENT",
                            "routeId and uri are required"))
                    .build());
          }
          GatewayRoute route =
              new GatewayRoute(
                  request.getRouteId(),
                  request.getUri(),
                  request.getPredicatesList(),
                  request.getFiltersList());
          return routeService
              .upsert(route)
              .map(ignored -> UpsertRouteResponse.newBuilder().setSuccess(true).build())
              .onErrorResume(
                  IllegalArgumentException.class,
                  ex ->
                      Mono.just(
                          UpsertRouteResponse.newBuilder()
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
                          UpsertRouteResponse.newBuilder()
                              .setSuccess(false)
                              .setError(
                                  GrpcAppErrors.internal(meterRegistry, logger, "UpsertRoute", ex))
                              .build()));
        });
  }

  private Mono<RemoveRouteResponse> buildRemoveRouteResponse(RemoveRouteRequest request) {
    return routeService
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
            IllegalArgumentException.class,
            ex ->
                Mono.just(
                    RemoveRouteResponse.newBuilder()
                        .setSuccess(false)
                        .setError(
                            GrpcAppErrors.error(
                                meterRegistry,
                                logger,
                                "RemoveRoute",
                                "INVALID_ARGUMENT",
                                ex.getMessage()))
                        .build()))
        .onErrorResume(
            Exception.class,
            ex ->
                Mono.just(
                    RemoveRouteResponse.newBuilder()
                        .setSuccess(false)
                        .setError(GrpcAppErrors.internal(meterRegistry, logger, "RemoveRoute", ex))
                        .build()));
  }

  private <T> void respond(Mono<T> responseMono, StreamObserver<T> responseObserver) {
    responseMono
        .toFuture()
        .whenComplete(
            (response, error) -> {
              if (error != null) {
                responseObserver.onError(unwrapCompletionException(error));
                return;
              }
              responseObserver.onNext(response);
              responseObserver.onCompleted();
            });
  }

  private Throwable unwrapCompletionException(Throwable error) {
    if (error instanceof CompletionException completionException
        && completionException.getCause() != null) {
      return completionException.getCause();
    }
    return error;
  }
}
