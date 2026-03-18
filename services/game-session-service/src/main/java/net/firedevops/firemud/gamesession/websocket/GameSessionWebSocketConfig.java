package net.firedevops.firemud.gamesession.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registers the WebSocket handler with the standard Servlet WebSocket stack. */
@Configuration
@EnableWebSocket
public class GameSessionWebSocketConfig implements WebSocketConfigurer {
  private final GameSessionWebSocketHandler handler;
  private final GameSessionWebSocketHandshakeInterceptor handshakeInterceptor;

  public GameSessionWebSocketConfig(
      GameSessionWebSocketHandler handler,
      GameSessionWebSocketHandshakeInterceptor handshakeInterceptor) {
    this.handler = handler;
    this.handshakeInterceptor = handshakeInterceptor;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(handler, "/ws/game", "/ws/game/**")
        .addInterceptors(handshakeInterceptor)
        .setAllowedOrigins("*");
  }
}
