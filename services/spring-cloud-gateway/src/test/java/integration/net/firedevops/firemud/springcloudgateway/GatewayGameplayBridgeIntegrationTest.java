package integration.net.firedevops.firemud.springcloudgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.command.text.LookCommandConstants;
import net.firedevops.firemud.springcloudgateway.SpringCloudGatewayApplication;
import net.firedevops.firemud.springcloudgateway.health.GameplayRouteReadinessHealthIndicator;
import net.firedevops.firemud.test.GatewayTestProperties;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.publisher.Mono;

class GatewayGameplayBridgeIntegrationTest {
  private static UpstreamHolder UPSTREAM;
  private static GatewayHolder GATEWAY;

  @AfterAll
  static void shutdown() {
    if (GATEWAY != null) {
      GATEWAY.close();
      GATEWAY = null;
    }
    if (UPSTREAM != null) {
      UPSTREAM.close();
      UPSTREAM = null;
    }
  }

  @Test
  @SuppressWarnings("removal")
  void gatewayRebindsUpstreamAfterAbruptDropWithoutDroppingClientSocket() throws Exception {
    ensureStarted();

    StandardWebSocketClient client = new StandardWebSocketClient();
    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    headers.add("X-Game-Instance-Id", "42");
    headers.add("X-Tenant-Id", "22");
    headers.add("X-Proxy-Connection-Id", "bridge-test-conn");

    List<String> responses = new CopyOnWriteArrayList<>();
    AtomicBoolean downstreamClosed = new AtomicBoolean(false);
    AtomicReference<WebSocketSession> sessionRef = new AtomicReference<>();
    CountDownLatch firstReady = new CountDownLatch(1);
    CountDownLatch firstLook = new CountDownLatch(1);
    CountDownLatch secondReady = new CountDownLatch(1);
    CountDownLatch secondLook = new CountDownLatch(1);

    WebSocketSession session =
        client
            .execute(
                new TextWebSocketHandler() {
                  @Override
                  public void afterConnectionEstablished(WebSocketSession session)
                      throws IOException {
                    sessionRef.set(session);
                  }

                  @Override
                  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                    responses.add(message.getPayload());
                    if (message.getPayload().startsWith("UPSTREAM_READY")) {
                      if (firstReady.getCount() > 0) {
                        firstReady.countDown();
                      } else {
                        secondReady.countDown();
                      }
                    }
                    if (message.getPayload().startsWith("OK LOOK")) {
                      firstLook.countDown();
                      secondLook.countDown();
                    }
                  }

                  @Override
                  public void afterConnectionClosed(
                      WebSocketSession session,
                      org.springframework.web.socket.CloseStatus closeStatus) {
                    downstreamClosed.set(true);
                  }
                },
                headers,
                URI.create(GATEWAY.websocketUrl()))
            .get(5, TimeUnit.SECONDS);

    assertThat(firstReady.await(5, TimeUnit.SECONDS)).isTrue();
    sessionRef.get().sendMessage(new TextMessage("LOOK"));
    assertThat(firstLook.await(5, TimeUnit.SECONDS)).isTrue();
    sessionRef.get().sendMessage(new TextMessage("FORCE_DROP"));
    assertThat(UPSTREAM.stub().secondConnection.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(downstreamClosed.get()).isFalse();

    assertThat(secondReady.await(5, TimeUnit.SECONDS)).isTrue();
    session.sendMessage(new TextMessage("LOOK"));
    assertThat(secondLook.await(5, TimeUnit.SECONDS)).isTrue();
    session.close();

    assertThat(responses).contains(LookCommandConstants.LOOK_RESPONSE);
    assertThat(UPSTREAM.stub().seenTransportSessionIds()).hasSize(2);
    assertThat(UPSTREAM.stub().seenTransportSessionIds().get(0))
        .isEqualTo(UPSTREAM.stub().seenTransportSessionIds().get(1));
  }

  private static synchronized void ensureStarted() {
    if (UPSTREAM == null) {
      UPSTREAM = startUpstream();
    }
    if (GATEWAY == null) {
      GATEWAY = startGateway(UPSTREAM.websocketUrl());
    }
  }

  private static UpstreamHolder startUpstream() {
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(UpstreamStubApplication.class)
            .properties(
                "server.port=0",
                "spring.main.web-application-type=reactive",
                "spring.main.allow-bean-definition-overriding=true")
            .run();
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    return new UpstreamHolder(context, port);
  }

  private static GatewayHolder startGateway(String upstreamUrl) {
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(SpringCloudGatewayApplication.class)
            .properties(
                "server.port=0",
                "spring.main.web-application-type=reactive",
                "spring.flyway.enabled=false",
                "firemud.database.enabled=false",
                "firemud.gateway.gameplay.bridge.upstream-url=" + upstreamUrl,
                GatewayTestProperties.SPRING_GRPC_SERVER_RANDOM_PORT,
                GatewayTestProperties.REACTIVE_WEB_APPLICATION,
                GatewayTestProperties.DISABLE_GATEWAY_WARNING_AND_GRPC_SERVER)
            .profiles("test")
            .run();
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    return new GatewayHolder(context, port);
  }

  private record GatewayHolder(ConfigurableApplicationContext context, int port) {
    String websocketUrl() {
      return "ws://localhost:" + port + "/ws/game";
    }

    void close() {
      context.close();
    }
  }

  private record UpstreamHolder(ConfigurableApplicationContext context, int port) {
    String websocketUrl() {
      return "ws://localhost:" + port + "/ws/game";
    }

    UpstreamStub stub() {
      return context.getBean(UpstreamStub.class);
    }

    void close() {
      context.close();
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration(
      excludeName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration"
      })
  @Import({UpstreamStubConfiguration.class})
  static class UpstreamStubApplication {}

  @Configuration
  @Import({NoGrpcServerTestConfiguration.class, GameplayRouteReadinessHealthIndicator.class})
  static class UpstreamStubConfiguration {
    @Bean
    UpstreamStub upstreamStub() {
      return new UpstreamStub();
    }

    @Bean
    WebSocketHandler upstreamWebSocketHandler(UpstreamStub upstreamStub) {
      return upstreamStub;
    }

    @Bean
    HandlerMapping upstreamWebSocketMapping(WebSocketHandler upstreamWebSocketHandler) {
      SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
      mapping.setOrder(-1);
      mapping.setUrlMap(
          Map.of("/ws/game", upstreamWebSocketHandler, "/ws/game/**", upstreamWebSocketHandler));
      return mapping;
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
  }

  static final class UpstreamStub implements WebSocketHandler {
    private final List<String> seenTransportSessionIds = new CopyOnWriteArrayList<>();
    private final AtomicBoolean firstConnectionDropped = new AtomicBoolean(false);
    private final CountDownLatch secondConnection = new CountDownLatch(1);

    @Override
    public Mono<Void> handle(org.springframework.web.reactive.socket.WebSocketSession session) {
      String transportSessionId =
          session.getHandshakeInfo().getHeaders().getFirst("X-Firemud-Transport-Session-Id");
      if (transportSessionId != null) {
        seenTransportSessionIds.add(transportSessionId);
      }
      if (seenTransportSessionIds.size() >= 2) {
        secondConnection.countDown();
      }

      return session
          .send(Mono.just(session.textMessage("UPSTREAM_READY " + transportSessionId)))
          .then(
              session
                  .receive()
                  .map(WebSocketMessage::getPayloadAsText)
                  .concatMap(command -> handleCommand(session, command))
                  .then());
    }

    private Mono<Void> handleCommand(
        org.springframework.web.reactive.socket.WebSocketSession session, String command) {
      if ("FORCE_DROP".equalsIgnoreCase(command)
          && firstConnectionDropped.compareAndSet(false, true)) {
        return Mono.error(new IllegalStateException("simulated upstream restart"));
      }
      if ("LOOK".equalsIgnoreCase(command)) {
        return session.send(Mono.just(session.textMessage(LookCommandConstants.LOOK_RESPONSE)));
      }
      return session.send(Mono.just(session.textMessage("OK " + command)));
    }

    List<String> seenTransportSessionIds() {
      return seenTransportSessionIds;
    }
  }
}
