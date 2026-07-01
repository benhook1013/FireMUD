package net.firedevops.firemud.tcpproxy.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.tcpproxy.service.PingService;
import net.firedevops.firemud.tcpproxy.service.TcpProxyEventService;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectRequest;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectResponse;
import net.firedevops.firemud.tcpproxy.v1.PingRequest;
import net.firedevops.firemud.tcpproxy.v1.PingResponse;
import net.firedevops.firemud.tcpproxy.v1.TcpProxyServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

/** gRPC endpoints for the TCP Proxy Service. */
@GrpcService
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected services and registry are Spring-managed collaborators.")
public class TcpProxyGrpcService extends TcpProxyServiceGrpc.TcpProxyServiceImplBase {
  private static final Logger logger = LoggerFactory.getLogger(TcpProxyGrpcService.class);
  private final PingService pingService;
  private final TcpProxyEventService eventService;
  private final MeterRegistry meterRegistry;

  public TcpProxyGrpcService(
      PingService pingService, TcpProxyEventService eventService, MeterRegistry meterRegistry) {
    this.pingService = pingService;
    this.eventService = eventService;
    this.meterRegistry = meterRegistry;
  }

  @Override
  @Timed(value = "tcpproxyGrpc.ping")
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    String msg = pingService.ping();
    PingResponse response = PingResponse.newBuilder().setMessage(msg).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "tcpproxyGrpc.notifyDisconnect")
  public void notifyDisconnect(
      NotifyDisconnectRequest request, StreamObserver<NotifyDisconnectResponse> responseObserver) {
    logger.info(
        "NotifyDisconnect for session {} tenant {} proxyConnectionId {} disconnectSequence {}",
        request.getSessionId(),
        request.getTenantId(),
        request.getProxyConnectionId(),
        request.getDisconnectSequence());
    try {
      NotifyDisconnectResponse response =
          eventService.notifyDisconnect(
              request.getGameInstanceId(),
              request.getTenantId(),
              request.getProxyConnectionId(),
              request.getDisconnectSequence());
      NotifyDisconnectResponse normalized = ensureErrorDetail(response, "NotifyDisconnect");
      GrpcAppErrors.logIfError(logger, "NotifyDisconnect", normalized.getError());
      responseObserver.onNext(normalized);
      responseObserver.onCompleted();
    } catch (RuntimeException ex) {
      ErrorDetail error = GrpcAppErrors.internal(meterRegistry, logger, "NotifyDisconnect", ex);
      responseObserver.onNext(NotifyDisconnectResponse.newBuilder().setError(error).build());
      responseObserver.onCompleted();
    }
  }

  private NotifyDisconnectResponse ensureErrorDetail(
      NotifyDisconnectResponse response, String operation) {
    if (response == null) {
      return NotifyDisconnectResponse.newBuilder()
          .setError(
              GrpcAppErrors.error(
                  meterRegistry,
                  logger,
                  operation,
                  "INTERNAL",
                  operation + " returned no response"))
          .build();
    }
    NotifyDisconnectResponse safe = response;
    ErrorDetail detail =
        safe.hasError()
            ? GrpcAppErrors.normalize(
                safe.getError(), "INTERNAL", operation + " returned no details")
            : GrpcAppErrors.ok(operation + " completed");
    return NotifyDisconnectResponse.newBuilder(safe).setError(detail).build();
  }
}
