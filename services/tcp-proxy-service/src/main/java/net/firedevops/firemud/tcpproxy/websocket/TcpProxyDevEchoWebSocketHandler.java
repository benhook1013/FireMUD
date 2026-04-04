package net.firedevops.firemud.tcpproxy.websocket;

import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.runtime.RuntimeLoggingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Lightweight echo handler to help developers validate the Telnet -> WebSocket bridge without
 * standing up the full gateway stack.
 */
@Component
@Profile("dev")
public class TcpProxyDevEchoWebSocketHandler extends TextWebSocketHandler {
  private static final Logger logger =
      LoggerFactory.getLogger(TcpProxyDevEchoWebSocketHandler.class);
  private final RuntimeIdentity runtimeIdentity;

  public TcpProxyDevEchoWebSocketHandler(RuntimeIdentity runtimeIdentity) {
    this.runtimeIdentity = runtimeIdentity;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    try (RuntimeLoggingContext ignored = openLoggingContext(session)) {
      logger.info("Dev echo WebSocket connected from {}", session.getRemoteAddress());
    }
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    String payload = message.getPayload();
    try (RuntimeLoggingContext ignored = openLoggingContext(session)) {
      logger.info("Dev echo received: {}", payload);
      try {
        session.sendMessage(new TextMessage(payload));
      } catch (Exception ex) {
        logger.error("Failed to echo message", ex);
      }
    }
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    try (RuntimeLoggingContext ignored = openLoggingContext(session)) {
      logger.error("WebSocket transport error", exception);
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    try (RuntimeLoggingContext ignored = openLoggingContext(session)) {
      logger.info("Dev echo WebSocket closed: {}", status);
    }
  }

  RuntimeLoggingContext openLoggingContext(WebSocketSession session) {
    return RuntimeLoggingContext.open(runtimeIdentity, session.getId());
  }
}
