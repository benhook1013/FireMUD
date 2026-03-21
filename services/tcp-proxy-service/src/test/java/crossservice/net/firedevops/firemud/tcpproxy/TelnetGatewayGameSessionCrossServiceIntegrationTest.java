package net.firedevops.firemud.tcpproxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.gamesession.test.LookTestFixtures;
import net.firedevops.firemud.tcpproxy.stub.GatewayStubApplication;
import net.firedevops.firemud.tcpproxy.telnet.TelnetServer;
import net.firedevops.firemud.test.HttpTestSupport;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Cross-service integration test verifying the full Telnet → Gateway → Game Session path. */
@Testcontainers
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = TcpProxyServiceApplication.class)
@Import(NoGrpcServerTestConfiguration.class)
class TelnetGatewayGameSessionCrossServiceIntegrationTest {

  private static GameSessionStubHolder GAME_SESSION_STUB;
  private static GatewayHolder GATEWAY;
  private static final Duration COMMAND_WAIT = Duration.ofSeconds(5);

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    ensureTestServicesStarted();
    registry.add("GATEWAY_WS_URL", GATEWAY::websocketUrl);
    registry.add("TCP_PROXY_PORT", () -> 0);
  }

  @LocalServerPort private int port;

  @Autowired private TelnetServer telnetServer;

  @AfterAll
  static synchronized void stopTestServices() {
    GatewayHolder gateway = GATEWAY;
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
  void telnetCommandFlowsThroughGatewayToGameSession() throws Exception {
    ensureTestServicesStarted();
    String body = HttpTestSupport.getBody("http://localhost:" + port + "/ping");
    assertThat(body).contains("pong");

    try (Socket socket = new Socket("localhost", telnetServer.getPort());
        PrintWriter writer =
            new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1),
                true);
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))) {
      socket.setSoTimeout((int) COMMAND_WAIT.toMillis());
      writer.println("SESSION 1 1");
      writer.println("look");
      String response = readMultiLineResponse(reader);
      assertThat(response.trim()).isEqualTo(LookTestFixtures.canonicalLookText().trim());
    }

    awaitCommand("look");
    assertThat(GAME_SESSION_STUB.stub().receivedCommands()).contains("look");
  }

  @Test
  void telnetLoginMatchesGatewayWebSocketResponses() throws Exception {
    ensureTestServicesStarted();
    List<String> websocketResponses =
        runGatewayWebSocketCommands("LOGIN demo@example.com swordfish", "LOOK");

    String telnetLoginResponse;
    String telnetLookResponse;
    try (Socket socket = new Socket("localhost", telnetServer.getPort());
        PrintWriter writer =
            new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1),
                true);
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))) {
      socket.setSoTimeout((int) COMMAND_WAIT.toMillis());
      writer.println("SESSION 2 2");
      writer.println("LOGIN demo@example.com swordfish");
      telnetLoginResponse = reader.readLine();
      assertThat(telnetLoginResponse).isNotNull();
      writer.println("LOOK");
      telnetLookResponse = readMultiLineResponse(reader);
    }

    assertThat(websocketResponses).hasSizeGreaterThanOrEqualTo(2);
    assertThat(telnetLoginResponse).isEqualTo(websocketResponses.get(0));
    assertThat(telnetLookResponse.trim()).isEqualTo(websocketResponses.get(1).trim());
  }

  private static void awaitCommand(String expected) {
    ensureTestServicesStarted();
    long deadline = System.nanoTime() + COMMAND_WAIT.toNanos();
    while (System.nanoTime() < deadline) {
      if (GAME_SESSION_STUB.stub().receivedCommands().contains(expected)) {
        return;
      }
      try {
        TimeUnit.MILLISECONDS.sleep(50);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(ex);
      }
    }
    assertThat(GAME_SESSION_STUB.stub().receivedCommands()).contains(expected);
  }

  private static synchronized void ensureTestServicesStarted() {
    if (GAME_SESSION_STUB == null) {
      GAME_SESSION_STUB = startGameSessionStub();
    }
    if (GATEWAY == null) {
      GATEWAY = startGateway(GAME_SESSION_STUB.port);
    }
  }

  private static GameSessionStubHolder startGameSessionStub() {
    int grpcPort = TestSocketUtils.findAvailableTcpPort();
    try {
      ConfigurableApplicationContext context =
          new SpringApplicationBuilder(GameSessionStubApplication.class)
              .properties(
                  "server.port=0",
                  "spring.main.web-application-type=reactive",
                  "spring.grpc.server.port=0",
                  "management.endpoint.health.group.liveness.include=livenessState",
                  "management.endpoint.health.group.readiness.include=readinessState,gameplayPathReadiness")
              .run();
      int port = ((WebServerApplicationContext) context).getWebServer().getPort();
      return new GameSessionStubHolder(context, port, context.getBean(GameSessionStub.class));
    } catch (RuntimeException ex) {
      throw new IllegalStateException(
          "Failed to start game session stub (grpcPort=" + grpcPort + ")", ex);
    }
  }

  private static GatewayHolder startGateway(int gameSessionPort) {
    try {
      ConfigurableApplicationContext context =
          new SpringApplicationBuilder(GatewayStubApplication.class)
              .properties(
                  "server.port=0",
                  "spring.main.web-application-type=reactive",
                  "gateway.stub.target-uri=ws://localhost:" + gameSessionPort + "/ws/game",
                  "management.endpoint.health.group.liveness.include=livenessState",
                  "management.endpoint.health.group.readiness.include=readinessState,gameplayRouteReadiness")
              .run();
      int port = ((WebServerApplicationContext) context).getWebServer().getPort();
      return new GatewayHolder(context, port);
    } catch (RuntimeException ex) {
      throw new IllegalStateException(
          "Failed to start gateway (gameSessionPort=" + gameSessionPort + ")", ex);
    }
  }

  private static final class GameSessionStubHolder {
    private final ConfigurableApplicationContext context;
    private final int port;
    private final GameSessionStub stub;

    private GameSessionStubHolder(
        ConfigurableApplicationContext context, int port, GameSessionStub stub) {
      this.context = context;
      this.port = port;
      this.stub = stub;
    }

    GameSessionStub stub() {
      return stub;
    }

    void close() {
      context.close();
    }
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

  private static String readMultiLineResponse(BufferedReader reader) throws IOException {
    StringBuilder builder = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) {
      builder.append(line).append("\n");
      if (line.isEmpty()) {
        break;
      }
    }
    return builder.toString();
  }

  private List<String> runGatewayWebSocketCommands(String loginCommand, String lookCommand)
      throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    List<String> responses = new CopyOnWriteArrayList<>();
    CompletableFuture<Void> responsesReady = new CompletableFuture<>();
    WebSocket webSocket =
        client
            .newWebSocketBuilder()
            .buildAsync(
                URI.create(GATEWAY.websocketUrl()),
                new Listener() {
                  private int received;

                  @Override
                  public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                  }

                  @Override
                  public CompletionStage<?> onText(
                      WebSocket webSocket, CharSequence data, boolean last) {
                    responses.add(data.toString());
                    received++;
                    webSocket.request(1);
                    if (received >= 2 && !responsesReady.isDone()) {
                      responsesReady.complete(null);
                    }
                    return Listener.super.onText(webSocket, data, last);
                  }
                })
            .join();
    webSocket.sendText(loginCommand, true).join();
    webSocket.sendText(lookCommand, true).join();
    responsesReady.get(COMMAND_WAIT.toMillis(), TimeUnit.MILLISECONDS);
    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    return responses;
  }

  private static final class GameSessionWebSocketHandler implements WebSocketHandler {
    private final GameSessionStub stub;

    private GameSessionWebSocketHandler(GameSessionStub stub) {
      this.stub = stub;
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

      Flux<WebSocketMessage> replies =
          commands.map(command -> session.textMessage(responsePayload(command)));

      return session.send(replies);
    }

    private String responsePayload(String command) {
      if ("LOOK".equalsIgnoreCase(command)) {
        return LookTestFixtures.canonicalLookText();
      }
      return "processed:" + command;
    }
  }
}
