package net.firedevops.firemud.websocket;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

/** Registers the Game Session WebSocket handler and maps it under `/ws/game`. */
@Configuration
public class GameSessionWebSocketConfig {
  private final GameSessionWebSocketHandler handler;

  public GameSessionWebSocketConfig(GameSessionWebSocketHandler handler) {
    this.handler = handler;
  }

  @Bean
  public HandlerMapping gameSessionWebSocketMapping() {
    SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
    mapping.setOrder(-1);
    Map<String, WebSocketHandler> urlMap = new LinkedHashMap<>();
    urlMap.put("/ws/game", handler);
    urlMap.put("/ws/game/**", handler);
    mapping.setUrlMap(urlMap);
    return mapping;
  }

  @Bean
  public WebSocketHandlerAdapter webSocketHandlerAdapter() {
    return new WebSocketHandlerAdapter();
  }
}
