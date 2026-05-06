package net.firedevops.firemud.springcloudgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.firedevops.firemud.command.text.LookCommandConstants;
import net.firedevops.firemud.springcloudgateway.health.GameplayRouteReadinessHealthIndicator;
import net.firedevops.firemud.test.ReactiveTestApplicationSupport;
import net.firedevops.firemud.test.WebSocketTestProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.webflux.autoconfigure.WebFluxProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class GatewayLookCommandIntegrationTest {
  private static volatile ReactiveTestApplicationSupport.ReactiveAppHolder GATEWAY;

  @AfterAll
  static void stopGateway() {
    if (GATEWAY != null) {
      GATEWAY.close();
      GATEWAY = null;
    }
  }

  @Test
  @SuppressWarnings("removal")
  void lookCommandReturnsExpectedResponseThroughGateway() throws Exception {
    ensureGatewayStarted();

    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    headers.add("X-Game-Instance-Id", "42");
    try (WebSocketTestProbe probe = WebSocketTestProbe.connect(GATEWAY.websocketUrl(), headers)) {
      probe.send("LOOK");
      assertThat(
              probe.awaitMessage(
                  LookCommandConstants.LOOK_RESPONSE::equals,
                  "LOOK response",
                  Duration.ofSeconds(5)))
          .isEqualTo(LookCommandConstants.LOOK_RESPONSE);
    }
  }

  @Test
  @SuppressWarnings("removal")
  void gatewayPropagatesCleanLogoutCloseReason() throws Exception {
    ensureGatewayStarted();

    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    headers.add("X-Game-Instance-Id", "42");

    try (WebSocketTestProbe probe = WebSocketTestProbe.connect(GATEWAY.websocketUrl(), headers)) {
      probe.send("FORCE_CLOSE_GATEWAY_RESTART");
      assertThat(probe.awaitClosed(Duration.ofSeconds(5))).isTrue();
      assertThat(probe.closeStatus()).isNotNull();
      assertThat(probe.closeStatus().getCode()).isEqualTo(1000);
      assertThat(probe.closeStatus().getReason()).isEqualTo("logout;subreason=gateway_restart");
    }
  }

  @Test
  @SuppressWarnings("removal")
  void gatewayPropagatesExplicitInternalErrorCloseReason() throws Exception {
    ensureGatewayStarted();

    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    headers.add("X-Game-Instance-Id", "42");

    try (WebSocketTestProbe probe = WebSocketTestProbe.connect(GATEWAY.websocketUrl(), headers)) {
      probe.send("FORCE_CLOSE_INTERNAL_ERROR");
      assertThat(probe.awaitClosed(Duration.ofSeconds(5))).isTrue();
      assertThat(probe.closeStatus()).isNotNull();
      assertThat(probe.closeStatus().getCode()).isEqualTo(1011);
      assertThat(probe.closeStatus().getReason()).isEqualTo("internal_error");
    }
  }

  private static synchronized void ensureGatewayStarted() {
    if (GATEWAY == null) {
      GATEWAY = startGateway();
    }
  }

  private static ReactiveTestApplicationSupport.ReactiveAppHolder startGateway() {
    return ReactiveTestApplicationSupport.startReactiveApp(
        Map.of(
            "spring.main.allow-bean-definition-overriding",
            "true",
            "management.endpoint.health.group.readiness.include",
            "readinessState"),
        GatewayStubApplication.class);
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration(
      excludeName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration"
      })
  @Import({GatewayStubConfiguration.class, GameplayRouteReadinessHealthIndicator.class})
  static class GatewayStubApplication {}

  @Configuration
  static class GatewayStubConfiguration {
    @Bean
    WebSocketHandler gatewayWebSocketHandler(GameSessionStub stub) {
      return new GatewayWebSocketHandler(stub);
    }

    @Bean
    HandlerMapping gatewayWebSocketMapping(WebSocketHandler handler) {
      SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
      mapping.setOrder(-1);
      Map<String, WebSocketHandler> urlMap = Map.of("/ws/game", handler, "/ws/game/**", handler);
      mapping.setUrlMap(urlMap);
      return mapping;
    }

    @Bean
    GameSessionStub gameSessionStub() {
      return new GameSessionStub();
    }

    @Bean
    WebSocketHandlerAdapter webSocketHandlerAdapter() {
      return new WebSocketHandlerAdapter();
    }

    @Bean
    @Primary
    ServerCodecConfigurer serverCodecConfigurer() {
      return ServerCodecConfigurer.create();
    }

    @Bean
    @Primary
    WebFluxProperties webFluxProperties() {
      return new WebFluxProperties();
    }
  }

  private static final class GatewayWebSocketHandler implements WebSocketHandler {
    private final GameSessionStub stub;

    private GatewayWebSocketHandler(GameSessionStub stub) {
      this.stub = stub;
    }

    @Override
    public Mono<Void> handle(org.springframework.web.reactive.socket.WebSocketSession session) {
      String gameInstanceId =
          session.getHandshakeInfo().getHeaders().getFirst("X-Game-Instance-Id");
      if (!StringUtils.hasText(gameInstanceId)) {
        WebSocketMessage errorMessage =
            session.textMessage("ERROR INVALID_ARGUMENT gameInstanceId header required");
        return session
            .send(Mono.just(errorMessage))
            .then(Mono.defer(() -> session.close(CloseStatus.BAD_DATA)));
      }

      Flux<String> commands =
          session
              .receive()
              .map(WebSocketMessage::getPayloadAsText)
              .map(String::trim)
              .filter(StringUtils::hasText)
              .doOnNext(stub::recordCommand);

      return commands.concatMap(cmd -> handleCommand(session, cmd)).then();
    }

    private String responseFor(String command) {
      if ("LOOK".equalsIgnoreCase(command)) {
        return LookCommandConstants.LOOK_RESPONSE;
      }
      return "OK " + command;
    }

    private Mono<Void> handleCommand(
        org.springframework.web.reactive.socket.WebSocketSession session, String command) {
      if ("FORCE_CLOSE_GATEWAY_RESTART".equalsIgnoreCase(command)) {
        return session.close(new CloseStatus(1000, "logout;subreason=gateway_restart"));
      }
      if ("FORCE_CLOSE_INTERNAL_ERROR".equalsIgnoreCase(command)) {
        return session.close(new CloseStatus(1011, "internal_error"));
      }
      return session.send(Mono.just(session.textMessage(responseFor(command))));
    }
  }

  private static final class GameSessionStub {
    private final Queue<String> commands = new ConcurrentLinkedQueue<>();

    void recordCommand(String command) {
      commands.add(command);
    }
  }
}
