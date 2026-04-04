package net.firedevops.firemud.springcloudgateway.websocket;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.runtime.RuntimeLoggingContext;
import org.slf4j.Logger;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/** Development-only echo handler for verifying the TCP proxy <-> gateway WebSocket bridge. */
public class DevEchoWebSocketHandler implements WebSocketHandler {
  private static final String ENDPOINT_TAG_VALUE = "dev-echo";
  private static final String METRIC_CONNECTIONS_ACTIVE = "gateway.connections.active";
  private static final String METRIC_CONNECTIONS_TOTAL = "gateway.connections.total";
  private static final String METRIC_MESSAGES = "gateway.websocket.messages";
  private static final Logger logger = LoggingUtil.getLogger(DevEchoWebSocketHandler.class);

  private final AtomicInteger activeConnections = new AtomicInteger();
  private final Counter connectionCounter;
  private final Counter messageCounter;
  private final RuntimeIdentity runtimeIdentity;

  public DevEchoWebSocketHandler(MeterRegistry meterRegistry, RuntimeIdentity runtimeIdentity) {
    this.runtimeIdentity = runtimeIdentity;
    this.connectionCounter =
        Counter.builder(METRIC_CONNECTIONS_TOTAL)
            .description("Total WebSocket connections for a gateway endpoint")
            .tag("endpoint", ENDPOINT_TAG_VALUE)
            .register(meterRegistry);
    this.messageCounter =
        Counter.builder(METRIC_MESSAGES)
            .description("WebSocket messages processed for a gateway endpoint")
            .tag("endpoint", ENDPOINT_TAG_VALUE)
            .register(meterRegistry);
    Gauge.builder(METRIC_CONNECTIONS_ACTIVE, activeConnections, AtomicInteger::get)
        .description("Active WebSocket connections for a gateway endpoint")
        .tag("endpoint", ENDPOINT_TAG_VALUE)
        .register(meterRegistry);
  }

  @Override
  public Mono<Void> handle(WebSocketSession session) {
    connectionCounter.increment();
    activeConnections.incrementAndGet();

    try (RuntimeLoggingContext ignored = openLoggingContext(session)) {
      logger.info(
          "Dev echo session {} connected from {}",
          session.getId(),
          session.getHandshakeInfo().getRemoteAddress());
    }

    Flux<WebSocketMessage> outbound =
        session
            .receive()
            .doOnNext(
                message -> {
                  String payload = message.getPayloadAsText();
                  try (RuntimeLoggingContext ignored = openLoggingContext(session)) {
                    logger.info("Dev echo session {} received: {}", session.getId(), payload);
                  }
                  messageCounter.increment();
                })
            .doOnError(
                throwable -> {
                  try (RuntimeLoggingContext ignored = openLoggingContext(session)) {
                    logger.warn(
                        "Dev echo session {} closed due to error", session.getId(), throwable);
                  }
                })
            .map(
                message -> {
                  String payload = message.getPayloadAsText();
                  String responsePayload =
                      "client-ip".equalsIgnoreCase(payload)
                          ? session.getHandshakeInfo().getHeaders().getFirst("X-Client-IP")
                          : payload;
                  return session.textMessage(responsePayload != null ? responsePayload : "");
                });

    return session
        .send(outbound)
        .doFinally(
            signalType -> {
              activeConnections.decrementAndGet();
              try (RuntimeLoggingContext ignored = openLoggingContext(session)) {
                if (signalType != SignalType.CANCEL) {
                  logger.info(
                      "Dev echo session {} closed via {} (active={})",
                      session.getId(),
                      signalType,
                      activeConnections.get());
                } else {
                  logger.debug(
                      "Dev echo session {} cancelled (active={})",
                      session.getId(),
                      activeConnections.get());
                }
              }
            });
  }

  RuntimeLoggingContext openLoggingContext(WebSocketSession session) {
    return RuntimeLoggingContext.open(runtimeIdentity, session.getId());
  }
}
