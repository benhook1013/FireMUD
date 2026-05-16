package integration.net.firedevops.firemud.springcloudgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.command.text.LookCommandConstants;
import net.firedevops.firemud.springcloudgateway.SpringCloudGatewayApplication;
import net.firedevops.firemud.springcloudgateway.config.GameplayWebSocketBridgeProperties;
import net.firedevops.firemud.springcloudgateway.health.GameplayRouteReadinessHealthIndicator;
import net.firedevops.firemud.test.GatewayTestProperties;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.ReactiveTestApplicationSupport;
import net.firedevops.firemud.test.TestAsyncAssertions;
import net.firedevops.firemud.test.WebSocketTestProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
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
import org.springframework.web.socket.WebSocketHttpHeaders;
import reactor.core.publisher.Mono;

class GatewayGameplayBridgeIntegrationTest {
  private static volatile UpstreamHolder UPSTREAM;
  private static volatile ReactiveTestApplicationSupport.ReactiveAppHolder GATEWAY;
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

    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    headers.add("X-Proxy-Client-IP", "203.0.113.10");
    headers.add("X-Proxy-Game-Instance-Id", "42");
    headers.add("X-Proxy-Tenant-Id", "22");
    headers.add("X-Proxy-Connection-Id", "bridge-test-conn");

    try (WebSocketTestProbe probe = WebSocketTestProbe.connect(GATEWAY.websocketUrl(), headers)) {
      probe.awaitStartsWith("UPSTREAM_READY", Duration.ofSeconds(5));
      probe.send("LOOK");
      probe.awaitStartsWith("OK LOOK", Duration.ofSeconds(5));
      probe.send("FORCE_DROP");
      TestAsyncAssertions.assertEventually(
          "gateway upstream reconnection after forced drop",
          Duration.ofSeconds(5),
          () -> TEST_UPSTREAM_STATE.get().connectionCount() >= 2);
      assertThat(probe.downstreamClosed()).isFalse();
      probe.send("LOOK");
      probe.awaitStartsWith("OK LOOK", Duration.ofSeconds(5));
      assertThat(probe.responses()).contains(LookCommandConstants.LOOK_RESPONSE);
    }
    assertThat(UPSTREAM.seenTransportSessionIds()).hasSize(2);
    assertThat(UPSTREAM.seenTransportSessionIds().get(0))
        .isEqualTo(UPSTREAM.seenTransportSessionIds().get(1));
  }

  @Test
  @SuppressWarnings("removal")
  void gatewayRebindsAfterRealUpstreamRestartWithoutDroppingClientSocket() throws Exception {
    ensureStarted();

    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    headers.add("X-Proxy-Client-IP", "203.0.113.10");
    headers.add("X-Proxy-Game-Instance-Id", "42");
    headers.add("X-Proxy-Tenant-Id", "22");
    headers.add("X-Proxy-Connection-Id", "bridge-test-conn");

    try (WebSocketTestProbe probe = WebSocketTestProbe.connect(GATEWAY.websocketUrl(), headers)) {
      probe.awaitStartsWith("UPSTREAM_READY", Duration.ofSeconds(5));
      probe.send("LOOK");
      probe.awaitStartsWith("OK LOOK", Duration.ofSeconds(5));

      UPSTREAM.restart();
      TestAsyncAssertions.assertEventually(
          "gateway upstream reconnection after upstream restart",
          Duration.ofSeconds(30),
          () -> TEST_UPSTREAM_STATE.get().connectionCount() >= 2);
      assertThat(probe.downstreamClosed()).isFalse();

      probe.send("LOOK");
      probe.awaitStartsWith("OK LOOK", Duration.ofSeconds(30));
    }

    assertThat(UPSTREAM.seenTransportSessionIds()).hasSize(2);
    assertThat(UPSTREAM.seenTransportSessionIds().get(0))
        .isEqualTo(UPSTREAM.seenTransportSessionIds().get(1));
  }

  private static synchronized void ensureStarted() {
    if (UPSTREAM == null) {
      UPSTREAM = startUpstream();
    }
    if (GATEWAY == null) {
      GATEWAY = startGateway(UPSTREAM.websocketUrl()).app();
    }
  }

  private static UpstreamHolder startUpstream() {
    int port = TestSocketUtils.findAvailableTcpPort();
    TEST_UPSTREAM_STATE.set(new UpstreamRuntimeState());
    return UpstreamHolder.start(port);
  }

  private static GatewayHolder startGateway(String upstreamUrl) {
    TEST_UPSTREAM_URL.set(upstreamUrl);
    return new GatewayHolder(
        ReactiveTestApplicationSupport.startReactiveApp(
            Map.of(
                "spring.profiles.active",
                "test",
                "spring.flyway.enabled",
                "false",
                "firemud.database.enabled",
                "false",
                GatewayTestProperties.SPRING_GRPC_SERVER_RANDOM_PORT,
                "",
                GatewayTestProperties.DISABLE_GATEWAY_WARNING_AND_GRPC_SERVER,
                ""),
            SpringCloudGatewayApplication.class,
            GatewayBridgeTestOverrideConfiguration.class));
  }

  private record GatewayHolder(ReactiveTestApplicationSupport.ReactiveAppHolder app) {
    String websocketUrl() {
      return app.websocketUrl();
    }

    void close() {
      app.close();
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
      ReactiveTestApplicationSupport.ReactiveAppHolder app =
          ReactiveTestApplicationSupport.startReactiveApp(
              Map.ofEntries(
                  Map.entry("server.port", Integer.toString(port)),
                  Map.entry("server.shutdown", "immediate"),
                  Map.entry("spring.main.allow-bean-definition-overriding", "true"),
                  Map.entry("spring.flyway.enabled", "false"),
                  Map.entry("firemud.database.enabled", "false"),
                  Map.entry(GatewayTestProperties.SPRING_GRPC_SERVER_RANDOM_PORT, ""),
                  Map.entry(GatewayTestProperties.SPRING_GRPC_SERVER_SSL_DISABLED, ""),
                  Map.entry(GatewayTestProperties.FIREMUD_GRPC_CERT_CHAIN_PATH, ""),
                  Map.entry(GatewayTestProperties.FIREMUD_GRPC_PRIVATE_KEY_PATH, ""),
                  Map.entry(GatewayTestProperties.FIREMUD_GRPC_CA_CERT_PATH, ""),
                  Map.entry("firemud.grpc.plaintext", "true"),
                  Map.entry(GatewayTestProperties.DISABLE_GATEWAY_WARNING_AND_GRPC_SERVER, "")),
              UpstreamStubApplication.class);
      return new UpstreamHolder(app.context(), port);
    }

    String websocketUrl() {
      return "ws://localhost:" + port + "/ws/game";
    }

    List<String> seenTransportSessionIds() {
      return TEST_UPSTREAM_STATE.get().seenTransportSessionIds();
    }

    void restart() {
      context.close();
      context = start(port).context;
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
