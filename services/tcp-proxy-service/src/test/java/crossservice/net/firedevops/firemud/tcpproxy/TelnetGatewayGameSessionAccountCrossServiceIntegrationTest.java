package net.firedevops.firemud.tcpproxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.common.conflict.ConflictTracker;
import net.firedevops.firemud.gamelogic.GameLogicServiceApplication;
import net.firedevops.firemud.gamesession.GameSessionServiceApplication;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.test.LookTestFixtures;
import net.firedevops.firemud.gamesession.test.stubs.EntityManagementStubServer;
import net.firedevops.firemud.gamesession.test.stubs.WorldManagementStubServer;
import net.firedevops.firemud.springcloudgateway.service.GatewayRoute;
import net.firedevops.firemud.springcloudgateway.service.GatewayRouteService;
import net.firedevops.firemud.tcpproxy.stub.GatewayStubApplication;
import net.firedevops.firemud.tcpproxy.telnet.TelnetServer;
import net.firedevops.firemud.test.HttpTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.grpc.server.service.GrpcServiceDiscoverer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.TestSocketUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
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
  private static EntityManagementStubServer ENTITY_STUB;
  private static GameLogicHolder GAME_LOGIC;
  private static GameSessionHolder GAME_SESSION;
  private static GatewayHolder GATEWAY;

  @LocalServerPort private int port;
  @Autowired private TelnetServer telnetServer;
  @Autowired private ConfigurableApplicationContext applicationContext;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

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

    EntityManagementStubServer entityStub = ENTITY_STUB;
    ENTITY_STUB = null;
    if (entityStub != null) {
      entityStub.close();
    }
  }

  @Test
  void readinessEndpointReportsTrafficAdmissionReady() throws Exception {
    ensureTestServicesStarted();
    String body =
        HttpTestSupport.getBody("http://localhost:" + port + "/actuator/health/readiness");
    assertThat(body).contains("\"status\":\"UP\"");
    Health health =
        applicationContext.getBean("trafficAdmissionReadiness", HealthIndicator.class).health();
    assertThat(health.getStatus()).isEqualTo(org.springframework.boot.health.contributor.Status.UP);
  }

  @Test
  void telnetLoginAndLookMatchCanonicalGameplayFlow() throws Exception {
    ensureTestServicesStarted();
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
      telnetLoginResponse = readBlockAfterContains(reader, "Logged in as demo@example.com");
      assertThat(telnetLoginResponse).contains("Logged in as demo@example.com");
      writer.println("LOOK");
      telnetLookResponse = readBlockAfterContains(reader, "OK LOOK");
    }

    assertThat(telnetLookResponse.trim()).isEqualTo(LookTestFixtures.canonicalLookText().trim());

    assertThat(ACCOUNT_STUB.capturedRequests())
        .anyMatch(
            request ->
                request.getUsername().equals("demo@example.com")
                    && request.getPassword().equals("swordfish")
                    && request.getTenantId().equals(String.valueOf(TENANT_ID)));
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
    if (ENTITY_STUB == null) {
      try {
        ENTITY_STUB = startEntityStub();
      } catch (IOException e) {
        throw new IllegalStateException("Failed to start entity stub", e);
      }
    }
    if (GAME_LOGIC == null) {
      GAME_LOGIC = startGameLogic(WORLD_STUB.port(), ENTITY_STUB.port());
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

  private static GameLogicHolder startGameLogic(int worldPort, int entityPort) {
    Map<String, Object> props = new java.util.LinkedHashMap<>();
    int grpcPort = TestSocketUtils.findAvailableTcpPort();
    props.put("spring.profiles.active", "test");
    props.put("spring.application.name", "game-logic-service");
    props.put("server.port", "0");
    props.put("spring.grpc.server.port", String.valueOf(grpcPort));
    props.put("firemud.grpc.plaintext", "true");
    props.put("otel.endpoint", "disabled");
    props.put("firemud.database.enabled", "false");
    props.put(
        "spring.autoconfigure.exclude",
        "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration");
    props.put("firemud.services.worldManagementService", "localhost:" + worldPort);
    props.put("firemud.services.entityManagementService", "localhost:" + entityPort);
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(
                GameLogicServiceApplication.class, NestedReadinessOverrides.class)
            .run(toCommandLineArgs(props));
    return new GameLogicHolder(context, grpcPort);
  }

  private static GameSessionHolder startGameSession(int gameLogicPort, int accountPort) {
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(
                GameSessionServiceApplication.class,
                GameSessionTestOverrides.class,
                NestedReadinessOverrides.class)
            .run(toCommandLineArgs(gameSessionProps(gameLogicPort, accountPort)));

    JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
    jdbc.execute(
        """
        CREATE TABLE IF NOT EXISTS game_instances (
          id BIGSERIAL PRIMARY KEY,
          tenant_id BIGINT NOT NULL,
          runtime_version VARCHAR(100) NOT NULL,
          script_patch_version VARCHAR(100),
          script_patch_pinned_at TIMESTAMP NULL,
          script_patch_pinned_by VARCHAR(200) NULL,
          script_patch_pinned_reason VARCHAR(500) NULL,
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
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    return new GameSessionHolder(context, port, insertedId);
  }

  private static Map<String, Object> gameSessionProps(int gameLogicPort, int accountPort) {
    Map<String, Object> props = new java.util.LinkedHashMap<>();
    props.put("spring.profiles.active", "test");
    props.put("spring.application.name", "game-session-service");
    props.put("server.port", "0");
    props.put("spring.grpc.server.port", "0");
    props.put("game-session.dev-isolated", "false");
    props.put("game-session.require-authenticated-commands", "true");
    props.put("firemud.services.accountService", "localhost:" + accountPort);
    props.put("firemud.services.gameLogicService", "localhost:" + gameLogicPort);
    props.put("firemud.grpc.plaintext", "true");
    props.put("otel.endpoint", "disabled");
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
        "management.endpoint.health.group.readiness.include",
        "readinessState,db,redis,gameplayPathReadiness");
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
    props.put(
        "spring.autoconfigure.exclude",
        "org.springframework.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration,"
            + "org.springframework.boot.grpc.server.autoconfigure.GrpcServerFactoryAutoConfiguration,"
            + "org.springframework.boot.grpc.server.autoconfigure.health.GrpcServerHealthAutoConfiguration");
    return props;
  }

  private static GatewayHolder startGateway(int gameSessionPort) {
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(GatewayStubApplication.class)
            .properties(
                "server.port=0",
                "spring.main.web-application-type=reactive",
                "gateway.stub.target-uri=ws://localhost:" + gameSessionPort + "/ws/game")
            .run();
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    return new GatewayHolder(context, port);
  }

  private static String[] toCommandLineArgs(Map<String, Object> props) {
    return props.entrySet().stream()
        .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
        .toArray(String[]::new);
  }

  private static String readBlockAfterContains(BufferedReader reader, String expectedSubstring)
      throws IOException {
    StringBuilder builder = new StringBuilder();
    boolean matched = false;
    String line;
    while ((line = reader.readLine()) != null) {
      builder.append(line).append("\n");
      if (!matched && line.contains(expectedSubstring)) {
        matched = true;
      } else if (matched && line.isEmpty()) {
        break;
      }
    }
    return builder.toString();
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

    @Bean(name = "gameInstanceServiceImpl")
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
    LookCacheService lookCacheService() {
      return new LookCacheService() {
        @Override
        public void cache(
            long tenantId,
            long sessionId,
            String roomId,
            String renderedText,
            String protocolText) {}

        @Override
        public Optional<LookCacheService.CachedLook> get(long tenantId, long sessionId) {
          return Optional.empty();
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
    GrpcServiceDiscoverer grpcServiceDiscoverer() {
      return Mockito.mock(GrpcServiceDiscoverer.class);
    }

    @Bean
    @Primary
    GrpcServerLifecycle grpcServerLifecycle() {
      return Mockito.mock(GrpcServerLifecycle.class);
    }
  }

  @TestConfiguration
  static class NestedReadinessOverrides {

    @Bean("trafficAdmissionReadiness")
    HealthIndicator trafficAdmissionReadinessHealthIndicator() {
      return () -> Health.up().withDetail("stub", "UP").build();
    }
  }
}
