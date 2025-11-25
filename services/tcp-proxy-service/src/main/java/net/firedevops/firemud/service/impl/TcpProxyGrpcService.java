package net.firedevops.firemud.service.impl;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.service.PingService;
import net.firedevops.firemud.service.TcpProxyEventService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectRequest;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectResponse;
import net.firedevops.firemud.tcpproxy.v1.PingRequest;
import net.firedevops.firemud.tcpproxy.v1.PingResponse;
import net.firedevops.firemud.tcpproxy.v1.PushBufferedInputRequest;
import net.firedevops.firemud.tcpproxy.v1.PushBufferedInputResponse;
import net.firedevops.firemud.tcpproxy.v1.TcpProxyServiceGrpc;
import org.lognet.springboot.grpc.GRpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        "NotifyDisconnect for session {} tenant {}", request.getSessionId(), request.getTenantId());
    try {
      NotifyDisconnectResponse response =
          eventService.notifyDisconnect(request.getSessionId(), request.getTenantId());
      NotifyDisconnectResponse safe = ensureErrorDetail(response, "NotifyDisconnect");
      logIfError(safe.getError(), "NotifyDisconnect");
      responseObserver.onNext(safe);
      responseObserver.onCompleted();
    } catch (RuntimeException ex) {
      logger.warn("NotifyDisconnect failed", ex);
      ErrorDetail error = error("INTERNAL", "NotifyDisconnect failed");
      logIfError(error, "NotifyDisconnect");
      responseObserver.onNext(NotifyDisconnectResponse.newBuilder().setError(error).build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "tcpproxyGrpc.pushBufferedInput")
  public void pushBufferedInput(
      PushBufferedInputRequest request,
      StreamObserver<PushBufferedInputResponse> responseObserver) {
    logger.info(
        "PushBufferedInput for session {} commands {}",
        request.getSessionId(),
        request.getCommandsCount());
    try {
      PushBufferedInputResponse response =
          eventService.pushBufferedInput(
              request.getSessionId(), request.getCommandsList(), request.getTenantId());
      PushBufferedInputResponse safe = ensureErrorDetail(response, "PushBufferedInput");
      logIfError(safe.getError(), "PushBufferedInput");
      responseObserver.onNext(safe);
      responseObserver.onCompleted();
    } catch (RuntimeException ex) {
      logger.warn("PushBufferedInput failed", ex);
      ErrorDetail error = error("INTERNAL", "PushBufferedInput failed");
      logIfError(error, "PushBufferedInput");
      responseObserver.onNext(PushBufferedInputResponse.newBuilder().setError(error).build());
      responseObserver.onCompleted();
    }
  }

  private NotifyDisconnectResponse ensureErrorDetail(
      NotifyDisconnectResponse response, String operation) {
    if (response.hasError()) {
      return response;
    }
    ErrorDetail detail = ok(operation + " completed");
    return NotifyDisconnectResponse.newBuilder(response).setError(detail).build();
  }

  private PushBufferedInputResponse ensureErrorDetail(
      PushBufferedInputResponse response, String operation) {
    if (response.hasError()) {
      return response;
    }
    ErrorDetail detail = ok(operation + " completed");
    return PushBufferedInputResponse.newBuilder(response).setError(detail).build();
  }

  private ErrorDetail ok(String message) {
    return ErrorDetail.newBuilder().setCode(OK).setMessage(message).build();
  }

  private ErrorDetail error(String code, String message) {
    return ErrorDetail.newBuilder().setCode(code).setMessage(message).build();
  }

  private void logIfError(ErrorDetail detail, String operation) {
    if (detail == null || OK.equals(detail.getCode())) {
      return;
    }
    logger.warn("{} returned error {}: {}", operation, detail.getCode(), detail.getMessage());
  }
}
