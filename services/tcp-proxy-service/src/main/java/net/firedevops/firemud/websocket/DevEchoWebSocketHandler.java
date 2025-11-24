package net.firedevops.firemud.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class DevEchoWebSocketHandler extends TextWebSocketHandler {
  private static final Logger logger = LoggerFactory.getLogger(DevEchoWebSocketHandler.class);

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    logger.info("Dev echo WebSocket connected from {}", session.getRemoteAddress());
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    String payload = message.getPayload();
    logger.info("Dev echo received: {}", payload);
    try {
      session.sendMessage(new TextMessage(payload));
    } catch (Exception ex) {
      logger.error("Failed to echo message", ex);
    }
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    logger.error("WebSocket transport error", exception);
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    logger.info("Dev echo WebSocket closed: {}", status);
  }
}
