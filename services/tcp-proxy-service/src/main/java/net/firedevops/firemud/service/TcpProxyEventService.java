package net.firedevops.firemud.service;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectResponse;
import net.firedevops.firemud.tcpproxy.v1.PushBufferedInputResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Publishes TCP proxy lifecycle events to the Game Session Service. */
@Service
public class TcpProxyEventService {
  private static final Logger logger = LoggerFactory.getLogger(TcpProxyEventService.class);
  private static final String OK = "OK";

  private final TcpProxyEventClient client;
  private final MeterRegistry meterRegistry;

  public TcpProxyEventService(TcpProxyEventClient client, MeterRegistry meterRegistry) {
    this.client = client;
    this.meterRegistry = meterRegistry;
  }

  public NotifyDisconnectResponse notifyDisconnect(String sessionId, String tenantId) {
    if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(tenantId)) {
      return NotifyDisconnectResponse.newBuilder()
          .setError(error("INVALID_ARGUMENT", "sessionId and tenantId are required"))
          .build();
    }
    try {
      NotifyDisconnectResponse response = client.notifyDisconnect(sessionId, tenantId);
      return normalize(response, "Disconnect notification delivered");
    } catch (RuntimeException ex) {
      logger.warn("Failed to notify Game Session Service about disconnect", ex);
      return NotifyDisconnectResponse.newBuilder()
          .setError(error("UPSTREAM_FAILURE", "Failed to notify Game Session Service"))
          .build();
    }
  }

  public PushBufferedInputResponse pushBufferedInput(
      String sessionId, List<String> commands, String tenantId) {
    if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(tenantId)) {
      return PushBufferedInputResponse.newBuilder()
          .setError(error("INVALID_ARGUMENT", "sessionId and tenantId are required"))
          .build();
    }
    if (commands == null || commands.isEmpty()) {
      return PushBufferedInputResponse.newBuilder()
          .setError(error("INVALID_ARGUMENT", "At least one buffered command is required"))
          .build();
    }
    try {
      PushBufferedInputResponse response = client.pushBufferedInput(sessionId, commands, tenantId);
      return normalize(response, "Buffered commands forwarded");
    } catch (RuntimeException ex) {
      logger.warn("Failed to push buffered commands", ex);
      return PushBufferedInputResponse.newBuilder()
          .setError(error("UPSTREAM_FAILURE", "Failed to push buffered commands"))
          .build();
    }
  }

  private NotifyDisconnectResponse normalize(NotifyDisconnectResponse response, String okMessage) {
    NotifyDisconnectResponse safeResponse =
        response != null ? response : NotifyDisconnectResponse.getDefaultInstance();
    if (safeResponse.hasError()) {
      incrementIfError(safeResponse.getError());
      return safeResponse;
    }
    return NotifyDisconnectResponse.newBuilder(safeResponse)
        .setError(ok(okMessage))
        .build();
  }

  private PushBufferedInputResponse normalize(PushBufferedInputResponse response, String okMessage) {
    PushBufferedInputResponse safeResponse =
        response != null ? response : PushBufferedInputResponse.getDefaultInstance();
    if (safeResponse.hasError()) {
      incrementIfError(safeResponse.getError());
      return safeResponse;
    }
    return PushBufferedInputResponse.newBuilder(safeResponse)
        .setError(ok(okMessage))
        .build();
  }

  private ErrorDetail ok(String message) {
    return ErrorDetail.newBuilder().setCode(OK).setMessage(message).build();
  }

  private ErrorDetail error(String code, String message) {
    meterRegistry.counter("grpc.app_error", "code", code).increment();
    return ErrorDetail.newBuilder().setCode(code).setMessage(message).build();
  }

  private void incrementIfError(ErrorDetail detail) {
    if (detail == null || OK.equals(detail.getCode())) {
      return;
    }
    meterRegistry.counter("grpc.app_error", "code", detail.getCode()).increment();
  }
}
