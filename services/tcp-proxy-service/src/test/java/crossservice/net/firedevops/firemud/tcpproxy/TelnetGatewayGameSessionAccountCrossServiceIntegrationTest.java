package crossservice.net.firedevops.firemud.tcpproxy;

import static org.assertj.core.api.Assertions.assertThat;

import crossservice.net.firedevops.firemud.tcpproxy.stub.GatewayStubApplication;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.common.conflict.ConflictTracker;
import net.firedevops.firemud.gamelogic.GameLogicServiceApplication;
import net.firedevops.firemud.gamesession.GameSessionServiceApplication;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.test.ChatTestFixtures;
import net.firedevops.firemud.gamesession.test.LookTestFixtures;
import net.firedevops.firemud.gamesession.test.stubs.ChatEntityManagementStubServer;
import net.firedevops.firemud.gamesession.test.stubs.EntityManagementStubServer;
import net.firedevops.firemud.gamesession.test.stubs.SocialGroupsStubServer;
import net.firedevops.firemud.gamesession.test.stubs.WorldManagementStubServer;
import net.firedevops.firemud.springcloudgateway.service.GatewayRoute;
import net.firedevops.firemud.springcloudgateway.service.GatewayRouteService;
import net.firedevops.firemud.tcpproxy.TcpProxyServiceApplication;
import net.firedevops.firemud.tcpproxy.telnet.TelnetServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.lognet.springboot.grpc.GRpcServerRunner;
import org.lognet.springboot.grpc.GRpcServicesRegistry;
import org.lognet.springboot.grpc.health.ManagedHealthStatusService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = TcpProxyServiceApplication.class)
class TelnetGatewayGameSessionAccountCrossServiceIntegrationTest {

  private static final Duration COMMAND_WAIT = Duration.ofSeconds(5);
  private static final long TENANT_ID = 1L;
  private static final long ACCOUNT_ID = 7L;

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("firemud")
          .withUsername("firemud")
          .withPassword("firemud");

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);

  private static AccountServiceStub ACCOUNT_STUB;
  private static WorldManagementStubServer WORLD_STUB;
  private static ChatEntityManagementStubServer CHAT_ENTITY_STUB;
  private static EntityManagementStubServer ENTITY_STUB;
  private static SocialGroupsStubServer SOCIAL_STUB;
  private static GameLogicHolder GAME_LOGIC;
  private static GameSessionHolder GAME_SESSION;
  private static GatewayHolder GATEWAY;

  @Autowired private TelnetServer telnetServer;

  @SuppressWarnings("removal")
  @MockBean
  private GRpcServerRunner grpcServerRunner;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    ensureTestServicesStarted();
    registry.add("GATEWAY_WS_URL", GATEWAY::websocketUrl);
    registry.add("TCP_PROXY_PORT", () -> 0);
    registry.add("firemud.redis.host", REDIS::getHost);
    registry.add("firemud.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @AfterAll
  static synchronized void stopTestServices() {
    GatewayHolder gateway = GATEWAY;
    GATEWAY = null;
    if (gateway != null) {
      gateway.close();
    }
    GameSessionHolder gameSession = GAME_SESSION;
    GAME_SESSION = null;
    if (gameSession != null) {
      gameSession.close();
    }
    AccountServiceStub accountStub = ACCOUNT_STUB;
    ACCOUNT_STUB = null;
    if (accountStub != null) {
      accountStub.close();
    }
    GameLogicHolder gameLogic = GAME_LOGIC;
    GAME_LOGIC = null;
    if (gameLogic != null) {
      gameLogic.close();
    }
    WorldManagementStubServer worldStub = WORLD_STUB;
    WORLD_STUB = null;
    if (worldStub != null) {
      worldStub.close();
    }
    ChatEntityManagementStubServer chatEntityStub = CHAT_ENTITY_STUB;
    CHAT_ENTITY_STUB = null;
    if (chatEntityStub != null) {
      chatEntityStub.close();
    }
    EntityManagementStubServer entityStub = ENTITY_STUB;
    ENTITY_STUB = null;
    if (entityStub != null) {
      entityStub.close();
    }
    SocialGroupsStubServer socialStub = SOCIAL_STUB;
    SOCIAL_STUB = null;
    if (socialStub != null) {
      socialStub.close();
    }
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
      writer.println("SESSION " + GAME_SESSION.sessionId() + " " + TENANT_ID);
      writer.println("LOGIN demo@example.com swordfish");
      telnetLoginResponse = reader.readLine();
      assertThat(telnetLoginResponse).isNotNull();
      writer.println("LOOK");
      telnetLookResponse = readMultiLineResponse(reader);

      WORLD_STUB.triggerNotFound("room missing for regression");
      writer.println("LOOK");
      String telnetFailureResponse = readMultiLineResponse(reader);
      assertThat(telnetFailureResponse).startsWith("ERROR ROOM_NOT_FOUND");
    }

    assertThat(websocketResponses).hasSizeGreaterThanOrEqualTo(2);
    assertThat(websocketResponses.get(1).trim())
        .isEqualTo(LookTestFixtures.canonicalLookText().trim());
    assertThat(telnetLoginResponse).isEqualTo(websocketResponses.get(0));
    assertThat(telnetLookResponse.trim()).isEqualTo(LookTestFixtures.canonicalLookText().trim());

    assertThat(ACCOUNT_STUB.capturedRequests())
        .anyMatch(
            request ->
                request.getUsername().equals("demo@example.com")
                    && request.getPassword().equals("swordfish")
                    && request.getTenantId().equals(String.valueOf(TENANT_ID)));
  }

  @Test
  void telnetSayReturnsCanonicalTranscriptAndSocialCall() throws Exception {
    ensureTestServicesStarted();
    String telnetSayResponse;
    try (Socket socket = new Socket("localhost", telnetServer.getPort());
        PrintWriter writer =
            new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1),
                true);
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))) {
      socket.setSoTimeout((int) COMMAND_WAIT.toMillis());
      writer.println("SESSION " + GAME_SESSION.sessionId() + " " + TENANT_ID);
      writer.println("LOGIN demo@example.com swordfish");
      reader.readLine(); // consume login response
      writer.println("SAY Hello travelers");
      telnetSayResponse = readMultiLineResponse(reader);
    }

    assertThat(telnetSayResponse.trim()).isEqualTo(ChatTestFixtures.canonicalSayText());
    assertThat(SOCIAL_STUB.lastRequest())
        .hasValueSatisfying(
            request -> {
              assertThat(request.getContent()).isEqualTo("Hello travelers");
              assertThat(request.getType())
                  .isEqualTo(net.firedevops.firemud.socialgroups.v1.ChatType.CHAT_TYPE_SAY);
            });

    assertMetricEventually("gamesession_command_say_invocations_total{tenantId=\"1\"}", 1.0);
  }

  private static synchronized void ensureTestServicesStarted() {
    if (ACCOUNT_STUB == null) {
      try {
        ACCOUNT_STUB = startAccountStub();
      } catch (IOException e) {
        throw new IllegalStateException("Failed to start account stub", e);
      }
    }
    if (WORLD_STUB == null) {
      try {
        WORLD_STUB = startWorldStub();
      } catch (IOException e) {
        throw new IllegalStateException("Failed to start world stub", e);
      }
    }
    if (CHAT_ENTITY_STUB == null) {
      try {
        CHAT_ENTITY_STUB = startChatEntityStub();
      } catch (IOException e) {
        throw new IllegalStateException("Failed to start chat entity stub", e);
      }
    }
    if (ENTITY_STUB == null) {
      try {
        ENTITY_STUB = startEntityStub();
      } catch (IOException e) {
        throw new IllegalStateException("Failed to start entity stub", e);
      }
    }
    if (SOCIAL_STUB == null) {
      try {
        SOCIAL_STUB = startSocialStub();
      } catch (IOException e) {
        throw new IllegalStateException("Failed to start social stub", e);
      }
    }
    if (GAME_LOGIC == null) {
      GAME_LOGIC = startGameLogic(WORLD_STUB.port(), ENTITY_STUB.port(), SOCIAL_STUB.port());
    }
    if (GAME_SESSION == null) {
      GAME_SESSION = startGameSession(GAME_LOGIC.grpcPort(), ACCOUNT_STUB.port());
    }
    if (GATEWAY == null) {
      GATEWAY = startGateway(GAME_SESSION.port());
    }
  }

  private static AccountServiceStub startAccountStub() throws IOException {
    int port = TestSocketUtils.findAvailableTcpPort();
    return new AccountServiceStub(port);
  }

  private static WorldManagementStubServer startWorldStub() throws IOException {
    return new WorldManagementStubServer(TestSocketUtils.findAvailableTcpPort());
  }

  private static EntityManagementStubServer startEntityStub() throws IOException {
    return new EntityManagementStubServer(TestSocketUtils.findAvailableTcpPort());
  }

  private static ChatEntityManagementStubServer startChatEntityStub() throws IOException {
    return new ChatEntityManagementStubServer(TestSocketUtils.findAvailableTcpPort());
  }

  private static SocialGroupsStubServer startSocialStub() throws IOException {
    return new SocialGroupsStubServer(TestSocketUtils.findAvailableTcpPort());
  }

  private static GameLogicHolder startGameLogic(int worldPort, int entityPort, int socialPort) {
    int grpcPort = TestSocketUtils.findAvailableTcpPort();
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("server.port", "0");
    props.put("grpc.server.port", String.valueOf(grpcPort));
    props.put("grpc.server.security.enabled", "false");
    props.put("firemud.grpc.plaintext", "true");
    props.put("firemud.services.worldManagementService", "localhost:" + worldPort);
    props.put("firemud.services.entityManagementService", "localhost:" + entityPort);
    props.put("firemud.services.socialGroupsService", "localhost:" + socialPort);
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(GameLogicServiceApplication.class).properties(props).run();
    return new GameLogicHolder(context, grpcPort);
  }

  private static GameSessionHolder startGameSession(int gameLogicPort, int accountPort) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("server.port", "0");
    props.put("grpc.server.port", "0");
    props.put("grpc.server.enabled", "false");
    props.put("game-session.dev-isolated", "false");
    props.put("game-session.require-authenticated-commands", "true");
    props.put("firemud.services.accountService", "localhost:" + accountPort);
    props.put("firemud.services.gameLogicService", "localhost:" + gameLogicPort);
    props.put("firemud.grpc.plaintext", "true");
    props.put("game.logic.default-room-id", LookTestFixtures.ROOM_ID);
    props.put("firemud.postgres.host", POSTGRES.getHost());
    props.put("firemud.postgres.port", String.valueOf(POSTGRES.getMappedPort(5432)));
    props.put("firemud.postgres.database", POSTGRES.getDatabaseName());
    props.put("firemud.postgres.username", POSTGRES.getUsername());
    props.put("firemud.postgres.password", POSTGRES.getPassword());
    props.put("firemud.redis.host", REDIS.getHost());
    props.put("firemud.redis.port", String.valueOf(REDIS.getMappedPort(6379)));
    props.put("firemud.database.enabled", "true");
    props.put("spring.main.allow-bean-definition-overriding", "true");
    props.put("firemud.auth.jwt-secret", "stub-secret");
    props.put(
        "spring.datasource.url",
        "jdbc:postgresql://"
            + POSTGRES.getHost()
            + ":"
            + POSTGRES.getMappedPort(5432)
            + "/"
            + POSTGRES.getDatabaseName());
    props.put("spring.datasource.username", POSTGRES.getUsername());
    props.put("spring.datasource.password", POSTGRES.getPassword());
    props.put("spring.jpa.hibernate.ddl-auto", "none");
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(
                GameSessionServiceApplication.class, GameSessionTestOverrides.class)
            .properties(props)
            .run();

    JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
    jdbc.execute(
        """
        CREATE TABLE IF NOT EXISTS game_instances (
          id BIGSERIAL PRIMARY KEY,
          tenant_id BIGINT NOT NULL,
          runtime_version VARCHAR(100) NOT NULL,
          script_patch_version VARCHAR(100),
          owner_account_id BIGINT NOT NULL,
          status VARCHAR(20) NOT NULL
        )
        """);
    long insertedId =
        Optional.ofNullable(
                jdbc.queryForObject(
                    "INSERT INTO game_instances (tenant_id, runtime_version, script_patch_version, owner_account_id, status) VALUES (?, ?, ?, ?, ?) RETURNING id",
                    Long.class,
                    TENANT_ID,
                    "0.1.0",
                    "initial",
                    ACCOUNT_ID,
                    "ACTIVE"))
            .orElseThrow(
                () -> new IllegalStateException("Game instance insert did not return an id"));
    int port =
        ((org.springframework.boot.web.context.WebServerApplicationContext) context)
            .getWebServer()
            .getPort();
    return new GameSessionHolder(context, port, insertedId);
  }

  private static GatewayHolder startGateway(int gameSessionPort) {
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(GatewayStubApplication.class)
            .properties(
                "server.port=0",
                "spring.main.web-application-type=reactive",
                "gateway.stub.target-uri=ws://localhost:" + gameSessionPort + "/ws/game")
            .run();
    int port =
        ((org.springframework.boot.web.context.WebServerApplicationContext) context)
            .getWebServer()
            .getPort();
    return new GatewayHolder(context, port);
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

  private void assertMetricEventually(String metric, double expectedValue) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    URI uri = URI.create("http://localhost:" + GAME_SESSION.port() + "/actuator/prometheus");
    long deadline = System.currentTimeMillis() + COMMAND_WAIT.toMillis();
    while (System.currentTimeMillis() < deadline) {
      HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.body().contains(metric + " " + expectedValue)) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("Metric " + metric + " did not reach " + expectedValue);
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

  private static final class AccountServiceStub extends AccountServiceGrpc.AccountServiceImplBase {
    private final Server server;
    private final List<AuthenticateRequest> requests = new CopyOnWriteArrayList<>();
    private final int port;

    AccountServiceStub(int port) throws IOException {
      this.port = port;
      this.server = ServerBuilder.forPort(port).addService(this).build().start();
    }

    List<AuthenticateRequest> capturedRequests() {
      return List.copyOf(requests);
    }

    int port() {
      return port;
    }

    @Override
    public void authenticate(
        AuthenticateRequest request, StreamObserver<AuthenticateResponse> responseObserver) {
      requests.add(request);
      AuthenticateResponse response =
          AuthenticateResponse.newBuilder()
              .setAccountId(String.valueOf(ACCOUNT_ID))
              .setAuthToken("stub-token")
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }

    void close() {
      server.shutdownNow();
    }
  }

  private static final class GameSessionHolder {
    private final ConfigurableApplicationContext context;
    private final int port;
    private final long sessionId;

    GameSessionHolder(ConfigurableApplicationContext context, int port, long sessionId) {
      this.context = context;
      this.port = port;
      this.sessionId = sessionId;
    }

    int port() {
      return port;
    }

    long sessionId() {
      return sessionId;
    }

    void close() {
      context.close();
    }
  }

  private static final class GatewayHolder {
    private final ConfigurableApplicationContext context;
    private final int port;

    GatewayHolder(ConfigurableApplicationContext context, int port) {
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

  private static final class GameLogicHolder {
    private final ConfigurableApplicationContext context;
    private final int grpcPort;

    GameLogicHolder(ConfigurableApplicationContext context, int grpcPort) {
      this.context = context;
      this.grpcPort = grpcPort;
    }

    int grpcPort() {
      return grpcPort;
    }

    void close() {
      context.close();
    }
  }

  @TestConfiguration
  static class GameSessionTestOverrides {

    @Bean
    RedisConnectionFactory redisConnectionFactory() {
      LettuceConnectionFactory factory =
          new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
      factory.afterPropertiesSet();
      return factory;
    }

    @Bean
    RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
      RedisTemplate<String, Object> template = new RedisTemplate<>();
      template.setConnectionFactory(factory);
      template.afterPropertiesSet();
      return template;
    }

    @Bean
    StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
      StringRedisTemplate template = new StringRedisTemplate();
      template.setConnectionFactory(factory);
      template.afterPropertiesSet();
      return template;
    }

    @Bean
    @Primary
    ConflictTracker conflictTracker() {
      return key -> {};
    }

    @Bean
    @Primary
    GameInstanceService stubGameInstanceService() {
      return new GameInstanceService() {
        @Override
        public GameInstanceDto startSession(StartSessionRequest request) {
          return new GameInstanceDto(
              request.ownerAccountId(),
              request.tenantId(),
              request.runtimeVersion(),
              request.scriptPatchVersion(),
              request.ownerAccountId(),
              "RUNNING");
        }

        @Override
        public GameInstanceDto stopSession(long sessionId) {
          return new GameInstanceDto(sessionId, 0L, "stub", null, 0L, "STOPPED");
        }

        @Override
        public GameInstanceDto restartSession(long sessionId) {
          return new GameInstanceDto(sessionId, 0L, "stub", null, 0L, "RUNNING");
        }
      };
    }

    @Bean
    @Primary
    GatewayRouteService gatewayRouteService() {
      return new GatewayRouteService() {
        @Override
        public GatewayRoute upsert(GatewayRoute route) {
          return route;
        }

        @Override
        public boolean remove(String routeId) {
          return true;
        }
      };
    }

    @Bean
    @Primary
    Tracer tracer() {
      return GlobalOpenTelemetry.getTracer("test");
    }

    @Bean
    @Primary
    GRpcServicesRegistry grpcServicesRegistry() {
      return new GRpcServicesRegistry();
    }

    @Bean
    @Primary
    ManagedHealthStatusService managedHealthStatusService() {
      return new ManagedHealthStatusService() {
        @Override
        public void onShutdown() {}

        @Override
        public void setStatus(String service, HealthCheckResponse.ServingStatus status) {}

        @Override
        public java.util.Map<String, HealthCheckResponse.ServingStatus> statuses() {
          return java.util.Collections.emptyMap();
        }
      };
    }
  }
}
