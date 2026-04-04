package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.gamesession.command.text.GameplayLoggingContext;
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.DisconnectDeduplicationService;
import net.firedevops.firemud.gamesession.service.PingService;
import net.firedevops.firemud.gamesession.service.SessionStateService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectRequest;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectResponse;
import net.firedevops.firemud.tcpproxy.v1.PingRequest;
import net.firedevops.firemud.tcpproxy.v1.PingResponse;
import net.firedevops.firemud.tcpproxy.v1.TcpProxyServiceGrpc;
import org.slf4j.Logger;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.util.StringUtils;

/** gRPC hooks used by the TcpProxyService to coordinate disconnects and buffered input. */
@GrpcService
public final class TcpProxyServiceImpl extends TcpProxyServiceGrpc.TcpProxyServiceImplBase {
  private static final Logger logger = LoggingUtil.getLogger(TcpProxyServiceImpl.class);
  private static final String SUSPENDED_STATUS = "SUSPENDED";
  private static final String DUPLICATE_DISCONNECT_METRIC =
      "gamesession.notifydisconnect.duplicate";
  private static final String MISSING_CONTEXT_METRIC =
      "gamesession.notifydisconnect.missing_context";

  private final GameInstanceRepository repository;
  private final SessionStateService sessionStateService;
  private final MeterRegistry meterRegistry;
  private final PingService pingService;
  private final DevIsolatedProperties devIsolatedProperties;
  private final DisconnectDeduplicationService disconnectDeduplicationService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected repository/services are internal Spring collaborators")
  public TcpProxyServiceImpl(
      GameInstanceRepository repository,
      SessionStateService sessionStateService,
      MeterRegistry meterRegistry,
      PingService pingService,
      DevIsolatedProperties devIsolatedProperties,
      DisconnectDeduplicationService disconnectDeduplicationService) {
    this.repository = repository;
    this.sessionStateService = sessionStateService;
    this.meterRegistry = meterRegistry;
    this.pingService = pingService;
    this.devIsolatedProperties = devIsolatedProperties;
    this.disconnectDeduplicationService = disconnectDeduplicationService;
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
    String gameInstanceIdText =
        StringUtils.hasText(request.getGameInstanceId())
            ? request.getGameInstanceId()
            : request.getSessionId();
    try (GameplayLoggingContext ignored =
        GameplayLoggingContext.open(request.getTenantId(), gameInstanceIdText, null, null)) {
      logger.debug(
          "NotifyDisconnect session={} gameInstanceId={} tenant={} proxyConnectionId={} disconnectSequence={}",
          request.getSessionId(),
          request.getGameInstanceId(),
          request.getTenantId(),
          request.getProxyConnectionId(),
          request.getDisconnectSequence());
      ErrorDetail error = handleNotifyDisconnect(request, gameInstanceIdText);
      responseObserver.onNext(NotifyDisconnectResponse.newBuilder().setError(error).build());
      responseObserver.onCompleted();
    }
  }

  private ErrorDetail handleNotifyDisconnect(
      NotifyDisconnectRequest request, String gameInstanceIdText) {
    if (StringUtils.hasText(request.getProxyConnectionId())
        && request.getDisconnectSequence() > 0) {
      if (!disconnectDeduplicationService.shouldProcess(
          request.getProxyConnectionId(), request.getDisconnectSequence())) {
        meterRegistry.counter(DUPLICATE_DISCONNECT_METRIC).increment();
        return GrpcAppErrors.ok("Duplicate disconnect ignored");
      }
    }

    if (!StringUtils.hasText(gameInstanceIdText) || !StringUtils.hasText(request.getTenantId())) {
      meterRegistry.counter(MISSING_CONTEXT_METRIC).increment();
      return GrpcAppErrors.ok("Disconnect recorded (no proxy bootstrap metadata)");
    }
    SessionValidationResult validation = validateSession(gameInstanceIdText, request.getTenantId());
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
      return GrpcAppErrors.ok("Disconnect recorded");
    } catch (RuntimeException ex) {
      logger.error(
          "Failed to save suspended session state tenantId={} gameInstanceId={} proxyConnectionId={} disconnectSequence={}",
          request.getTenantId(),
          gameInstanceIdText,
          request.getProxyConnectionId(),
          request.getDisconnectSequence(),
          ex);
      return GrpcAppErrors.error(meterRegistry, "INTERNAL", "Failed to update session state");
    }
  }

  private SessionValidationResult validateSession(String sessionIdText, String tenantIdText) {
    if (!StringUtils.hasText(sessionIdText) || !StringUtils.hasText(tenantIdText)) {
      return SessionValidationResult.failed(
          GrpcAppErrors.error(
              meterRegistry, "INVALID_ARGUMENT", "sessionId and tenantId are required"));
    }
    long sessionId;
    long tenantId;
    try {
      sessionId = Long.parseLong(sessionIdText);
    } catch (NumberFormatException ex) {
      return SessionValidationResult.failed(
          GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", "sessionId must be numeric"));
    }
    try {
      tenantId = Long.parseLong(tenantIdText);
    } catch (NumberFormatException ex) {
      return SessionValidationResult.failed(
          GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", "tenantId must be numeric"));
    }
    // Dev-isolated mode never persists GameInstance records, so this lookup currently always fails
    // and propagates NOT_FOUND. We should skip the DB check or seed the session state when
    // dev-isolated
    // mode is enabled so buffered input/disconnect hooks remain usable in that profile.
    if (devIsolatedProperties.isDevIsolated()) {
      return new SessionValidationResult(
          sessionId, tenantId, buildDevIsolatedInstance(sessionId, tenantId), null);
    }
    Optional<GameInstance> maybeInstance = repository.findById(sessionId);
    if (maybeInstance.isEmpty()) {
      return SessionValidationResult.failed(
          GrpcAppErrors.error(meterRegistry, "NOT_FOUND", "Session not found"));
    }
    GameInstance instance = maybeInstance.get();
    if (!instance.getTenantId().equals(tenantId)) {
      return SessionValidationResult.failed(
          GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", "Tenant does not own session"));
    }
    return new SessionValidationResult(sessionId, tenantId, instance, null);
  }

  private GameInstance buildDevIsolatedInstance(long sessionId, long tenantId) {
    GameInstance instance = new GameInstance();
    instance.setId(sessionId);
    instance.setTenantId(tenantId);
    instance.setRuntimeVersion("dev-isolated");
    instance.setScriptPatchVersion(null);
    instance.setOwnerAccountId(0L);
    instance.setStatus("RUNNING");
    return instance;
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
