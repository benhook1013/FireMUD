package net.firedevops.firemud;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.telnet.TelnetServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Cross-service integration test verifying the full Telnet → Gateway → Game Session path. */
@Testcontainers
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = TcpProxyServiceApplication.class)
class TelnetGatewayGameSessionCrossServiceIntegrationTest {

  private static final GenericContainer<?> REDIS = startRedis();
  private static final GameSessionStubHolder GAME_SESSION_STUB = startGameSessionStub();
  private static final GatewayHolder GATEWAY = startGateway(GAME_SESSION_STUB.port);
  private static final Duration COMMAND_WAIT = Duration.ofSeconds(5);

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("GATEWAY_WS_URL", GATEWAY::websocketUrl);
    registry.add("TCP_PROXY_PORT", () -> 0);
  }

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Autowired private TelnetServer telnetServer;

  @AfterAll
  static void stopTestServices() {
    if (GATEWAY != null) {
      GATEWAY.close();
    }
    if (GAME_SESSION_STUB != null) {
      GAME_SESSION_STUB.close();
    }
    if (REDIS != null) {
      REDIS.stop();
    }
  }

  @Test
  void telnetCommandFlowsThroughGatewayToGameSession() throws Exception {
    String body = restTemplate.getForObject("http://localhost:" + port + "/ping", String.class);
    assertThat(body).contains("pong");

    try (Socket socket = new Socket("localhost", telnetServer.getPort());
        PrintWriter writer =
            new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1), true);
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))) {
      socket.setSoTimeout((int) COMMAND_WAIT.toMillis());
      writer.println("SESSION 1 1");
      writer.println("look");
      String response = reader.readLine();
      assertThat(response).isEqualTo("processed:look");
    }

    awaitCommand("look");
    assertThat(GAME_SESSION_STUB.stub().receivedCommands()).contains("look");
  }

  private static void awaitCommand(String expected) {
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

  private static GameSessionStubHolder startGameSessionStub() {
    Map<String, Object> props = new HashMap<>();
    props.put("server.port", 0);
    props.put("spring.main.web-application-type", "reactive");
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(GameSessionStubApplication.class).properties(props).run();
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    return new GameSessionStubHolder(context, port, context.getBean(GameSessionStub.class));
  }

  private static GatewayHolder startGateway(int gameSessionPort) {
    Map<String, Object> props = new HashMap<>();
    props.put("server.port", 0);
    props.put("spring.profiles.active", "dev");
    props.put("grpc.server.security.enabled", false);
    props.put("grpc.server.port", 0);
    props.put("spring.redis.host", REDIS.getHost());
    props.put("spring.redis.port", REDIS.getFirstMappedPort());
    props.put("spring.cloud.gateway.routes[0].uri", "ws://localhost:" + gameSessionPort);
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(SpringCloudGatewayApplication.class)
            .properties(props)
            .run();
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    return new GatewayHolder(context, port);
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

  private static GenericContainer<?> startRedis() {
    GenericContainer<?> container =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    container.start();
    return container;
  }

  @SpringBootApplication
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

  private static final class GameSessionWebSocketHandler implements WebSocketHandler {
    private final GameSessionStub stub;

    private GameSessionWebSocketHandler(GameSessionStub stub) {
      this.stub = stub;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
      Flux<String> commands =
          session.receive()
              .map(WebSocketMessage::getPayloadAsText)
              .map(String::trim)
              .filter(StringUtils::hasText)
              .doOnNext(stub::recordCommand);

      Flux<WebSocketMessage> replies =
          commands.map(cmd -> session.textMessage("processed:" + cmd));

      return session.send(replies);
    }
  }
}
