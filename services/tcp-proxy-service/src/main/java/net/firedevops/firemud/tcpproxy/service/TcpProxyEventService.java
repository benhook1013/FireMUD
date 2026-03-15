package net.firedevops.firemud.tcpproxy.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectResponse;
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
  private final Timer connectionDurationTimer;
  private final Counter connectCounter;
  private final Counter disconnectCounter;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "MeterRegistry is a shared Spring singleton used to record proxy metrics")
  public TcpProxyEventService(TcpProxyEventClient client, MeterRegistry meterRegistry) {
    this.client = client;
    this.meterRegistry = meterRegistry;
    this.connectionDurationTimer =
        Timer.builder("tcpproxy.connection.duration")
            .publishPercentileHistogram()
            .register(meterRegistry);
    this.connectCounter = meterRegistry.counter("tcpproxy.connection.events", "type", "connect");
    this.disconnectCounter =
        meterRegistry.counter("tcpproxy.connection.events", "type", "disconnect");
  }

  public void recordConnectEvent(String sessionId, String tenantId, String clientIp) {
    connectCounter.increment();
    logger
        .atInfo()
        .addKeyValue("sessionId", sessionId)
        .addKeyValue("tenantId", tenantId)
        .addKeyValue("clientIp", clientIp)
        .log("Telnet connect event");
  }

  public void recordDisconnectEvent(
      String sessionId, String tenantId, String clientIp, Duration connectionDuration) {
    disconnectCounter.increment();
    if (connectionDuration != null) {
      connectionDurationTimer.record(connectionDuration);
    }
    logger
        .atInfo()
        .addKeyValue("sessionId", sessionId)
        .addKeyValue("tenantId", tenantId)
        .addKeyValue("clientIp", clientIp)
        .addKeyValue(
            "durationMs", connectionDuration != null ? connectionDuration.toMillis() : null)
        .log("Telnet disconnect event");
  }

  public NotifyDisconnectResponse notifyDisconnect(
      String gameInstanceId, String tenantId, String proxyConnectionId, long disconnectSequence) {
    if (!StringUtils.hasText(proxyConnectionId) || disconnectSequence <= 0) {
      return NotifyDisconnectResponse.newBuilder()
          .setError(
              error("INVALID_ARGUMENT", "proxyConnectionId and disconnectSequence are required"))
          .build();
    }
    try {
      NotifyDisconnectResponse response =
          client.notifyDisconnect(gameInstanceId, tenantId, proxyConnectionId, disconnectSequence);
      return normalize(response, "Disconnect notification delivered");
    } catch (RuntimeException ex) {
      logger.warn("Failed to notify Game Session Service about disconnect", ex);
      return NotifyDisconnectResponse.newBuilder()
          .setError(error("UPSTREAM_FAILURE", "Failed to notify Game Session Service"))
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
    return NotifyDisconnectResponse.newBuilder(safeResponse).setError(ok(okMessage)).build();
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
