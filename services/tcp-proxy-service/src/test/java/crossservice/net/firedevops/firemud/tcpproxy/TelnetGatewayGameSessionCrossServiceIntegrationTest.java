package net.firedevops.firemud.tcpproxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.firedevops.firemud.gamesession.test.LookTestFixtures;
import net.firedevops.firemud.tcpproxy.stub.GatewayStubApplication;
import net.firedevops.firemud.tcpproxy.telnet.TelnetServer;
import net.firedevops.firemud.tcpproxy.testsupport.GameplayTelnetDriver;
import net.firedevops.firemud.test.HttpTestSupport;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.ReactiveTestApplicationSupport;
import net.firedevops.firemud.test.TestAsyncAssertions;
import net.firedevops.firemud.test.WebSocketTestProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Cross-service integration test verifying the full Telnet → Gateway → Game Session path. */
@Testcontainers
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = TcpProxyServiceApplication.class)
@Import(NoGrpcServerTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TelnetGatewayGameSessionCrossServiceIntegrationTest {

  private static GameSessionStubHolder GAME_SESSION_STUB;
  private static ReactiveTestApplicationSupport.ReactiveAppHolder GATEWAY;
  // This suite runs alongside many other service checks in the full repo build, so use a wider
  // per-command timeout than the lighter isolated telnet tests.
  private static final Duration COMMAND_WAIT = Duration.ofSeconds(30);

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    ensureTestServicesStarted();
    registry.add("GATEWAY_WS_URL", GATEWAY::websocketUrl);
    registry.add("TCP_PROXY_PORT", () -> 0);
    registry.add("TCP_PROXY_DEFAULT_WORLD_SLUG", () -> "demo");
    registry.add("TCP_PROXY_DEFAULT_REALM_SLUG", () -> "production");
    registry.add("TCP_PROXY_DEFAULT_POINTER_VERSION", () -> "1");
    registry.add("TCP_PROXY_DEFAULT_GAME_INSTANCE_ID", () -> "1");
    registry.add("TCP_PROXY_DEFAULT_TENANT_ID", () -> "1");
  }

  @LocalServerPort private int port;

  @Autowired private TelnetServer telnetServer;

  @AfterEach
  void resetSharedBridgeState() {
    stopTestServices();
  }

  @AfterAll
  static synchronized void stopTestServices() {
    ReactiveTestApplicationSupport.ReactiveAppHolder gateway = GATEWAY;
    GATEWAY = null;
    if (gateway != null) {
      gateway.close();
    }
    GameSessionStubHolder gameSessionStub = GAME_SESSION_STUB;
    GAME_SESSION_STUB = null;
    if (gameSessionStub != null) {
      gameSessionStub.close();
    }
  }

  @Test
  void telnetWorldsCanBeBrowsedBeforeLoginAndPlay() throws Exception {
    ensureTestServicesStarted();
    String body = HttpTestSupport.getBody("http://localhost:" + port + "/ping");
    assertThat(body).contains("pong");

    try (GameplayTelnetDriver client =
        GameplayTelnetDriver.connect("localhost", telnetServer.getPort(), COMMAND_WAIT)) {
      client.awaitInitialGuidance();
      String worldsResponse = client.sendAndExpectExactLine("WORLDS", "processed:WORLDS");
      assertThat(worldsResponse).isEqualTo("processed:WORLDS");

      String loginResponse =
          client.sendAndExpectExactLine(
              "LOGIN demo@example.com swordfish", "processed:LOGIN demo@example.com swordfish");
      assertThat(loginResponse).isEqualTo("processed:LOGIN demo@example.com swordfish");

      String playResponse = client.sendAndExpectExactLine("PLAY demo", "processed:PLAY demo");
      assertThat(playResponse).isEqualTo("processed:PLAY demo");

      client.sendLine("look");
      String response = client.readMultiLineResponse();
      assertThat(response.trim()).isEqualTo(LookTestFixtures.canonicalLookText().trim());
    }

    awaitCommand("look");
    assertThat(GAME_SESSION_STUB.stub().receivedCommands())
        .contains("WORLDS", "LOGIN demo@example.com swordfish", "PLAY demo", "look");
  }

  @Test
  void telnetLoginMatchesGatewayWebSocketResponses() throws Exception {
    ensureTestServicesStarted();
    List<String> websocketResponses =
        runGatewayWebSocketCommands(
            "WORLDS", "LOGIN demo@example.com swordfish", "PLAY demo", "LOOK");

    String telnetWorldsResponse;
    String telnetLoginResponse;
    String telnetPlayResponse;
    String telnetLookResponse;
    try (GameplayTelnetDriver client =
        GameplayTelnetDriver.connect("localhost", telnetServer.getPort(), COMMAND_WAIT)) {
      client.awaitInitialGuidance();
      telnetWorldsResponse = client.sendAndExpectExactLine("WORLDS", "processed:WORLDS");
      assertThat(telnetWorldsResponse).isNotNull();
      telnetLoginResponse =
          client.sendAndExpectExactLine(
              "LOGIN demo@example.com swordfish", "processed:LOGIN demo@example.com swordfish");
      assertThat(telnetLoginResponse).isNotNull();
      telnetPlayResponse = client.sendAndExpectExactLine("PLAY demo", "processed:PLAY demo");
      assertThat(telnetPlayResponse).isNotNull();
      client.sendLine("LOOK");
      telnetLookResponse = client.readMultiLineResponse();
    }

    assertThat(websocketResponses).hasSizeGreaterThanOrEqualTo(4);
    assertThat(websocketResponses.get(0)).isEqualTo("processed:WORLDS");
    assertThat(telnetWorldsResponse).isEqualTo(websocketResponses.get(0));
    assertThat(telnetLoginResponse).isEqualTo(websocketResponses.get(1));
    assertThat(telnetPlayResponse).isEqualTo(websocketResponses.get(2));
    assertThat(telnetLookResponse.trim()).isEqualTo(websocketResponses.get(3).trim());
  }

  @Test
  void telnetPreservesGatewayRestartLogoutOnCleanBridgeClose() throws Exception {
    ensureTestServicesStarted();

    try (GameplayTelnetDriver client =
        GameplayTelnetDriver.connect("localhost", telnetServer.getPort(), COMMAND_WAIT)) {
      client.awaitInitialGuidance();
      assertThat(client.sendAndExpectExactLine("WORLDS", "processed:WORLDS"))
          .isEqualTo("processed:WORLDS");
      assertThat(
              client.sendAndExpectExactLine(
                  "LOGIN demo@example.com swordfish", "processed:LOGIN demo@example.com swordfish"))
          .isEqualTo("processed:LOGIN demo@example.com swordfish");
      assertThat(client.sendAndExpectExactLine("PLAY demo", "processed:PLAY demo"))
          .isEqualTo("processed:PLAY demo");
      assertThat(
              client.sendAndExpectExactLine(
                  "FORCE_CLOSE_GATEWAY_RESTART",
                  "DISCONNECT logout;subreason=gateway_restart Gameplay session ended; please reconnect"))
          .isEqualTo(
              "DISCONNECT logout;subreason=gateway_restart Gameplay session ended; please reconnect");
    }
  }

  private static void awaitCommand(String expected) {
    ensureTestServicesStarted();
    try {
      TestAsyncAssertions.assertQueueContains(
          GAME_SESSION_STUB.stub().receivedCommands(),
          expected,
          COMMAND_WAIT,
          "gateway stub command " + expected);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(ex);
    }
  }

  private static synchronized void ensureTestServicesStarted() {
    if (GAME_SESSION_STUB == null) {
      GAME_SESSION_STUB = startGameSessionStub();
    }
    if (GATEWAY == null) {
      GATEWAY = startGateway(GAME_SESSION_STUB.port());
    }
  }

  private static GameSessionStubHolder startGameSessionStub() {
    try {
      ReactiveTestApplicationSupport.ReactiveAppHolder app =
          ReactiveTestApplicationSupport.startReactiveApp(
              Map.of(
                  "spring.grpc.server.port",
                  "0",
                  "management.endpoint.health.group.liveness.include",
                  "livenessState",
                  "management.endpoint.health.group.readiness.include",
                  "readinessState,gameplayPathReadiness"),
              GameSessionStubApplication.class);
      return new GameSessionStubHolder(app, app.context().getBean(GameSessionStub.class));
    } catch (RuntimeException ex) {
      throw new IllegalStateException("Failed to start game session stub", ex);
    }
  }

  private static ReactiveTestApplicationSupport.ReactiveAppHolder startGateway(
      int gameSessionPort) {
    try {
      return ReactiveTestApplicationSupport.startReactiveApp(
          Map.of(
              "gateway.stub.target-uri",
              "ws://localhost:" + gameSessionPort + "/ws/game",
              "management.endpoint.health.group.liveness.include",
              "livenessState",
              "management.endpoint.health.group.readiness.include",
              "readinessState,gameplayRouteReadiness"),
          GatewayStubApplication.class);
    } catch (RuntimeException ex) {
      throw new IllegalStateException(
          "Failed to start gateway (gameSessionPort=" + gameSessionPort + ")", ex);
    }
  }

  private static final class GameSessionStubHolder {
    private final ReactiveTestApplicationSupport.ReactiveAppHolder app;
    private final GameSessionStub stub;

    private GameSessionStubHolder(
        ReactiveTestApplicationSupport.ReactiveAppHolder app, GameSessionStub stub) {
      this.app = app;
      this.stub = stub;
    }

    GameSessionStub stub() {
      return stub;
    }

    int port() {
      return app.port();
    }

    String websocketUrl() {
      return app.websocketUrl();
    }

    void close() {
      app.close();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration(
      excludeName = {
        "org.springframework.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration",
        "org.springframework.boot.grpc.server.autoconfigure.GrpcServerFactoryAutoConfiguration",
        "org.springframework.boot.grpc.server.autoconfigure.health.GrpcServerHealthAutoConfiguration",
        "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration"
      })
  @Import(GameSessionStubConfiguration.class)
  static class GameSessionStubApplication {}

  @Configuration
  static class GameSessionStubConfiguration {
    @Bean
    GameSessionStub gameSessionStub() {
      return new GameSessionStub();
    }

    @Bean
    WebSocketHandler gameSessionWebSocketHandler(GameSessionStub stub) {
      return new GameSessionWebSocketHandler(stub);
    }

    @Bean
    HandlerMapping gameSessionWebSocketMapping(WebSocketHandler handler) {
      SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
      mapping.setOrder(-1);
      Map<String, WebSocketHandler> urlMap = new HashMap<>();
      urlMap.put("/ws/game", handler);
      urlMap.put("/ws/game/**", handler);
      mapping.setUrlMap(urlMap);
      return mapping;
    }

    @Bean
    WebSocketHandlerAdapter webSocketHandlerAdapter() {
      return new WebSocketHandlerAdapter();
    }

    @Bean("gameplayPathReadiness")
    HealthIndicator gameplayPathReadinessHealthIndicator() {
      return () -> Health.up().withDetail("stub", "UP").build();
    }

    @Bean("trafficAdmissionReadiness")
    HealthIndicator trafficAdmissionReadinessHealthIndicator() {
      return () -> Health.up().withDetail("stub", "UP").build();
    }
  }

  // No additional configuration is required for the gateway stub because it is provided by
  // GatewayStubApplication.

  private static final class GameSessionStub {
    private final Queue<String> commands = new ConcurrentLinkedQueue<>();

    void recordCommand(String command) {
      commands.add(command);
    }

    List<String> receivedCommands() {
      return new ArrayList<>(commands);
    }
  }

  private List<String> runGatewayWebSocketCommands(String... commands) throws Exception {
    try (WebSocketTestProbe probe =
        WebSocketTestProbe.connect(GATEWAY.websocketUrl(), new WebSocketHttpHeaders())) {
      for (String command : commands) {
        probe.send(command);
      }
      for (String command : commands) {
        if ("LOOK".equalsIgnoreCase(command)) {
          probe.awaitMessage(
              LookTestFixtures.canonicalLookText()::equals, "LOOK response", COMMAND_WAIT);
          continue;
        }
        probe.awaitStartsWith("processed:" + command, COMMAND_WAIT);
      }
      return probe.responses();
    }
  }

  private static final class GameSessionWebSocketHandler implements WebSocketHandler {
    private final GameSessionStub stub;

    private GameSessionWebSocketHandler(GameSessionStub stub) {
      this.stub = stub;
    }

    private String responsePayload(String command) {
      if ("LOOK".equalsIgnoreCase(command)) {
        return LookTestFixtures.canonicalLookText();
      }
      return "processed:" + command;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
      Flux<String> commands =
          session
              .receive()
              .map(WebSocketMessage::getPayloadAsText)
              .map(String::trim)
              .filter(StringUtils::hasText)
              .doOnNext(stub::recordCommand);

      return commands.concatMap(command -> handleCommand(session, command)).then();
    }

    private Mono<Void> handleCommand(WebSocketSession session, String command) {
      if ("FORCE_CLOSE_GATEWAY_RESTART".equalsIgnoreCase(command)) {
        return session.close(new CloseStatus(1000, "logout;subreason=gateway_restart"));
      }
      return session.send(Mono.just(session.textMessage(responsePayload(command))));
    }
  }
}
