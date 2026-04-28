package net.firedevops.firemud.tcpproxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import javax.sql.DataSource;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.common.conflict.ConflictTracker;
import net.firedevops.firemud.gamelogic.GameLogicServiceApplication;
import net.firedevops.firemud.gamesession.GameSessionServiceApplication;
import net.firedevops.firemud.gamesession.client.ModerationPolicyClient;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.service.ActiveTransportSessionRegistry;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.test.ChatTestFixtures;
import net.firedevops.firemud.gamesession.test.GameInstanceTestFixtures;
import net.firedevops.firemud.gamesession.test.LookTestFixtures;
import net.firedevops.firemud.gamesession.test.stubs.EntityManagementStubServer;
import net.firedevops.firemud.gamesession.test.stubs.SocialGroupsStubServer;
import net.firedevops.firemud.gamesession.test.stubs.WorldManagementStubServer;
import net.firedevops.firemud.socialgroups.v1.ChatType;
import net.firedevops.firemud.springcloudgateway.service.GatewayRoute;
import net.firedevops.firemud.springcloudgateway.service.GatewayRouteService;
import net.firedevops.firemud.tcpproxy.stub.GatewayStubApplication;
import net.firedevops.firemud.tcpproxy.telnet.TelnetServer;
import net.firedevops.firemud.tcpproxy.testsupport.GameplayTelnetDriver;
import net.firedevops.firemud.test.AccountRuntimeStubServer;
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
import reactor.core.publisher.Mono;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = TcpProxyServiceApplication.class)
class TelnetGatewayGameSessionAccountCrossServiceIntegrationTest {

  // This suite boots multiple real service contexts and backing stores, so allow slower
  // round-trips than the lighter isolated telnet seam tests.
  private static final Duration COMMAND_WAIT = Duration.ofSeconds(15);
  private static final String CROSS_SERVICE_TEST_JWT_SECRET =
      "stub-secret-key-for-tests-1234567890";
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

  private static AccountRuntimeStubServer ACCOUNT_STUB;
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

    AccountRuntimeStubServer accountStub = ACCOUNT_STUB;
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
    try (GameplayTelnetDriver client = openTelnetClient()) {
      client.awaitInitialGuidance();
      client.sendLine("LOGIN demo@example.com swordfish");
      telnetLoginResponse = client.readBlockContaining("Logged in as demo@example.com");
      assertThat(telnetLoginResponse).contains("Logged in as demo@example.com");
      client.play("demo");
      client.sendLine("LOOK");
      telnetLookResponse = client.readBlockContaining("OK LOOK");
    }

    assertThat(telnetLookResponse.trim()).matches(matchesCanonicalLookWithOptionalPrompt());

    assertThat(ACCOUNT_STUB.capturedAuthenticateRequests())
        .anyMatch(
            request ->
                request.getUsername().equals("demo@example.com")
                    && request.getPassword().equals("swordfish")
                    && request.getTenantId().equals(String.valueOf(TENANT_ID)));
  }

  @Test
  void telnetReconnectAfterRevocationFailsPlayAdmission() throws Exception {
    ensureTestServicesStarted();
    try (GameplayTelnetDriver firstClient = openTelnetClient()) {
      firstClient.awaitInitialGuidance();
      firstClient.sendLine("LOGIN demo@example.com swordfish");
      assertThat(firstClient.readBlockContaining("Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      firstClient.play("demo");
    }

    ACCOUNT_STUB.setGameplayAdmissionAllowed(false);

    try (GameplayTelnetDriver secondClient = openTelnetClient()) {
      secondClient.awaitInitialGuidance();
      secondClient.sendLine("LOGIN demo@example.com swordfish");
      assertThat(secondClient.readBlockContaining("Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");

      secondClient.sendLine("PLAY demo");
      assertThat(secondClient.readLineContaining("ERROR WORLD_ACCESS_DENIED"))
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
    try (GameplayTelnetDriver client = openTelnetClient()) {
      client.awaitInitialGuidance();
      client.sendLine("LOGIN demo@example.com swordfish");
      assertThat(client.readBlockContaining("Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      client.play("demo");

      client.sendLine("MOVE north");
      telnetMoveResponse = client.readBlockContainingOrTimeout("OK LOOK");

      client.sendLine("LOOK");
      telnetLookResponse = client.readBlockContainingOrTimeout("OK LOOK");

      client.sendLine("MOVE west");
      telnetInvalidMoveResponse = client.readLineContaining("ERROR INVALID_EXIT");
    }

    assertThat(telnetMoveResponse.trim())
        .matches(
            matchesCanonicalMoveRefreshWithOptionalPrompt(LookTestFixtures.DESTINATION_ROOM_ID));
    assertThat(telnetLookResponse)
        .contains("Room: Crafting Hall of Ember")
        .contains("A soot-dark hall ringed with anvils and cooling braziers.");
    assertThat(telnetInvalidMoveResponse).contains("ERROR INVALID_EXIT");
  }

  @Test
  void telnetItemAndEquipmentLoopMatchesWebSocketCommandSurface() throws Exception {
    ensureTestServicesStarted();
    ENTITY_STUB.resetRoomEntities();
    ENTITY_STUB.resetItemState();

    String roomInventoryBeforePickup;
    String pickupResponse;
    String containerViewResponse;
    String putResponse;
    String takeResponse;
    String dropResponse;
    String roomInventoryAfterDrop;
    String emptyEquipmentResponse;
    String wearResponse;
    String equipmentResponse;
    String removeResponse;
    String incompatibleResponse;
    try (GameplayTelnetDriver client = openTelnetClient()) {
      client.awaitInitialGuidance();
      client.sendLine("LOGIN demo@example.com swordfish");
      assertThat(client.readBlockContaining("Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      client.play("demo");
      client.sendLine("LOOK");
      assertThat(client.readBlockContainingOrTimeout("OK LOOK")).contains("Candle-lit Antechamber");

      client.sendLine("INV HERE");
      roomInventoryBeforePickup = client.readBlockContaining("Room Inventory:");
      client.sendLine("GET Torch");
      pickupResponse = client.readBlockContaining("You pick up Torch.");
      client.sendLine("CONTAINER Backpack");
      containerViewResponse = client.readBlockContaining("Container: Backpack [backpack#1]");
      client.sendLine("PUT Torch INTO Backpack");
      putResponse = client.readBlockContaining("You put Torch into Backpack.");
      client.sendLine("TAKE Torch FROM Backpack");
      takeResponse = client.readBlockContaining("You take Torch from Backpack.");
      client.sendLine("DROP Torch");
      dropResponse = client.readBlockContaining("You drop Torch.");
      client.sendLine("INV HERE");
      roomInventoryAfterDrop = client.readBlockContaining("Room Inventory:");

      client.sendLine("EQUIPMENT");
      emptyEquipmentResponse = client.readBlockContaining("You have nothing equipped.");
      client.sendLine("WEAR Leather Cap");
      wearResponse = client.readLineContaining("You wear Leather Cap.");
      client.sendLine("EQUIPMENT");
      equipmentResponse = client.readBlockContaining("Equipment:");
      client.sendLine("REMOVE HEAD");
      removeResponse = client.readLineContaining("You remove Leather Cap.");
      client.sendLine("WEAR Iron Boots");
      incompatibleResponse = client.readLineContaining("ERROR SLOT_INCOMPATIBLE");
    }

    assertThat(roomInventoryBeforePickup)
        .contains("- Torch [torch#1] (A small torch)")
        .contains("- Backpack [backpack#1] (A weathered backpack)");
    assertThat(pickupResponse)
        .contains("You pick up Torch.")
        .contains("Inventory:")
        .contains("- Torch [torch#1] (A small torch)");
    assertThat(containerViewResponse)
        .contains("Container: Backpack [backpack#1]")
        .contains("- Ration [ration#1] (A dry trail ration)");
    assertThat(putResponse)
        .contains("You put Torch into Backpack.")
        .contains("- Torch [torch#1] (A small torch)");
    assertThat(takeResponse)
        .contains("You take Torch from Backpack.")
        .contains("- Ration [ration#1] (A dry trail ration)");
    assertThat(dropResponse).contains("You drop Torch.");
    assertThat(roomInventoryAfterDrop).contains("- Torch [torch#1] (A small torch)");
    assertThat(emptyEquipmentResponse).contains("You have nothing equipped.");
    assertThat(wearResponse).contains("You wear Leather Cap.");
    assertThat(equipmentResponse).contains("- HEAD: Leather Cap [cap#1] (A small cap)");
    assertThat(removeResponse).contains("You remove Leather Cap.");
    assertThat(incompatibleResponse)
        .contains("ERROR SLOT_INCOMPATIBLE")
        .contains("Iron Boots cannot be worn by this body layout.");
    assertThat(ENTITY_STUB.lastPickupRequest())
        .hasValueSatisfying(
            request -> {
              assertThat(request.getTenantId()).isEqualTo(String.valueOf(TENANT_ID));
              assertThat(request.getCharacterId()).isEqualTo(ChatTestFixtures.PLAYER_EMBERLINE);
              assertThat(request.getRoomInstanceId()).isEqualTo(LookTestFixtures.ROOM_ID);
              assertThat(request.getItemId()).isEqualTo("torch");
              assertThat(request.getEffectId()).isNotBlank();
            });
    assertThat(ENTITY_STUB.lastDropRequest())
        .hasValueSatisfying(
            request -> {
              assertThat(request.getTenantId()).isEqualTo(String.valueOf(TENANT_ID));
              assertThat(request.getCharacterId()).isEqualTo(ChatTestFixtures.PLAYER_EMBERLINE);
              assertThat(request.getRoomInstanceId()).isEqualTo(LookTestFixtures.ROOM_ID);
              assertThat(request.getItemId()).isEqualTo("torch");
              assertThat(request.getEffectId()).isNotBlank();
            });
    assertThat(ENTITY_STUB.lastPutRequest())
        .hasValueSatisfying(
            request -> {
              assertThat(request.getTenantId()).isEqualTo(String.valueOf(TENANT_ID));
              assertThat(request.getCharacterId()).isEqualTo(ChatTestFixtures.PLAYER_EMBERLINE);
              assertThat(request.getContainerInstanceId()).isEqualTo("container-backpack-1");
              assertThat(request.getItemId()).isEqualTo("torch");
              assertThat(request.getItemInstanceId()).isEqualTo("torch-ground-1");
              assertThat(request.getEffectId()).isNotBlank();
            });
    assertThat(ENTITY_STUB.lastTakeRequest())
        .hasValueSatisfying(
            request -> {
              assertThat(request.getTenantId()).isEqualTo(String.valueOf(TENANT_ID));
              assertThat(request.getCharacterId()).isEqualTo(ChatTestFixtures.PLAYER_EMBERLINE);
              assertThat(request.getContainerInstanceId()).isEqualTo("container-backpack-1");
              assertThat(request.getItemId()).isEqualTo("torch");
              assertThat(request.getItemInstanceId()).isBlank();
              assertThat(request.getEffectId()).isNotBlank();
            });
    assertThat(ENTITY_STUB.lastRemoveRequest())
        .hasValueSatisfying(
            request -> {
              assertThat(request.getTenantId()).isEqualTo(String.valueOf(TENANT_ID));
              assertThat(request.getCharacterId()).isEqualTo(ChatTestFixtures.PLAYER_EMBERLINE);
              assertThat(request.getSlot()).isEqualTo("HEAD");
              assertThat(request.getEffectId()).isNotBlank();
            });
    assertThat(ENTITY_STUB.lastWearRequest())
        .hasValueSatisfying(
            request -> {
              assertThat(request.getTenantId()).isEqualTo(String.valueOf(TENANT_ID));
              assertThat(request.getCharacterId()).isEqualTo(ChatTestFixtures.PLAYER_EMBERLINE);
              assertThat(request.getItemId()).isEqualTo("iron-boots");
              assertThat(request.getEffectId()).isNotBlank();
            });
  }

  @Test
  void telnetReconnectAfterRevocationFailsClosed() throws Exception {
    ensureTestServicesStarted();
    try (GameplayTelnetDriver firstClient = openTelnetClient()) {
      firstClient.awaitInitialGuidance();
      firstClient.sendLine("LOGIN demo@example.com swordfish");
      assertThat(firstClient.readBlockContaining("Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      firstClient.play("demo");
      firstClient.sendLine("MOVE north");
      assertThat(firstClient.readBlockContainingOrTimeout("OK LOOK"))
          .contains(LookTestFixtures.DESTINATION_ROOM_ID);
    }

    ACCOUNT_STUB.setGameplayAdmissionAllowed(false);

    try (GameplayTelnetDriver secondClient = openTelnetClient()) {
      secondClient.awaitInitialGuidance();
      secondClient.sendLine("LOGIN demo@example.com swordfish");
      assertThat(secondClient.readBlockContaining("Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      secondClient.sendLine("PLAY demo");
      assertThat(secondClient.readLineContaining("ERROR WORLD_ACCESS_DENIED"))
          .contains("ERROR WORLD_ACCESS_DENIED");
    }
  }

  @Test
  void telnetReconnectAfterMoveKeepsDestinationRoomContext() throws Exception {
    ensureTestServicesStarted();
    String telnetMoveResponse;
    String telnetReplayResponse;
    String telnetReconnectLookResponse;

    try (GameplayTelnetDriver client = openTelnetClient()) {
      client.awaitInitialGuidance();
      client.sendLine("LOGIN demo@example.com swordfish");
      assertThat(client.readBlockContaining("Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      client.play("demo");
      client.sendLine("MOVE north");
      telnetMoveResponse = client.readBlockContainingOrTimeout("OK LOOK");
    }

    try (GameplayTelnetDriver client = openTelnetClient()) {
      client.awaitInitialGuidance();
      client.sendLine("LOGIN demo@example.com swordfish");
      assertThat(client.readBlockContaining("Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      client.play("demo");
      telnetReplayResponse = client.readBlockContainingOrTimeout("OK LOOK");
      client.sendLine("LOOK");
      telnetReconnectLookResponse =
          client.readBlockMatching(
              matchesCanonicalLookWithOptionalPrompt(LookTestFixtures.DESTINATION_ROOM_ID),
              "canonical reconnect look");
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

    try (GameplayTelnetDriver client = openTelnetClient()) {
      client.awaitInitialGuidance();
      client.sendLine("LOGIN demo@example.com swordfish");
      assertThat(client.readBlockContaining("Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      client.play("demo");
      client.sendLine("MOVE north");
      telnetMoveResponse = client.readBlockContainingOrTimeout("OK LOOK");
    }

    SessionContextService sessionContextService = GAME_SESSION.bean(SessionContextService.class);
    long activeGameplaySessionId =
        sessionContextService
            .findByGameplayIdentity(TENANT_ID, DEMO_WORLD_INSTANCE_ID, ACCOUNT_ID)
            .orElseThrow(() -> new IllegalStateException("Expected active gameplay binding"))
            .sessionId();
    sessionContextService.deleteBySessionId(TENANT_ID, activeGameplaySessionId);

    try (GameplayTelnetDriver client = openTelnetClient()) {
      client.awaitInitialGuidance();
      client.sendLine("LOGIN demo@example.com swordfish");
      assertThat(client.readBlockContaining("Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      client.play("demo");
      telnetReplayResponse = client.readBlockContainingOrTimeout("OK LOOK");
      client.sendLine("LOOK");
      telnetReconnectLookResponse =
          client.readBlockMatching(matchesCanonicalLookWithOptionalPrompt(), "canonical look");
    }

    assertThat(telnetMoveResponse.trim())
        .matches(
            matchesCanonicalMoveRefreshWithOptionalPrompt(LookTestFixtures.DESTINATION_ROOM_ID));
    assertThat(telnetReplayResponse.trim()).isEqualTo("demo>");
    assertThat(telnetReconnectLookResponse.trim())
        .matches(matchesCanonicalLookWithOptionalPrompt());
  }

  @Test
  void telnetSecondConnectionTakesOverGameplayBinding() throws Exception {
    ensureTestServicesStarted();

    try (GameplayTelnetDriver firstClient = openTelnetClient();
        GameplayTelnetDriver secondClient = openTelnetClient()) {
      firstClient.awaitInitialGuidance();
      secondClient.awaitInitialGuidance();

      firstClient.sendLine("LOGIN demo@example.com swordfish");
      assertThat(firstClient.readBlockContaining("Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      firstClient.play("demo");
      firstClient.sendLine("LOOK");
      assertThat(firstClient.readBlockContainingOrTimeout("OK LOOK").trim())
          .matches(matchesCanonicalLookWithOptionalPrompt());

      secondClient.sendLine("LOGIN demo@example.com swordfish");
      assertThat(secondClient.readBlockContaining("Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      secondClient.play("demo");
      secondClient.sendLine("LOOK");
      assertThat(secondClient.readBlockContainingOrTimeout("OK LOOK").trim())
          .matches(matchesCanonicalLookWithOptionalPrompt());

      firstClient.sendLine("LOOK");
      assertThat(firstClient.readLineContaining("ERROR LOGIN_REQUIRED"))
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
    try (GameplayTelnetDriver client = openTelnetClient()) {
      client.awaitInitialGuidance();
      client.sendLine("LOGIN demo@example.com swordfish");
      assertThat(client.readBlockContaining("Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      client.play("demo");

      client.sendLine("WHISPER Sora Keep quiet");
      telnetWhisperResponse = client.readLineContaining(ChatTestFixtures.canonicalWhisperText());
      assertThat(SOCIAL_STUB.lastRequest())
          .hasValueSatisfying(
              request -> {
                assertThat(request.getType()).isEqualTo(ChatType.CHAT_TYPE_WHISPER);
                assertThat(request.getRecipientId()).isEqualTo(ChatTestFixtures.PLAYER_SORA);
              });

      client.sendLine("TELL Sora Meet me at the forge");
      telnetTellResponse = client.readLineContaining(ChatTestFixtures.canonicalTellText());
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

    try (GameplayTelnetDriver actorClient = openTelnetClient();
        GameplayTelnetDriver targetClient = openTelnetClient();
        GameplayTelnetDriver observerClient = openTelnetClient()) {
      actorClient.awaitInitialGuidance();
      targetClient.awaitInitialGuidance();
      observerClient.awaitInitialGuidance();

      actorClient.sendLine("LOGIN demo@example.com swordfish");
      assertThat(actorClient.readBlockContaining("Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      actorClient.play("demo");

      targetClient.sendLine("LOGIN demo@example.com swordfish");
      assertThat(targetClient.readBlockContaining("Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      targetClient.play("demo", "Sora");

      observerClient.sendLine("LOGIN demo@example.com swordfish");
      assertThat(observerClient.readBlockContaining("Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      observerClient.play("demo", "Nyx");

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

      actorClient.sendLine("WHISPER Sora Keep quiet");
      assertBufferedScreenEventuallyContains(
          screenBufferService, targetContext, ChatTestFixtures.canonicalWhisperTargetText());
      assertBufferedScreenEventuallyContains(
          screenBufferService,
          observerContext,
          ChatTestFixtures.canonicalWhisperObserverMetadataText());
      assertThat(actorClient.readLineContaining(ChatTestFixtures.canonicalWhisperText()))
          .contains(ChatTestFixtures.canonicalWhisperText());
      assertThat(targetClient.readLineContaining(ChatTestFixtures.canonicalWhisperTargetText()))
          .contains(ChatTestFixtures.canonicalWhisperTargetText());
      assertThat(observerClient.readLineContaining("Emberline whispers something to Sora."))
          .contains(ChatTestFixtures.canonicalWhisperObserverMetadataText());
    }
  }

  @Test
  void telnetTellDeliversTargetView() throws Exception {
    ensureTestServicesStarted();
    ENTITY_STUB.setRoomEntities(ChatTestFixtures.sampleEntities());

    try (GameplayTelnetDriver actorClient = openTelnetClient();
        GameplayTelnetDriver targetClient = openTelnetClient()) {
      actorClient.awaitInitialGuidance();
      targetClient.awaitInitialGuidance();

      actorClient.sendLine("LOGIN demo@example.com swordfish");
      assertThat(actorClient.readBlockContaining("Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      actorClient.play("demo", "Emberline");

      targetClient.sendLine("LOGIN demo@example.com swordfish");
      assertThat(targetClient.readBlockContaining("Logged in as demo@example.com"))
          .contains("Logged in as demo@example.com");
      targetClient.play("demo", "Sora");

      SessionContextService sessionContextService = GAME_SESSION.bean(SessionContextService.class);
      ActiveTransportSessionRegistry sessionRegistry =
          GAME_SESSION.bean(ActiveTransportSessionRegistry.class);
      ScreenBufferService screenBufferService = GAME_SESSION.bean(ScreenBufferService.class);
      SessionContext targetContext =
          sessionContextService
              .findByGameplayName(TENANT_ID, DEMO_WORLD_INSTANCE_ID, "Sora")
              .orElseThrow();
      assertThat(sessionRegistry.find(targetContext.sessionId())).isPresent();

      actorClient.sendLine("TELL Sora Meet me at the forge");
      assertBufferedScreenEventuallyContains(
          screenBufferService, targetContext, ChatTestFixtures.canonicalTellTargetText());
      assertThat(actorClient.readLineContaining(ChatTestFixtures.canonicalTellText()))
          .contains(ChatTestFixtures.canonicalTellText());
      assertThat(targetClient.readLineContaining(ChatTestFixtures.canonicalTellTargetText()))
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

  private static AccountRuntimeStubServer startAccountStub() throws IOException {
    AccountRuntimeStubServer stub = new AccountRuntimeStubServer(0);
    stub.setDefaultAccountId(ACCOUNT_ID);
    stub.mapAccountId("sora@example.com", SORA_ACCOUNT_ID);
    return stub;
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
    props.put("firemud.auth.jwt-secret", CROSS_SERVICE_TEST_JWT_SECRET);
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
    long insertedId =
        GameInstanceTestFixtures.insertRunningGameInstance(jdbc, TENANT_ID, ACCOUNT_ID, 7L);
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    return new GameSessionHolder(context, port, insertedId);
  }

  private static Map<String, Object> gameSessionProps(int gameLogicPort, int accountPort) {
    Map<String, Object> props = new java.util.LinkedHashMap<>();
    props.put("spring.profiles.active", "test");
    props.put("spring.application.name", "game-session-service");
    props.put("server.port", "0");
    props.put("spring.grpc.server.port", "0");
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
    props.put("firemud.auth.jwt-secret", CROSS_SERVICE_TEST_JWT_SECRET);
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
    props.put("spring.flyway.enabled", "true");
    props.put("spring.flyway.locations", "filesystem:" + gameSessionMigrationDir());
    props.put(
        "spring.autoconfigure.exclude",
        "org.springframework.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration,"
            + "org.springframework.boot.grpc.server.autoconfigure.GrpcServerFactoryAutoConfiguration,"
            + "org.springframework.boot.grpc.server.autoconfigure.health.GrpcServerHealthAutoConfiguration");
    return props;
  }

  private static String gameSessionMigrationDir() {
    return resolveModuleMigrationDir("game-session-service").toString();
  }

  private static Path resolveModuleMigrationDir(String moduleName) {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      Path candidate =
          current
              .resolve("services")
              .resolve(moduleName)
              .resolve("src/main/resources/db/migration");
      if (candidate.toFile().exists()) {
        return candidate;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Could not resolve migration directory for " + moduleName);
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

  private GameplayTelnetDriver openTelnetClient() throws IOException {
    return GameplayTelnetDriver.connect("localhost", telnetServer.getPort(), COMMAND_WAIT);
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

    @Bean
    @Primary
    ModerationPolicyClient moderationPolicyClient() {
      ModerationPolicyClient client = Mockito.mock(ModerationPolicyClient.class);
      Mockito.when(client.evaluateGameplayAdmission(Mockito.anyLong(), Mockito.anyLong()))
          .thenReturn(
              net.firedevops.firemud.loggingadmin.v1.EvaluateModerationPolicyResponse.newBuilder()
                  .setAllowed(true)
                  .build());
      return client;
    }

    @Bean(name = "gameInstanceServiceImpl")
    @Primary
    GameInstanceService stubGameInstanceService() {
      return new GameInstanceService() {
        @Override
        public GameInstanceDto startSession(
            StartSessionRequest request, boolean replaceExistingFirst) {
          return new GameInstanceDto(
              request.ownerAccountId(),
              request.tenantId(),
              "stub-template-" + request.gameTemplateId(),
              null,
              request.gameTemplateId(),
              null,
              null,
              null,
              null,
              null,
              request.ownerAccountId(),
              "RUNNING");
        }

        @Override
        public GameInstanceDto stopSession(long sessionId) {
          return new GameInstanceDto(
              sessionId, 0L, "stub", null, null, null, null, null, null, null, 0L, "STOPPED");
        }

        @Override
        public GameInstanceDto restartSession(long sessionId) {
          return new GameInstanceDto(
              sessionId, 0L, "stub", null, null, null, null, null, null, null, 0L, "RUNNING");
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
        public Mono<GatewayRoute> upsert(GatewayRoute route) {
          return Mono.just(route);
        }

        @Override
        public Mono<Boolean> remove(String routeId) {
          return Mono.just(true);
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
