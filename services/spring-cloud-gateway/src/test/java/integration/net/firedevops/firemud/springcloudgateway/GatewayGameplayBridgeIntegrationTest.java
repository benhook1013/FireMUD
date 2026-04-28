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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.command.text.LookCommandConstants;
import net.firedevops.firemud.springcloudgateway.SpringCloudGatewayApplication;
import net.firedevops.firemud.springcloudgateway.config.GameplayWebSocketBridgeProperties;
import net.firedevops.firemud.springcloudgateway.health.GameplayRouteReadinessHealthIndicator;
import net.firedevops.firemud.test.GatewayTestProperties;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.http.client.ReactorResourceFactory;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.test.util.TestSocketUtils;
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
  private static volatile UpstreamHolder UPSTREAM;
  private static volatile GatewayHolder GATEWAY;
  private static final AtomicReference<String> TEST_UPSTREAM_URL = new AtomicReference<>();
  private static final AtomicReference<UpstreamRuntimeState> TEST_UPSTREAM_STATE =
      new AtomicReference<>();

  @AfterEach
  void shutdownAfterEach() {
    shutdown();
  }

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
    headers.add("X-Proxy-Client-IP", "203.0.113.10");
    headers.add("X-Proxy-Game-Instance-Id", "42");
    headers.add("X-Proxy-Tenant-Id", "22");
    headers.add("X-Proxy-Connection-Id", "bridge-test-conn");

    List<String> responses = new CopyOnWriteArrayList<>();
    AtomicBoolean downstreamClosed = new AtomicBoolean(false);
    AtomicReference<WebSocketSession> sessionRef = new AtomicReference<>();
    CountDownLatch firstReady = new CountDownLatch(1);
    CountDownLatch firstLook = new CountDownLatch(1);
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
    assertThat(UPSTREAM.awaitConnections(2, 5, TimeUnit.SECONDS)).isTrue();
    assertThat(downstreamClosed.get()).isFalse();
    session.sendMessage(new TextMessage("LOOK"));
    assertThat(secondLook.await(5, TimeUnit.SECONDS)).isTrue();
    session.close();

    assertThat(responses).contains(LookCommandConstants.LOOK_RESPONSE);
    assertThat(UPSTREAM.seenTransportSessionIds()).hasSize(2);
    assertThat(UPSTREAM.seenTransportSessionIds().get(0))
        .isEqualTo(UPSTREAM.seenTransportSessionIds().get(1));
  }

  @Test
  @SuppressWarnings("removal")
  void gatewayRebindsAfterRealUpstreamRestartWithoutDroppingClientSocket() throws Exception {
    ensureStarted();

    StandardWebSocketClient client = new StandardWebSocketClient();
    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    headers.add("X-Proxy-Client-IP", "203.0.113.10");
    headers.add("X-Proxy-Game-Instance-Id", "42");
    headers.add("X-Proxy-Tenant-Id", "22");
    headers.add("X-Proxy-Connection-Id", "bridge-test-conn");

    AtomicBoolean downstreamClosed = new AtomicBoolean(false);
    AtomicReference<WebSocketSession> sessionRef = new AtomicReference<>();
    CountDownLatch firstReady = new CountDownLatch(1);
    CountDownLatch firstLook = new CountDownLatch(1);
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
                    if (message.getPayload().startsWith("UPSTREAM_READY")) {
                      if (firstReady.getCount() > 0) {
                        firstReady.countDown();
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

    UPSTREAM.restart();
    assertThat(UPSTREAM.awaitConnections(2, 30, TimeUnit.SECONDS)).isTrue();
    assertThat(downstreamClosed.get()).isFalse();

    session.sendMessage(new TextMessage("LOOK"));
    assertThat(secondLook.await(30, TimeUnit.SECONDS)).isTrue();
    session.close();

    assertThat(UPSTREAM.seenTransportSessionIds()).hasSize(2);
    assertThat(UPSTREAM.seenTransportSessionIds().get(0))
        .isEqualTo(UPSTREAM.seenTransportSessionIds().get(1));
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
    int port = TestSocketUtils.findAvailableTcpPort();
    TEST_UPSTREAM_STATE.set(new UpstreamRuntimeState());
    return UpstreamHolder.start(port);
  }

  private static GatewayHolder startGateway(String upstreamUrl) {
    TEST_UPSTREAM_URL.set(upstreamUrl);
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(
                SpringCloudGatewayApplication.class, GatewayBridgeTestOverrideConfiguration.class)
            .profiles("test")
            .properties(
                "server.port=0",
                "spring.main.web-application-type=reactive",
                "spring.flyway.enabled=false",
                "firemud.database.enabled=false",
                GatewayTestProperties.SPRING_GRPC_SERVER_RANDOM_PORT,
                GatewayTestProperties.DISABLE_GATEWAY_WARNING_AND_GRPC_SERVER)
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

  private static final class UpstreamHolder {
    private ConfigurableApplicationContext context;
    private final int port;

    private UpstreamHolder(ConfigurableApplicationContext context, int port) {
      this.context = context;
      this.port = port;
    }

    static UpstreamHolder start(int port) {
      ConfigurableApplicationContext context =
          new SpringApplicationBuilder(UpstreamStubApplication.class)
              .properties(
                  "server.port=" + port,
                  "server.shutdown=immediate",
                  "spring.main.web-application-type=reactive",
                  "spring.main.allow-bean-definition-overriding=true",
                  "spring.flyway.enabled=false",
                  "firemud.database.enabled=false",
                  GatewayTestProperties.SPRING_GRPC_SERVER_RANDOM_PORT,
                  GatewayTestProperties.SPRING_GRPC_SERVER_SSL_DISABLED,
                  GatewayTestProperties.FIREMUD_GRPC_CERT_CHAIN_PATH,
                  GatewayTestProperties.FIREMUD_GRPC_PRIVATE_KEY_PATH,
                  GatewayTestProperties.FIREMUD_GRPC_CA_CERT_PATH,
                  "firemud.grpc.plaintext=true",
                  GatewayTestProperties.DISABLE_GATEWAY_WARNING_AND_GRPC_SERVER)
              .run();
      return new UpstreamHolder(context, port);
    }

    String websocketUrl() {
      return "ws://localhost:" + port + "/ws/game";
    }

    boolean awaitConnections(int count, long timeout, TimeUnit unit) throws InterruptedException {
      long deadline = System.nanoTime() + unit.toNanos(timeout);
      while (System.nanoTime() < deadline) {
        if (TEST_UPSTREAM_STATE.get().connectionCount() >= count) {
          return true;
        }
        Thread.sleep(50);
      }
      return TEST_UPSTREAM_STATE.get().connectionCount() >= count;
    }

    List<String> seenTransportSessionIds() {
      return TEST_UPSTREAM_STATE.get().seenTransportSessionIds();
    }

    void restart() {
      context.close();
      context =
          new SpringApplicationBuilder(UpstreamStubApplication.class)
              .properties(
                  "server.port=" + port,
                  "server.shutdown=immediate",
                  "spring.main.web-application-type=reactive",
                  "spring.main.allow-bean-definition-overriding=true",
                  "spring.flyway.enabled=false",
                  "firemud.database.enabled=false",
                  GatewayTestProperties.SPRING_GRPC_SERVER_RANDOM_PORT,
                  GatewayTestProperties.SPRING_GRPC_SERVER_SSL_DISABLED,
                  GatewayTestProperties.FIREMUD_GRPC_CERT_CHAIN_PATH,
                  GatewayTestProperties.FIREMUD_GRPC_PRIVATE_KEY_PATH,
                  GatewayTestProperties.FIREMUD_GRPC_CA_CERT_PATH,
                  "firemud.grpc.plaintext=true",
                  GatewayTestProperties.DISABLE_GATEWAY_WARNING_AND_GRPC_SERVER)
              .run();
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
      return new UpstreamStub(TEST_UPSTREAM_STATE.get());
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

    @Bean
    ReactorResourceFactory reactorResourceFactory() {
      ReactorResourceFactory factory = new ReactorResourceFactory();
      factory.setUseGlobalResources(false);
      return factory;
    }
  }

  @Configuration
  static class GatewayBridgeTestOverrideConfiguration {
    @Bean
    @Primary
    GameplayWebSocketBridgeProperties gameplayWebSocketBridgeProperties() {
      return new GameplayWebSocketBridgeProperties(TEST_UPSTREAM_URL.get(), 160, 250L, 256);
    }
  }

  static final class UpstreamStub implements WebSocketHandler {
    private final UpstreamRuntimeState state;

    UpstreamStub(UpstreamRuntimeState state) {
      this.state = state;
    }

    @Override
    public Mono<Void> handle(org.springframework.web.reactive.socket.WebSocketSession session) {
      String transportSessionId =
          session.getHandshakeInfo().getHeaders().getFirst("X-Firemud-Transport-Session-Id");
      if (transportSessionId != null) {
        state.recordTransportSessionId(transportSessionId);
      }
      state.incrementConnectionCount();

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
      if ("FORCE_DROP".equalsIgnoreCase(command) && state.markFirstConnectionDropped()) {
        return Mono.error(new IllegalStateException("simulated upstream restart"));
      }
      if ("LOOK".equalsIgnoreCase(command)) {
        return session.send(Mono.just(session.textMessage(LookCommandConstants.LOOK_RESPONSE)));
      }
      return session.send(Mono.just(session.textMessage("OK " + command)));
    }
  }

  private static final class UpstreamRuntimeState {
    private final List<String> seenTransportSessionIds = new CopyOnWriteArrayList<>();
    private final AtomicBoolean firstConnectionDropped = new AtomicBoolean(false);
    private final AtomicInteger connectionCount = new AtomicInteger();

    void recordTransportSessionId(String transportSessionId) {
      seenTransportSessionIds.add(transportSessionId);
    }

    void incrementConnectionCount() {
      connectionCount.incrementAndGet();
    }

    int connectionCount() {
      return connectionCount.get();
    }

    List<String> seenTransportSessionIds() {
      return seenTransportSessionIds;
    }

    boolean markFirstConnectionDropped() {
      return firstConnectionDropped.compareAndSet(false, true);
    }
  }
}
