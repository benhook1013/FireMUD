package net.firedevops.firemud.tcpproxy.service.impl;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.tcpproxy.service.PingService;
import net.firedevops.firemud.tcpproxy.service.TcpProxyEventService;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectRequest;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectResponse;
import net.firedevops.firemud.tcpproxy.v1.PingRequest;
import net.firedevops.firemud.tcpproxy.v1.PingResponse;
import net.firedevops.firemud.tcpproxy.v1.TcpProxyServiceGrpc;
import org.lognet.springboot.grpc.GRpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/** gRPC endpoints for the TCP Proxy Service. */
@GRpcService
public class TcpProxyGrpcService extends TcpProxyServiceGrpc.TcpProxyServiceImplBase {
  private static final Logger logger = LoggerFactory.getLogger(TcpProxyGrpcService.class);
  private static final String OK = "OK";
  private final PingService pingService;
  private final TcpProxyEventService eventService;

  public TcpProxyGrpcService(PingService pingService, TcpProxyEventService eventService) {
    this.pingService = pingService;
    this.eventService = eventService;
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
      String gameInstanceId =
          StringUtils.hasText(request.getGameInstanceId())
              ? request.getGameInstanceId()
              : request.getSessionId();
      NotifyDisconnectResponse response =
          eventService.notifyDisconnect(
              gameInstanceId,
              request.getTenantId(),
              request.getProxyConnectionId(),
              request.getDisconnectSequence());
      NotifyDisconnectResponse normalized = ensureErrorDetail(response, "NotifyDisconnect");
      logIfError(normalized.getError(), "NotifyDisconnect");
      responseObserver.onNext(normalized);
      responseObserver.onCompleted();
    } catch (RuntimeException ex) {
      logger.warn("NotifyDisconnect failed", ex);
      ErrorDetail error = error("INTERNAL", "NotifyDisconnect failed");
      logIfError(error, "NotifyDisconnect");
      responseObserver.onNext(NotifyDisconnectResponse.newBuilder().setError(error).build());
      responseObserver.onCompleted();
    }
  }

  private NotifyDisconnectResponse ensureErrorDetail(
      NotifyDisconnectResponse response, String operation) {
    if (response == null) {
      return NotifyDisconnectResponse.newBuilder()
          .setError(error("INTERNAL", operation + " returned no response"))
          .build();
    }
    NotifyDisconnectResponse safe = response;
    ErrorDetail detail =
        safe.hasError()
            ? normalizeDetail(safe.getError(), operation)
            : ok(operation + " completed");
    return NotifyDisconnectResponse.newBuilder(safe).setError(detail).build();
  }

  private ErrorDetail ok(String message) {
    return ErrorDetail.newBuilder().setCode(OK).setMessage(message).build();
  }

  private ErrorDetail error(String code, String message) {
    return ErrorDetail.newBuilder().setCode(code).setMessage(message).build();
  }

  private ErrorDetail normalizeDetail(ErrorDetail detail, String operation) {
    if (detail == null) {
      return error("INTERNAL", operation + " returned no details");
    }
    String code = StringUtils.hasText(detail.getCode()) ? detail.getCode() : "UNKNOWN";
    String message =
        StringUtils.hasText(detail.getMessage())
            ? detail.getMessage()
            : operation + " returned no message";
    return ErrorDetail.newBuilder(detail).setCode(code).setMessage(message).build();
  }

  private void logIfError(ErrorDetail detail, String operation) {
    if (detail == null || OK.equals(detail.getCode())) {
      return;
    }
    logger.warn("{} returned error {}: {}", operation, detail.getCode(), detail.getMessage());
  }
}
