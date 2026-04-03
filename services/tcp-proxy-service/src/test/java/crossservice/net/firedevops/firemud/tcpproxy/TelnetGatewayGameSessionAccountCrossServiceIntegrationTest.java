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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import javax.sql.DataSource;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.common.conflict.ConflictTracker;
import net.firedevops.firemud.gamelogic.GameLogicServiceApplication;
import net.firedevops.firemud.gamesession.GameSessionServiceApplication;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.service.ActiveTransportSessionRegistry;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.test.ChatTestFixtures;
import net.firedevops.firemud.gamesession.test.LookTestFixtures;
import net.firedevops.firemud.gamesession.test.stubs.EntityManagementStubServer;
import net.firedevops.firemud.gamesession.test.stubs.SocialGroupsStubServer;
import net.firedevops.firemud.gamesession.test.stubs.WorldManagementStubServer;
import net.firedevops.firemud.socialgroups.v1.ChatType;
import net.firedevops.firemud.springcloudgateway.service.GatewayRoute;
import net.firedevops.firemud.springcloudgateway.service.GatewayRouteService;
import net.firedevops.firemud.tcpproxy.stub.GatewayStubApplication;
import net.firedevops.firemud.tcpproxy.telnet.TelnetServer;
import net.firedevops.firemud.test.HttpTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
  private static final long SORA_ACCOUNT_ID = Long.parseLong(ChatTestFixtures.PLAYER_SORA);
  private static final long DEMO_WORLD_INSTANCE_ID = 1L;

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
  private static SocialGroupsStubServer SOCIAL_STUB;
  private static GameLogicHolder GAME_LOGIC;
  private static GameSessionHolder GAME_SESSION;
  private static GatewayHolder GATEWAY;

  @LocalServerPort private int port;
  @Autowired private TelnetServer telnetServer;
  @Autowired private ConfigurableApplicationContext applicationContext;
  @Autowired private StringRedisTemplate stringRedisTemplate;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    ensureTestServicesStarted();
    registry.add("GATEWAY_WS_URL", GATEWAY::websocketUrl);
    registry.add("TCP_PROXY_PORT", () -> 0);
    registry.add(
        "TCP_PROXY_DEFAULT_GAME_INSTANCE_ID", () -> String.valueOf(GAME_SESSION.sessionId()));
    registry.add("TCP_PROXY_DEFAULT_TENANT_ID", () -> String.valueOf(TENANT_ID));
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

    SocialGroupsStubServer socialStub = SOCIAL_STUB;
    SOCIAL_STUB = null;
    if (socialStub != null) {
      socialStub.close();
    }
  }

  @BeforeEach
  void clearSharedRuntimeState() {
    stringRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    ACCOUNT_STUB.setGameplayAdmissionAllowed(true);
    ACCOUNT_STUB.setGameplayAvailable(true);
    if (ENTITY_STUB != null) {
      ENTITY_STUB.resetRoomEntities();
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
  void telnetLoginAndLookMatchTransportFlowWithoutSession() throws Exception {
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
      writer.println("LOGIN demo@example.com swordfish");
      telnetLoginResponse = readBlockAfterContains(reader, "Logged in as demo@example.com");
      assertThat(telnetLoginResponse).contains("Logged in as demo@example.com");
      writer.println("PLAY demo");
      assertThat(readLineAfterContains(reader, "OK PLAY Entered world: demo"))
          .contains("OK PLAY Entered world: demo");
      writer.println("LOOK");
      telnetLookResponse = readBlockAfterContains(reader, "OK LOOK");
    }

    assertThat(telnetLookResponse.trim()).matches(matchesCanonicalLookWithOptionalPrompt());

    assertThat(ACCOUNT_STUB.capturedRequests())
        .anyMatch(
            request ->
                request.getUsername().equals("demo@example.com")
                    && request.getPassword().equals("swordfish")
                    && request.getTenantId().equals(String.valueOf(TENANT_ID)));
  }

  @Test
  void telnetReconnectAfterRevocationFailsPlayAdmission() throws Exception {
    ensureTestServicesStarted();
    try (Socket firstSocket = new Socket("localhost", telnetServer.getPort());
        PrintWriter firstWriter =
            new PrintWriter(
                new OutputStreamWriter(firstSocket.getOutputStream(), StandardCharsets.ISO_8859_1),
                true);
        BufferedReader firstReader =
            new BufferedReader(
                new InputStreamReader(firstSocket.getInputStream(), StandardCharsets.ISO_8859_1))) {
      firstSocket.setSoTimeout((int) COMMAND_WAIT.toMillis());
      firstWriter.println("LOGIN demo@example.com swordfish");
      assertThat(readBlockAfterContains(firstReader, "Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      firstWriter.println("PLAY demo");
      assertThat(readLineAfterContains(firstReader, "OK PLAY Entered world: demo"))
          .contains("OK PLAY Entered world: demo");
    }

    ACCOUNT_STUB.setGameplayAdmissionAllowed(false);

    try (Socket secondSocket = new Socket("localhost", telnetServer.getPort());
        PrintWriter secondWriter =
            new PrintWriter(
                new OutputStreamWriter(secondSocket.getOutputStream(), StandardCharsets.ISO_8859_1),
                true);
        BufferedReader secondReader =
            new BufferedReader(
                new InputStreamReader(
                    secondSocket.getInputStream(), StandardCharsets.ISO_8859_1))) {
      secondSocket.setSoTimeout((int) COMMAND_WAIT.toMillis());
      secondWriter.println("LOGIN demo@example.com swordfish");
      assertThat(readBlockAfterContains(secondReader, "Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");

      secondWriter.println("PLAY demo");
      assertThat(readLineAfterContains(secondReader, "ERROR WORLD_ACCESS_DENIED"))
          .contains("ERROR WORLD_ACCESS_DENIED")
          .contains("You are not allowed to enter that world.");
    }
  }

  @Test
  void telnetMovementMatchesCanonicalDestinationLookWithoutSession() throws Exception {
    ensureTestServicesStarted();
    String telnetMoveResponse;
    String telnetLookResponse;
    String telnetInvalidMoveResponse;
    try (Socket socket = new Socket("localhost", telnetServer.getPort());
        PrintWriter writer =
            new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1),
                true);
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))) {
      socket.setSoTimeout((int) COMMAND_WAIT.toMillis());
      writer.println("LOGIN demo@example.com swordfish");
      assertThat(readBlockAfterContains(reader, "Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      writer.println("PLAY demo");
      assertThat(readLineAfterContains(reader, "OK PLAY Entered world: demo"))
          .contains("OK PLAY Entered world: demo");

      writer.println("MOVE north");
      telnetMoveResponse = readBlockAfterContainsOrTimeout(reader, "OK LOOK");

      writer.println("LOOK");
      telnetLookResponse = readBlockAfterContainsOrTimeout(reader, "OK LOOK");

      writer.println("MOVE west");
      telnetInvalidMoveResponse = readLineAfterContains(reader, "ERROR INVALID_EXIT");
    }

    assertThat(telnetMoveResponse.trim())
        .matches(
            matchesCanonicalMoveRefreshWithOptionalPrompt(LookTestFixtures.DESTINATION_ROOM_ID));
    assertThat(telnetLookResponse.trim())
        .matches(matchesCanonicalLookWithOptionalPrompt(LookTestFixtures.DESTINATION_ROOM_ID));
    assertThat(telnetInvalidMoveResponse).contains("ERROR INVALID_EXIT");
  }

  @Test
  void telnetReconnectAfterRevocationFailsClosed() throws Exception {
    ensureTestServicesStarted();
    try (Socket firstSocket = new Socket("localhost", telnetServer.getPort());
        PrintWriter firstWriter =
            new PrintWriter(
                new OutputStreamWriter(firstSocket.getOutputStream(), StandardCharsets.ISO_8859_1),
                true);
        BufferedReader firstReader =
            new BufferedReader(
                new InputStreamReader(firstSocket.getInputStream(), StandardCharsets.ISO_8859_1))) {
      firstSocket.setSoTimeout((int) COMMAND_WAIT.toMillis());
      firstWriter.println("LOGIN demo@example.com swordfish");
      assertThat(readBlockAfterContains(firstReader, "Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      firstWriter.println("PLAY demo");
      assertThat(readLineAfterContains(firstReader, "OK PLAY Entered world: demo"))
          .contains("OK PLAY Entered world: demo");
      firstWriter.println("MOVE north");
      assertThat(readBlockAfterContainsOrTimeout(firstReader, "OK LOOK"))
          .contains(LookTestFixtures.DESTINATION_ROOM_ID);
    }

    ACCOUNT_STUB.setGameplayAdmissionAllowed(false);

    try (Socket secondSocket = new Socket("localhost", telnetServer.getPort());
        PrintWriter secondWriter =
            new PrintWriter(
                new OutputStreamWriter(secondSocket.getOutputStream(), StandardCharsets.ISO_8859_1),
                true);
        BufferedReader secondReader =
            new BufferedReader(
                new InputStreamReader(
                    secondSocket.getInputStream(), StandardCharsets.ISO_8859_1))) {
      secondSocket.setSoTimeout((int) COMMAND_WAIT.toMillis());
      secondWriter.println("LOGIN demo@example.com swordfish");
      assertThat(readBlockAfterContains(secondReader, "Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      secondWriter.println("PLAY demo");
      assertThat(readLineAfterContains(secondReader, "ERROR WORLD_ACCESS_DENIED"))
          .contains("ERROR WORLD_ACCESS_DENIED");
    }
  }

  @Test
  void telnetReconnectAfterMoveKeepsDestinationRoomContext() throws Exception {
    ensureTestServicesStarted();
    String telnetMoveResponse;
    String telnetReplayResponse;
    String telnetReconnectLookResponse;

    try (Socket socket = new Socket("localhost", telnetServer.getPort());
        PrintWriter writer =
            new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1),
                true);
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))) {
      socket.setSoTimeout((int) COMMAND_WAIT.toMillis());
      writer.println("LOGIN demo@example.com swordfish");
      assertThat(readBlockAfterContains(reader, "Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      writer.println("PLAY demo");
      assertThat(readLineAfterContains(reader, "OK PLAY Entered world: demo"))
          .contains("OK PLAY Entered world: demo");
      writer.println("MOVE north");
      telnetMoveResponse = readBlockAfterContainsOrTimeout(reader, "OK LOOK");
    }

    try (Socket socket = new Socket("localhost", telnetServer.getPort());
        PrintWriter writer =
            new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1),
                true);
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))) {
      socket.setSoTimeout((int) COMMAND_WAIT.toMillis());
      writer.println("LOGIN demo@example.com swordfish");
      assertThat(readBlockAfterContains(reader, "Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      writer.println("PLAY demo");
      assertThat(readLineAfterContains(reader, "OK PLAY Entered world: demo"))
          .contains("OK PLAY Entered world: demo");
      telnetReplayResponse = readBlockAfterContainsOrTimeout(reader, "OK LOOK");
      writer.println("LOOK");
      telnetReconnectLookResponse = readBlockAfterContainsOrTimeout(reader, "OK LOOK");
    }

    assertThat(telnetMoveResponse.trim())
        .matches(
            matchesCanonicalMoveRefreshWithOptionalPrompt(LookTestFixtures.DESTINATION_ROOM_ID));
    assertThat(telnetReplayResponse.trim())
        .matches(
            matchesCanonicalMoveRefreshWithOptionalPrompt(LookTestFixtures.DESTINATION_ROOM_ID));
    assertThat(telnetReconnectLookResponse.trim())
        .matches(matchesCanonicalLookWithOptionalPrompt(LookTestFixtures.DESTINATION_ROOM_ID));
  }

  @Test
  void telnetReconnectAfterStaleSessionFreshEntersGameplay() throws Exception {
    ensureTestServicesStarted();
    String telnetMoveResponse;
    String telnetReplayResponse;
    String telnetReconnectLookResponse;

    try (Socket socket = new Socket("localhost", telnetServer.getPort());
        PrintWriter writer =
            new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1),
                true);
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))) {
      socket.setSoTimeout((int) COMMAND_WAIT.toMillis());
      writer.println("LOGIN demo@example.com swordfish");
      assertThat(readBlockAfterContains(reader, "Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      writer.println("PLAY demo");
      assertThat(readLineAfterContains(reader, "OK PLAY Entered world: demo"))
          .contains("OK PLAY Entered world: demo");
      writer.println("MOVE north");
      telnetMoveResponse = readBlockAfterContainsOrTimeout(reader, "OK LOOK");
    }

    SessionContextService sessionContextService = GAME_SESSION.bean(SessionContextService.class);
    long activeGameplaySessionId =
        sessionContextService
            .findByGameplayIdentity(TENANT_ID, DEMO_WORLD_INSTANCE_ID, ACCOUNT_ID)
            .orElseThrow(() -> new IllegalStateException("Expected active gameplay binding"))
            .sessionId();
    sessionContextService.deleteBySessionId(TENANT_ID, activeGameplaySessionId);

    try (Socket socket = new Socket("localhost", telnetServer.getPort());
        PrintWriter writer =
            new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1),
                true);
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))) {
      socket.setSoTimeout((int) COMMAND_WAIT.toMillis());
      writer.println("LOGIN demo@example.com swordfish");
      assertThat(readBlockAfterContains(reader, "Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      writer.println("PLAY demo");
      assertThat(readLineAfterContains(reader, "OK PLAY Entered world: demo"))
          .contains("OK PLAY Entered world: demo");
      telnetReplayResponse = readBlockAfterContainsOrTimeout(reader, "OK LOOK");
      writer.println("LOOK");
      telnetReconnectLookResponse = readBlockAfterContainsOrTimeout(reader, "OK LOOK");
    }

    assertThat(telnetMoveResponse.trim())
        .matches(
            matchesCanonicalMoveRefreshWithOptionalPrompt(LookTestFixtures.DESTINATION_ROOM_ID));
    assertThat(telnetReplayResponse.trim())
        .matches(
            matchesCanonicalMoveRefreshWithOptionalPrompt(LookTestFixtures.DESTINATION_ROOM_ID));
    assertThat(telnetReconnectLookResponse.trim())
        .matches(matchesCanonicalLookWithOptionalPrompt());
  }

  @Test
  void telnetSecondConnectionTakesOverGameplayBinding() throws Exception {
    ensureTestServicesStarted();

    try (Socket firstSocket = new Socket("localhost", telnetServer.getPort());
        PrintWriter firstWriter =
            new PrintWriter(
                new OutputStreamWriter(firstSocket.getOutputStream(), StandardCharsets.ISO_8859_1),
                true);
        BufferedReader firstReader =
            new BufferedReader(
                new InputStreamReader(firstSocket.getInputStream(), StandardCharsets.ISO_8859_1));
        Socket secondSocket = new Socket("localhost", telnetServer.getPort());
        PrintWriter secondWriter =
            new PrintWriter(
                new OutputStreamWriter(secondSocket.getOutputStream(), StandardCharsets.ISO_8859_1),
                true);
        BufferedReader secondReader =
            new BufferedReader(
                new InputStreamReader(
                    secondSocket.getInputStream(), StandardCharsets.ISO_8859_1))) {
      firstSocket.setSoTimeout((int) COMMAND_WAIT.toMillis());
      secondSocket.setSoTimeout((int) COMMAND_WAIT.toMillis());

      firstWriter.println("LOGIN demo@example.com swordfish");
      assertThat(readBlockAfterContains(firstReader, "Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      firstWriter.println("PLAY demo");
      assertThat(readLineAfterContains(firstReader, "OK PLAY Entered world: demo"))
          .contains("OK PLAY Entered world: demo");
      firstWriter.println("LOOK");
      assertThat(readBlockAfterContainsOrTimeout(firstReader, "OK LOOK").trim())
          .matches(matchesCanonicalLookWithOptionalPrompt());

      secondWriter.println("LOGIN demo@example.com swordfish");
      assertThat(readBlockAfterContains(secondReader, "Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      secondWriter.println("PLAY demo");
      assertThat(readLineAfterContains(secondReader, "OK PLAY Entered world: demo"))
          .contains("OK PLAY Entered world: demo");
      secondWriter.println("LOOK");
      assertThat(readBlockAfterContainsOrTimeout(secondReader, "OK LOOK").trim())
          .matches(matchesCanonicalLookWithOptionalPrompt());

      firstWriter.println("LOOK");
      assertThat(readLineAfterContains(firstReader, "ERROR LOGIN_REQUIRED"))
          .contains("ERROR LOGIN_REQUIRED");
    }
  }

  @Test
  void telnetCommunicationMatchesCanonicalTranscriptsWithoutSession() throws Exception {
    ensureTestServicesStarted();
    ENTITY_STUB.setRoomEntities(ChatTestFixtures.sampleEntities());
    seedLiveTargetSession();

    String telnetWhisperResponse;
    String telnetTellResponse;
    try (Socket socket = new Socket("localhost", telnetServer.getPort());
        PrintWriter writer =
            new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1),
                true);
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))) {
      socket.setSoTimeout((int) COMMAND_WAIT.toMillis());
      writer.println("LOGIN demo@example.com swordfish");
      assertThat(readBlockAfterContains(reader, "Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      writer.println("PLAY demo");
      assertThat(readLineAfterContains(reader, "OK PLAY Entered world: demo"))
          .contains("OK PLAY Entered world: demo");

      writer.println("WHISPER Sora Keep quiet");
      telnetWhisperResponse = readLineAfterContains(reader, "You whisper to Sora, \"Keep quiet\"");
      assertThat(SOCIAL_STUB.lastRequest())
          .hasValueSatisfying(
              request -> {
                assertThat(request.getType()).isEqualTo(ChatType.CHAT_TYPE_WHISPER);
                assertThat(request.getRecipientId()).isEqualTo(ChatTestFixtures.PLAYER_SORA);
              });

      writer.println("TELL Sora Meet me at the forge");
      telnetTellResponse = readLineAfterContains(reader, "You tell Sora, \"Meet me at the forge\"");
    }

    assertThat(telnetWhisperResponse).contains(ChatTestFixtures.canonicalWhisperText());
    assertThat(telnetTellResponse).contains(ChatTestFixtures.canonicalTellText());
    assertThat(SOCIAL_STUB.lastRequest())
        .hasValueSatisfying(
            request -> {
              assertThat(request.getType()).isEqualTo(ChatType.CHAT_TYPE_TELL);
              assertThat(request.getRecipientId()).isEqualTo(ChatTestFixtures.PLAYER_SORA);
            });
  }

  @Test
  void telnetWhisperDeliversTargetAndObserverViews() throws Exception {
    ensureTestServicesStarted();
    ENTITY_STUB.setRoomEntities(ChatTestFixtures.sampleEntities());

    try (Socket actorSocket = new Socket("localhost", telnetServer.getPort());
        PrintWriter actorWriter =
            new PrintWriter(
                new OutputStreamWriter(actorSocket.getOutputStream(), StandardCharsets.ISO_8859_1),
                true);
        BufferedReader actorReader =
            new BufferedReader(
                new InputStreamReader(actorSocket.getInputStream(), StandardCharsets.ISO_8859_1));
        Socket targetSocket = new Socket("localhost", telnetServer.getPort());
        PrintWriter targetWriter =
            new PrintWriter(
                new OutputStreamWriter(targetSocket.getOutputStream(), StandardCharsets.ISO_8859_1),
                true);
        BufferedReader targetReader =
            new BufferedReader(
                new InputStreamReader(targetSocket.getInputStream(), StandardCharsets.ISO_8859_1));
        Socket observerSocket = new Socket("localhost", telnetServer.getPort());
        PrintWriter observerWriter =
            new PrintWriter(
                new OutputStreamWriter(
                    observerSocket.getOutputStream(), StandardCharsets.ISO_8859_1),
                true);
        BufferedReader observerReader =
            new BufferedReader(
                new InputStreamReader(
                    observerSocket.getInputStream(), StandardCharsets.ISO_8859_1))) {
      actorSocket.setSoTimeout((int) COMMAND_WAIT.toMillis());
      targetSocket.setSoTimeout((int) COMMAND_WAIT.toMillis());
      observerSocket.setSoTimeout((int) COMMAND_WAIT.toMillis());

      actorWriter.println("LOGIN demo@example.com swordfish");
      assertThat(readBlockAfterContains(actorReader, "Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      actorWriter.println("PLAY demo");
      assertThat(readLineAfterContains(actorReader, "OK PLAY Entered world: demo"))
          .contains("OK PLAY Entered world: demo");

      targetWriter.println("LOGIN demo@example.com swordfish");
      assertThat(readBlockAfterContains(targetReader, "Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      targetWriter.println("PLAY demo Sora");
      assertThat(readLineAfterContains(targetReader, "OK PLAY Entered world: demo"))
          .contains("OK PLAY Entered world: demo");

      observerWriter.println("LOGIN demo@example.com swordfish");
      assertThat(readBlockAfterContains(observerReader, "Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      observerWriter.println("PLAY demo Nyx");
      assertThat(readLineAfterContains(observerReader, "OK PLAY Entered world: demo"))
          .contains("OK PLAY Entered world: demo");

      SessionContextService sessionContextService = GAME_SESSION.bean(SessionContextService.class);
      ActiveTransportSessionRegistry sessionRegistry =
          GAME_SESSION.bean(ActiveTransportSessionRegistry.class);
      ScreenBufferService screenBufferService = GAME_SESSION.bean(ScreenBufferService.class);
      SessionContext targetContext =
          sessionContextService
              .findByGameplayName(TENANT_ID, DEMO_WORLD_INSTANCE_ID, "Sora")
              .orElseThrow();
      SessionContext observerContext =
          sessionContextService
              .findByGameplayName(TENANT_ID, DEMO_WORLD_INSTANCE_ID, "Nyx")
              .orElseThrow();
      assertThat(sessionRegistry.find(targetContext.sessionId())).isPresent();
      assertThat(sessionRegistry.find(observerContext.sessionId())).isPresent();

      actorWriter.println("WHISPER Sora Keep quiet");
      assertBufferedScreenEventuallyContains(
          screenBufferService, targetContext, ChatTestFixtures.canonicalWhisperTargetText());
      assertBufferedScreenEventuallyContains(
          screenBufferService,
          observerContext,
          ChatTestFixtures.canonicalWhisperObserverMetadataText());
      assertThat(readLineAfterContains(actorReader, "You whisper to Sora, \"Keep quiet\""))
          .contains(ChatTestFixtures.canonicalWhisperText());
      assertThat(readLineAfterContains(targetReader, "Emberline whispers to you, \"Keep quiet\""))
          .contains(ChatTestFixtures.canonicalWhisperTargetText());
      assertThat(readLineAfterContains(observerReader, "Emberline whispers something to Sora."))
          .contains(ChatTestFixtures.canonicalWhisperObserverMetadataText());
    }
  }

  @Test
  void telnetTellDeliversTargetView() throws Exception {
    ensureTestServicesStarted();
    ENTITY_STUB.setRoomEntities(ChatTestFixtures.sampleEntities());

    try (Socket actorSocket = new Socket("localhost", telnetServer.getPort());
        PrintWriter actorWriter =
            new PrintWriter(
                new OutputStreamWriter(actorSocket.getOutputStream(), StandardCharsets.ISO_8859_1),
                true);
        BufferedReader actorReader =
            new BufferedReader(
                new InputStreamReader(actorSocket.getInputStream(), StandardCharsets.ISO_8859_1));
        Socket targetSocket = new Socket("localhost", telnetServer.getPort());
        PrintWriter targetWriter =
            new PrintWriter(
                new OutputStreamWriter(targetSocket.getOutputStream(), StandardCharsets.ISO_8859_1),
                true);
        BufferedReader targetReader =
            new BufferedReader(
                new InputStreamReader(
                    targetSocket.getInputStream(), StandardCharsets.ISO_8859_1))) {
      actorSocket.setSoTimeout((int) COMMAND_WAIT.toMillis());
      targetSocket.setSoTimeout((int) COMMAND_WAIT.toMillis());

      actorWriter.println("LOGIN demo@example.com swordfish");
      assertThat(readBlockAfterContains(actorReader, "Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      actorWriter.println("PLAY demo Emberline");
      assertThat(readLineAfterContains(actorReader, "OK PLAY Entered world: demo"))
          .contains("OK PLAY Entered world: demo");

      targetWriter.println("LOGIN demo@example.com swordfish");
      assertThat(readBlockAfterContains(targetReader, "Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      targetWriter.println("PLAY demo Sora");
      assertThat(readLineAfterContains(targetReader, "OK PLAY Entered world: demo"))
          .contains("OK PLAY Entered world: demo");

      SessionContextService sessionContextService = GAME_SESSION.bean(SessionContextService.class);
      ActiveTransportSessionRegistry sessionRegistry =
          GAME_SESSION.bean(ActiveTransportSessionRegistry.class);
      ScreenBufferService screenBufferService = GAME_SESSION.bean(ScreenBufferService.class);
      SessionContext targetContext =
          sessionContextService
              .findByGameplayName(TENANT_ID, DEMO_WORLD_INSTANCE_ID, "Sora")
              .orElseThrow();
      assertThat(sessionRegistry.find(targetContext.sessionId())).isPresent();

      actorWriter.println("TELL Sora Meet me at the forge");
      assertBufferedScreenEventuallyContains(
          screenBufferService, targetContext, ChatTestFixtures.canonicalTellTargetText());
      assertThat(readLineAfterContains(actorReader, "You tell Sora, \"Meet me at the forge\""))
          .contains(ChatTestFixtures.canonicalTellText());
      assertThat(
              readLineAfterContains(targetReader, "Emberline tells you, \"Meet me at the forge\""))
          .contains(ChatTestFixtures.canonicalTellTargetText());
    }
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
    if (SOCIAL_STUB == null) {
      try {
        SOCIAL_STUB = new SocialGroupsStubServer(0);
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
    return new AccountServiceStub(0);
  }

  private static WorldManagementStubServer startWorldStub() throws IOException {
    return new WorldManagementStubServer(0);
  }

  private static EntityManagementStubServer startEntityStub() throws IOException {
    return new EntityManagementStubServer(0);
  }

  private static GameLogicHolder startGameLogic(int worldPort, int entityPort, int socialPort) {
    Map<String, Object> props = new java.util.LinkedHashMap<>();
    props.put("spring.profiles.active", "test");
    props.put("spring.application.name", "game-logic-service");
    props.put("server.port", "0");
    props.put("spring.grpc.server.port", "0");
    props.put("firemud.grpc.plaintext", "true");
    props.put("otel.endpoint", "disabled");
    props.put("firemud.database.enabled", "false");
    props.put(
        "spring.autoconfigure.exclude",
        "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration");
    props.put("firemud.services.worldManagementService", "localhost:" + worldPort);
    props.put("firemud.services.entityManagementService", "localhost:" + entityPort);
    props.put("firemud.services.socialGroupsService", "localhost:" + socialPort);
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(
                GameLogicServiceApplication.class, NestedReadinessOverrides.class)
            .run(toCommandLineArgs(props));
    int boundGrpcPort = context.getBean(GrpcServerLifecycle.class).getPort();
    return new GameLogicHolder(context, boundGrpcPort);
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
    props.put("firemud.services.entityManagementService", "localhost:" + ENTITY_STUB.port());
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

  private void seedLiveTargetSession() {
    GAME_SESSION
        .bean(SessionContextService.class)
        .save(
            new SessionContext(
                90210L,
                TENANT_ID,
                Long.parseLong(ChatTestFixtures.PLAYER_SORA),
                "sora@example.com",
                Long.parseLong(ChatTestFixtures.PLAYER_SORA),
                "Sora",
                DEMO_WORLD_INSTANCE_ID,
                LookTestFixtures.ROOM_ID,
                "target-jwt"));
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

  private static String readLineAfterContains(BufferedReader reader, String expectedSubstring)
      throws IOException {
    String line;
    while ((line = reader.readLine()) != null) {
      if (line.contains(expectedSubstring)) {
        return line;
      }
    }
    return "";
  }

  private static String readBlockAfterContainsOrTimeout(
      BufferedReader reader, String expectedSubstring) throws IOException {
    StringBuilder builder = new StringBuilder();
    boolean matched = false;
    while (true) {
      String line;
      try {
        line = reader.readLine();
      } catch (java.net.SocketTimeoutException ex) {
        return builder.toString();
      }
      if (line == null) {
        return builder.toString();
      }
      builder.append(line).append("\n");
      if (!matched && line.contains(expectedSubstring)) {
        matched = true;
      } else if (matched && line.isEmpty()) {
        return builder.toString();
      }
    }
  }

  private static void assertBufferedScreenEventuallyContains(
      ScreenBufferService screenBufferService, SessionContext context, String expectedSubstring)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + COMMAND_WAIT.toMillis();
    while (System.currentTimeMillis() < deadline) {
      Optional<ScreenBufferService.BufferedScreen> maybeBuffer =
          screenBufferService.get(
              context.tenantId(), context.gameInstanceId(), context.characterId());
      if (maybeBuffer.isPresent()
          && maybeBuffer.orElseThrow().protocolText().contains(expectedSubstring)) {
        return;
      }
      Thread.sleep(50L);
    }
    Optional<ScreenBufferService.BufferedScreen> maybeBuffer =
        screenBufferService.get(
            context.tenantId(), context.gameInstanceId(), context.characterId());
    String actual = maybeBuffer.map(ScreenBufferService.BufferedScreen::protocolText).orElse("");
    throw new AssertionError(
        "Expected buffered screen to contain '" + expectedSubstring + "', got '" + actual + "'");
  }

  private static Predicate<String> matchesCanonicalLookWithOptionalPrompt() {
    return matchesCanonicalLookWithOptionalPrompt(LookTestFixtures.ROOM_ID);
  }

  private static Predicate<String> matchesCanonicalLookWithOptionalPrompt(String roomId) {
    String canonical = LookTestFixtures.canonicalLookText(roomId).trim();
    String leadingPrompt = "demo> \n" + canonical;
    String trailingPrompt = canonical + "\n\ndemo>";
    String wrappedPrompt = "demo> \n" + trailingPrompt;
    return response ->
        response.equals(canonical)
            || response.equals(leadingPrompt)
            || response.equals(trailingPrompt)
            || response.equals(wrappedPrompt);
  }

  private static Predicate<String> matchesCanonicalMoveRefreshWithOptionalPrompt(String roomId) {
    String canonical =
        LookTestFixtures.canonicalLookText(roomId).replaceFirst("\\nLong: .*\\n", "\n").trim();
    String leadingPrompt = "demo> \n" + canonical;
    String trailingPrompt = canonical + "\n\ndemo>";
    String wrappedPrompt = "demo> \n" + trailingPrompt;
    return response ->
        response.equals(canonical)
            || response.equals(leadingPrompt)
            || response.equals(trailingPrompt)
            || response.equals(wrappedPrompt);
  }

  private static final class AccountServiceStub extends AccountServiceGrpc.AccountServiceImplBase {
    private final Server server;
    private final List<AuthenticateRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicBoolean gameplayAdmissionAllowed = new AtomicBoolean(true);
    private final AtomicBoolean gameplayAvailable = new AtomicBoolean(true);
    private final int port;

    AccountServiceStub(int port) throws IOException {
      this.server = ServerBuilder.forPort(port).addService(this).build().start();
      this.port = server.getPort();
    }

    List<AuthenticateRequest> capturedRequests() {
      return List.copyOf(requests);
    }

    int port() {
      return port;
    }

    void setGameplayAdmissionAllowed(boolean allowed) {
      gameplayAdmissionAllowed.set(allowed);
    }

    void setGameplayAvailable(boolean available) {
      gameplayAvailable.set(available);
    }

    @Override
    public void authenticate(
        AuthenticateRequest request, StreamObserver<AuthenticateResponse> responseObserver) {
      requests.add(request);
      long accountId =
          switch (request.getUsername()) {
            case "sora@example.com" -> SORA_ACCOUNT_ID;
            default -> ACCOUNT_ID;
          };
      AuthenticateResponse response =
          AuthenticateResponse.newBuilder()
              .setAccountId(String.valueOf(accountId))
              .setAuthToken("stub-token")
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }

    @Override
    public void getTenantMembershipForRuntime(
        GetTenantMembershipForRuntimeRequest request,
        StreamObserver<GetTenantMembershipForRuntimeResponse> responseObserver) {
      responseObserver.onNext(
          GetTenantMembershipForRuntimeResponse.newBuilder()
              .setAccountId(request.getAccountId())
              .setTenantId(request.getTenantId())
              .setGameplayAdmissionAllowed(gameplayAdmissionAllowed.get())
              .setMembershipVersion(1L)
              .setEvaluatedAt("2026-03-30T00:00:00Z")
              .build());
      responseObserver.onCompleted();
    }

    @Override
    public void getTenantEntitlementsForRuntime(
        GetTenantEntitlementsForRuntimeRequest request,
        StreamObserver<GetTenantEntitlementsForRuntimeResponse> responseObserver) {
      responseObserver.onNext(
          GetTenantEntitlementsForRuntimeResponse.newBuilder()
              .setTenantId(request.getTenantId())
              .setGameplayAvailable(gameplayAvailable.get())
              .setEntitlementVersion(1L)
              .setTenantBillingSequence(1L)
              .setEvaluatedAt("2026-03-30T00:00:00Z")
              .build());
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

    <T> T bean(Class<T> type) {
      return context.getBean(type);
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
