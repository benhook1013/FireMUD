package net.firedevops.firemud.springcloudgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.springcloudgateway.websocket.GameplayWebSocketBridgeHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

class GameplayWebSocketBridgeConfigTest {

  private final ReactiveWebApplicationContextRunner contextRunner =
      new ReactiveWebApplicationContextRunner()
          .withUserConfiguration(
              GameplayWebSocketBridgeConfig.class, TestRuntimeIdentityConfig.class);

  @Test
  void gameplayWebSocketPathIsOwnedByGatewayCode() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(GameplayWebSocketBridgeHandler.class);
          assertThat(context).hasSingleBean(SimpleUrlHandlerMapping.class);
          assertThat(context).hasSingleBean(WebSocketHandlerAdapter.class);

          SimpleUrlHandlerMapping mapping = context.getBean(SimpleUrlHandlerMapping.class);
          assertThat(mapping.getUrlMap()).containsKeys("/ws/game", "/ws/game/**");
          assertThat(mapping.getUrlMap().get("/ws/game"))
              .isSameAs(context.getBean(GameplayWebSocketBridgeHandler.class));
        });
  }

  @Configuration
  static class TestRuntimeIdentityConfig {
    @Bean
    RuntimeIdentity runtimeIdentity() {
      return new RuntimeIdentity(
          "spring-cloud-gateway", "test-instance", "localhost", Instant.EPOCH, null, null, null);
    }
  }
}
