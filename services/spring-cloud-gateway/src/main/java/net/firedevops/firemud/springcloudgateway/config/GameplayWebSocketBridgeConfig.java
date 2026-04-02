package net.firedevops.firemud.springcloudgateway.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.Map;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
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
import reactor.netty.http.client.HttpClient;

@Configuration
@EnableConfigurationProperties(GameplayWebSocketBridgeProperties.class)
public class GameplayWebSocketBridgeConfig {

  @Bean
  @Primary
  ReactorNettyWebSocketClient gameplayWebSocketClient() {
    HttpClient httpClient =
        HttpClient.newConnection()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1_000)
            .responseTimeout(Duration.ofSeconds(1));
    return new ReactorNettyWebSocketClient(httpClient);
  }

  @Bean
  GameplayWebSocketBridgeHandler gameplayWebSocketBridgeHandler(
      ReactorNettyWebSocketClient gameplayWebSocketClient,
      GameplayWebSocketBridgeProperties properties,
      RuntimeIdentity runtimeIdentity) {
    return new GameplayWebSocketBridgeHandler(gameplayWebSocketClient, properties, runtimeIdentity);
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
