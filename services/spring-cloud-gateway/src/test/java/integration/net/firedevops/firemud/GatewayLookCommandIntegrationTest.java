package integration.net.firedevops.firemud;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.command.text.LookCommandConstants;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.WebFluxProperties;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
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
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class GatewayLookCommandIntegrationTest {
  private static GatewayHolder GATEWAY;

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

    StandardWebSocketClient client = new StandardWebSocketClient();
    AtomicReference<String> response = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    headers.add("X-Game-Instance-Id", "42");
    client
        .doHandshake(
            new TextWebSocketHandler() {
              @Override
              public void afterConnectionEstablished(WebSocketSession session) throws IOException {
                session.sendMessage(new TextMessage("LOOK"));
              }

              @Override
              protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                response.set(message.getPayload());
                latch.countDown();
              }
            },
            headers,
            URI.create(GATEWAY.websocketUrl()))
        .get(5, TimeUnit.SECONDS);

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(response.get()).isEqualTo(LookCommandConstants.LOOK_RESPONSE);
  }

  private static synchronized void ensureGatewayStarted() {
    if (GATEWAY == null) {
      GATEWAY = startGateway();
    }
  }

  private static GatewayHolder startGateway() {
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(GatewayStubApplication.class)
            .properties(
                "server.port=0",
                "spring.main.web-application-type=reactive",
                "spring.main.allow-bean-definition-overriding=true")
            .run();
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    return new GatewayHolder(context, port);
  }

  private static final class GatewayHolder {
    private final ConfigurableApplicationContext context;
    private final int port;

    private GatewayHolder(ConfigurableApplicationContext context, int port) {
      this.context = context;
      this.port = port;
    }

    String websocketUrl() {
      return "ws://localhost:" + port + "/ws/game";
    }

    void close() {
      context.close();
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration(
      exclude = {DataSourceAutoConfiguration.class, RedisAutoConfiguration.class})
  @Import(GatewayStubConfiguration.class)
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

      Flux<WebSocketMessage> replies = commands.map(cmd -> session.textMessage(responseFor(cmd)));
      return session.send(replies);
    }

    private String responseFor(String command) {
      if ("LOOK".equalsIgnoreCase(command)) {
        return LookCommandConstants.LOOK_RESPONSE;
      }
      return "OK " + command;
    }
  }

  private static final class GameSessionStub {
    private final Queue<String> commands = new ConcurrentLinkedQueue<>();

    void recordCommand(String command) {
      commands.add(command);
    }

    List<String> receivedCommands() {
      return new ArrayList<>(commands);
    }
  }
}
