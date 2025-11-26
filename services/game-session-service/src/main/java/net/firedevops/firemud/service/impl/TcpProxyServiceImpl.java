package net.firedevops.firemud.service.impl;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.dto.GameInstanceDto;
import net.firedevops.firemud.entity.GameInstance;
import net.firedevops.firemud.repository.GameInstanceRepository;
import net.firedevops.firemud.service.CommandService;
import net.firedevops.firemud.service.PingService;
import net.firedevops.firemud.service.SessionStateService;
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
import org.springframework.util.StringUtils;

/** gRPC hooks used by the TcpProxyService to coordinate disconnects and buffered input. */
@GRpcService
public final class TcpProxyServiceImpl extends TcpProxyServiceGrpc.TcpProxyServiceImplBase {
  private static final Logger logger = LoggingUtil.getLogger(TcpProxyServiceImpl.class);
  private static final String OK_CODE = "OK";
  private static final String SUSPENDED_STATUS = "SUSPENDED";

  private final CommandService commandService;
  private final GameInstanceRepository repository;
  private final SessionStateService sessionStateService;
  private final MeterRegistry meterRegistry;
  private final PingService pingService;

  public TcpProxyServiceImpl(
      CommandService commandService,
      GameInstanceRepository repository,
      SessionStateService sessionStateService,
      MeterRegistry meterRegistry,
      PingService pingService) {
    this.commandService = commandService;
    this.repository = repository;
    this.sessionStateService = sessionStateService;
    this.meterRegistry = meterRegistry;
    this.pingService = pingService;
  }

  @Override
  @Timed("tcpProxyGrpc.ping")
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    String message = pingService.ping();
    responseObserver.onNext(PingResponse.newBuilder().setMessage(message).build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed("tcpProxyGrpc.notifyDisconnect")
  public void notifyDisconnect(
      NotifyDisconnectRequest request, StreamObserver<NotifyDisconnectResponse> responseObserver) {
    logger.debug(
        "NotifyDisconnect session={} tenant={}", request.getSessionId(), request.getTenantId());
    ErrorDetail error = handleNotifyDisconnect(request);
    responseObserver.onNext(NotifyDisconnectResponse.newBuilder().setError(error).build());
    responseObserver.onCompleted();
  }

  private ErrorDetail handleNotifyDisconnect(NotifyDisconnectRequest request) {
    SessionValidationResult validation =
        validateSession(request.getSessionId(), request.getTenantId());
    if (validation.hasError()) {
      return validation.errorDetail();
    }
    GameInstance instance = validation.instance();
    GameInstanceDto suspendedState =
        new GameInstanceDto(
            instance.getId(),
            instance.getTenantId(),
            instance.getRuntimeVersion(),
            instance.getScriptPatchVersion(),
            instance.getOwnerAccountId(),
            SUSPENDED_STATUS);
    try {
      sessionStateService.saveState(suspendedState);
      return ok("Disconnect recorded");
    } catch (RuntimeException ex) {
      logger.error("Failed to save suspended session state", ex);
      return error("INTERNAL", "Failed to update session state");
    }
  }

  @Override
  @Timed("tcpProxyGrpc.pushBufferedInput")
  public void pushBufferedInput(
      PushBufferedInputRequest request, StreamObserver<PushBufferedInputResponse> responseObserver) {
    logger.debug(
        "PushBufferedInput session={} tenant={} commands={}",
        request.getSessionId(),
        request.getTenantId(),
        request.getCommandsCount());
    ErrorDetail error = handleBufferedInput(request);
    responseObserver.onNext(PushBufferedInputResponse.newBuilder().setError(error).build());
    responseObserver.onCompleted();
  }

  private ErrorDetail handleBufferedInput(PushBufferedInputRequest request) {
    if (request.getCommandsCount() == 0) {
      return error("INVALID_ARGUMENT", "At least one buffered command is required");
    }
    SessionValidationResult validation =
        validateSession(request.getSessionId(), request.getTenantId());
    if (validation.hasError()) {
      return validation.errorDetail();
    }
    String sessionIdText = String.valueOf(validation.sessionId());
    for (String command : request.getCommandsList()) {
      try {
        var result = commandService.enqueue(sessionIdText, command, false);
        if (!result.accepted()) {
          return error(result.errorCode(), result.errorMessage());
        }
      } catch (RuntimeException ex) {
        logger.error("Failed to enqueue buffered command", ex);
        return error("INTERNAL", "Failed to enqueue buffered input");
      }
    }
    return ok("Buffered commands accepted");
  }

  private SessionValidationResult validateSession(String sessionIdText, String tenantIdText) {
    if (!StringUtils.hasText(sessionIdText) || !StringUtils.hasText(tenantIdText)) {
      return SessionValidationResult.failed(
          error("INVALID_ARGUMENT", "sessionId and tenantId are required"));
    }
    long sessionId;
    long tenantId;
    try {
      sessionId = Long.parseLong(sessionIdText);
    } catch (NumberFormatException ex) {
      return SessionValidationResult.failed(
          error("INVALID_ARGUMENT", "sessionId must be numeric"));
    }
    try {
      tenantId = Long.parseLong(tenantIdText);
    } catch (NumberFormatException ex) {
      return SessionValidationResult.failed(
          error("INVALID_ARGUMENT", "tenantId must be numeric"));
    }
    Optional<GameInstance> maybeInstance = repository.findById(sessionId);
    if (maybeInstance.isEmpty()) {
      return SessionValidationResult.failed(error("NOT_FOUND", "Session not found"));
    }
    GameInstance instance = maybeInstance.get();
    if (!instance.getTenantId().equals(tenantId)) {
      return SessionValidationResult.failed(
          error("INVALID_ARGUMENT", "Tenant does not own session"));
    }
    return new SessionValidationResult(sessionId, tenantId, instance, null);
  }

  private ErrorDetail error(String code, String message) {
    meterRegistry.counter("grpc.app_error", "code", code).increment();
    return ErrorDetail.newBuilder().setCode(code).setMessage(message).build();
  }

  private ErrorDetail ok(String message) {
    return ErrorDetail.newBuilder().setCode(OK_CODE).setMessage(message).build();
  }

  private record SessionValidationResult(
      long sessionId, long tenantId, GameInstance instance, ErrorDetail errorDetail) {
    static SessionValidationResult failed(ErrorDetail errorDetail) {
      return new SessionValidationResult(0L, 0L, null, errorDetail);
    }

    boolean hasError() {
      return errorDetail != null;
    }
  }
}
