package net.firedevops.firemud.springcloudgateway.config;

import java.util.Map;
import net.firedevops.firemud.springcloudgateway.websocket.GameplayWebSocketBridgeHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

@Configuration
@EnableConfigurationProperties(GameplayWebSocketBridgeProperties.class)
public class GameplayWebSocketBridgeConfig {

  @Bean
  @Primary
  ReactorNettyWebSocketClient gameplayWebSocketClient() {
    return new ReactorNettyWebSocketClient();
  }

  @Bean
  GameplayWebSocketBridgeHandler gameplayWebSocketBridgeHandler(
      ReactorNettyWebSocketClient gameplayWebSocketClient,
      GameplayWebSocketBridgeProperties properties) {
    return new GameplayWebSocketBridgeHandler(gameplayWebSocketClient, properties);
  }

  @Bean
  HandlerMapping gameplayWebSocketBridgeMapping(
      GameplayWebSocketBridgeHandler gameplayWebSocketBridgeHandler) {
    SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
    mapping.setOrder(-1);
    mapping.setUrlMap(
        Map.of(
            "/ws/game",
            gameplayWebSocketBridgeHandler,
            "/ws/game/**",
            gameplayWebSocketBridgeHandler));
    return mapping;
  }

  @Bean
  @ConditionalOnMissingBean(WebSocketHandlerAdapter.class)
  WebSocketHandlerAdapter gameplayWebSocketHandlerAdapter() {
    return new WebSocketHandlerAdapter();
  }
}
