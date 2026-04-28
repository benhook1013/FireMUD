package net.firedevops.firemud.tcpproxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.common.conflict.ConflictTracker;
import net.firedevops.firemud.gamesession.CrossServiceAppHarness;
import net.firedevops.firemud.gamesession.client.ModerationPolicyClient;
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
import net.firedevops.firemud.gamesession.testsupport.GameplayAsyncAssertions;
import net.firedevops.firemud.gamesession.testsupport.GameplayCrossServiceStack;
import net.firedevops.firemud.gamesession.testsupport.GameplayEntityAssertions;
import net.firedevops.firemud.gamesession.testsupport.GameplayTranscriptMatchers;
import net.firedevops.firemud.socialgroups.v1.ChatType;
import net.firedevops.firemud.springcloudgateway.service.GatewayRoute;
import net.firedevops.firemud.springcloudgateway.service.GatewayRouteService;
import net.firedevops.firemud.tcpproxy.stub.GatewayStubApplication;
import net.firedevops.firemud.tcpproxy.telnet.TelnetServer;
import net.firedevops.firemud.tcpproxy.testsupport.GameplayTelnetDriver;
import net.firedevops.firemud.tcpproxy.testsupport.GameplayTelnetScenarios;
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
  private static final String READY_LOOK_TEXT = "Candle-lit Antechamber";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("firemud")
          .withUsername("firemud")
          .withPassword("firemud");

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);

  private static GameplayCrossServiceStack STACK;
  private static long DEFAULT_GAME_INSTANCE_ID;
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
    registry.add("TCP_PROXY_DEFAULT_WORLD_SLUG", () -> "demo");
    registry.add("TCP_PROXY_DEFAULT_REALM_SLUG", () -> "production");
    registry.add("TCP_PROXY_DEFAULT_POINTER_VERSION", () -> "1");
    registry.add(
        "TCP_PROXY_DEFAULT_GAME_INSTANCE_ID", () -> String.valueOf(DEFAULT_GAME_INSTANCE_ID));
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

    GameplayCrossServiceStack stack = STACK;
    STACK = null;
    if (stack != null) {
      stack.close();
    }
  }

  @BeforeEach
  void clearSharedRuntimeState() {
    STACK.resetScenarioState();
    STACK.clearRedis();
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
      client.login("demo@example.com", "swordfish");
      telnetLoginResponse = "Logged in as demo@example.com";
      assertThat(telnetLoginResponse).contains("Logged in as demo@example.com");
      client.play("demo");
      client.sendLine("LOOK");
      telnetLookResponse = client.readBlockContaining("OK LOOK");
    }

    assertThat(telnetLookResponse.trim())
        .matches(GameplayTranscriptMatchers.matchesCanonicalLookWithOptionalPrompt());

    assertThat(accountStub().capturedAuthenticateRequests())
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
      firstClient.login("demo@example.com", "swordfish");
      firstClient.play("demo");
    }

    accountStub().setGameplayAdmissionAllowed(false);

    try (GameplayTelnetDriver secondClient = openTelnetClient()) {
      secondClient.awaitInitialGuidance();
      secondClient.login("demo@example.com", "swordfish");

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
    try (GameplayTelnetDriver client = openReadyTelnetClient()) {

      client.sendLine("MOVE north");
      telnetMoveResponse = client.readBlockContainingOrTimeout("OK LOOK");

      client.sendLine("LOOK");
      telnetLookResponse = client.readBlockContainingOrTimeout("OK LOOK");

      client.sendLine("MOVE west");
      telnetInvalidMoveResponse = client.readLineContaining("ERROR INVALID_EXIT");
    }

    assertThat(telnetMoveResponse.trim())
        .matches(
            GameplayTranscriptMatchers.matchesCanonicalMoveRefreshWithOptionalPrompt(
                LookTestFixtures.DESTINATION_ROOM_ID));
    assertThat(telnetLookResponse)
        .contains("Room: Crafting Hall of Ember")
        .contains("A soot-dark hall ringed with anvils and cooling braziers.");
    assertThat(telnetInvalidMoveResponse).contains("ERROR INVALID_EXIT");
  }

  @Test
  void telnetItemAndEquipmentLoopMatchesWebSocketCommandSurface() throws Exception {
    ensureTestServicesStarted();
    entityStub().resetRoomEntities();
    entityStub().resetItemState();

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
    try (GameplayTelnetDriver client = openReadyTelnetClient()) {

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
    GameplayEntityAssertions.assertPickup(
        entityStub().lastPickupRequest(),
        String.valueOf(TENANT_ID),
        ChatTestFixtures.PLAYER_EMBERLINE,
        null,
        LookTestFixtures.ROOM_ID,
        "torch");
    GameplayEntityAssertions.assertDrop(
        entityStub().lastDropRequest(),
        String.valueOf(TENANT_ID),
        ChatTestFixtures.PLAYER_EMBERLINE,
        null,
        LookTestFixtures.ROOM_ID,
        "torch");
    GameplayEntityAssertions.assertPut(
        entityStub().lastPutRequest(),
        String.valueOf(TENANT_ID),
        ChatTestFixtures.PLAYER_EMBERLINE,
        "container-backpack-1",
        "torch",
        "torch-ground-1");
    GameplayEntityAssertions.assertTake(
        entityStub().lastTakeRequest(),
        String.valueOf(TENANT_ID),
        ChatTestFixtures.PLAYER_EMBERLINE,
        "container-backpack-1",
        "torch",
        "");
    GameplayEntityAssertions.assertRemove(
        entityStub().lastRemoveRequest(),
        String.valueOf(TENANT_ID),
        ChatTestFixtures.PLAYER_EMBERLINE,
        "HEAD");
    GameplayEntityAssertions.assertWear(
        entityStub().lastWearRequest(),
        String.valueOf(TENANT_ID),
        ChatTestFixtures.PLAYER_EMBERLINE,
        "iron-boots",
        null);
  }

  @Test
  void telnetReconnectAfterRevocationFailsClosed() throws Exception {
    ensureTestServicesStarted();
    try (GameplayTelnetDriver firstClient = openTelnetClient()) {
      firstClient.awaitInitialGuidance();
      firstClient.login("demo@example.com", "swordfish");
      firstClient.play("demo");
      firstClient.sendLine("MOVE north");
      assertThat(firstClient.readBlockContainingOrTimeout("OK LOOK"))
          .contains(LookTestFixtures.DESTINATION_ROOM_ID);
    }

    accountStub().setGameplayAdmissionAllowed(false);

    try (GameplayTelnetDriver secondClient = openTelnetClient()) {
      secondClient.awaitInitialGuidance();
      secondClient.login("demo@example.com", "swordfish");
      secondClient.sendLine("PLAY demo");
      assertThat(secondClient.readLineContaining("ERROR WORLD_ACCESS_DENIED"))
          .contains("ERROR WORLD_ACCESS_DENIED");
    }
  }

  @Test
  void telnetReconnectAfterStaleSessionFreshEntersGameplay() throws Exception {
    ensureTestServicesStarted();
    String telnetMoveResponse;
    String telnetReplayResponse;
    String telnetReconnectLookResponse;

    try (GameplayTelnetDriver client = openTelnetClient()) {
      client.awaitInitialGuidance();
      client.login("demo@example.com", "swordfish");
      client.play("demo");
      client.sendLine("MOVE north");
      telnetMoveResponse = client.readBlockContainingOrTimeout("OK LOOK");
    }

    SessionContextService sessionContextService = gameSession().bean(SessionContextService.class);
    long activeGameplaySessionId =
        sessionContextService
            .findByGameplayIdentity(TENANT_ID, DEMO_WORLD_INSTANCE_ID, ACCOUNT_ID)
            .orElseThrow(() -> new IllegalStateException("Expected active gameplay binding"))
            .sessionId();
    sessionContextService.deleteBySessionId(TENANT_ID, activeGameplaySessionId);

    try (GameplayTelnetDriver client = openTelnetClient()) {
      client.awaitInitialGuidance();
      client.login("demo@example.com", "swordfish");
      client.play("demo");
      telnetReplayResponse = client.readBlockContainingOrTimeout("OK LOOK");
      client.sendLine("LOOK");
      telnetReconnectLookResponse = client.readBlockContainingOrTimeout("OK LOOK");
    }

    assertThat(telnetMoveResponse.trim())
        .matches(
            GameplayTranscriptMatchers.matchesCanonicalMoveRefreshWithOptionalPrompt(
                LookTestFixtures.DESTINATION_ROOM_ID));
    assertThat(telnetReplayResponse.trim()).isEqualTo("demo>");
    assertThat(telnetReconnectLookResponse.trim())
        .matches(GameplayTranscriptMatchers.matchesCanonicalLookWithOptionalPrompt());
  }

  @Test
  void telnetSecondConnectionTakesOverGameplayBinding() throws Exception {
    ensureTestServicesStarted();

    try (GameplayTelnetDriver firstClient = openTelnetClient();
        GameplayTelnetDriver secondClient = openTelnetClient()) {
      firstClient.awaitInitialGuidance();
      secondClient.awaitInitialGuidance();

      firstClient.login("demo@example.com", "swordfish");
      firstClient.play("demo");
      firstClient.sendLine("LOOK");
      assertThat(firstClient.readBlockContainingOrTimeout("OK LOOK").trim())
          .matches(GameplayTranscriptMatchers.matchesCanonicalLookWithOptionalPrompt());

      secondClient.login("demo@example.com", "swordfish");
      secondClient.play("demo");
      secondClient.sendLine("LOOK");
      assertThat(secondClient.readBlockContainingOrTimeout("OK LOOK").trim())
          .matches(GameplayTranscriptMatchers.matchesCanonicalLookWithOptionalPrompt());

      firstClient.sendLine("LOOK");
      assertThat(firstClient.readLineContaining("ERROR LOGIN_REQUIRED"))
          .contains("ERROR LOGIN_REQUIRED");
    }
  }

  @Test
  void telnetCommunicationMatchesCanonicalTranscriptsWithoutSession() throws Exception {
    ensureTestServicesStarted();
    entityStub().setRoomEntities(ChatTestFixtures.sampleEntities());
    seedLiveTargetSession();

    String telnetWhisperResponse;
    String telnetTellResponse;
    try (GameplayTelnetDriver client = openReadyTelnetClient()) {

      client.sendLine("WHISPER Sora Keep quiet");
      telnetWhisperResponse = client.readLineContaining(ChatTestFixtures.canonicalWhisperText());
      GameplayEntityAssertions.assertMessage(
          socialStub().lastRequest(),
          ChatType.CHAT_TYPE_WHISPER,
          ChatTestFixtures.PLAYER_SORA,
          null,
          false);

      client.sendLine("TELL Sora Meet me at the forge");
      telnetTellResponse = client.readLineContaining(ChatTestFixtures.canonicalTellText());
    }

    assertThat(telnetWhisperResponse).contains(ChatTestFixtures.canonicalWhisperText());
    assertThat(telnetTellResponse).contains(ChatTestFixtures.canonicalTellText());
    GameplayEntityAssertions.assertMessage(
        socialStub().lastRequest(),
        ChatType.CHAT_TYPE_TELL,
        ChatTestFixtures.PLAYER_SORA,
        null,
        false);
  }

  @Test
  void telnetWhisperDeliversTargetAndObserverViews() throws Exception {
    ensureTestServicesStarted();
    entityStub().setRoomEntities(ChatTestFixtures.sampleEntities());

    try (GameplayTelnetScenarios.ThreePlayerScenario scenario =
        GameplayTelnetScenarios.openReadyTrio(
            this::openTelnetClient,
            GameplayTelnetScenarios.Admission.unnamed(
                "demo@example.com", "swordfish", "demo", READY_LOOK_TEXT),
            GameplayTelnetScenarios.Admission.named(
                "demo@example.com", "swordfish", "demo", "Sora", READY_LOOK_TEXT),
            GameplayTelnetScenarios.Admission.named(
                "demo@example.com", "swordfish", "demo", "Nyx", READY_LOOK_TEXT))) {
      SessionContextService sessionContextService = gameSession().bean(SessionContextService.class);
      ActiveTransportSessionRegistry sessionRegistry =
          gameSession().bean(ActiveTransportSessionRegistry.class);
      ScreenBufferService screenBufferService = gameSession().bean(ScreenBufferService.class);
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

      scenario.actor().sendLine("WHISPER Sora Keep quiet");
      GameplayAsyncAssertions.assertBufferedScreenEventuallyContains(
          screenBufferService,
          targetContext,
          COMMAND_WAIT,
          ChatTestFixtures.canonicalWhisperTargetText());
      GameplayAsyncAssertions.assertBufferedScreenEventuallyContains(
          screenBufferService,
          observerContext,
          COMMAND_WAIT,
          ChatTestFixtures.canonicalWhisperObserverMetadataText());
      assertThat(scenario.actor().readLineContaining(ChatTestFixtures.canonicalWhisperText()))
          .contains(ChatTestFixtures.canonicalWhisperText());
      assertThat(
              scenario.target().readLineContaining(ChatTestFixtures.canonicalWhisperTargetText()))
          .contains(ChatTestFixtures.canonicalWhisperTargetText());
      assertThat(scenario.observer().readLineContaining("Emberline whispers something to Sora."))
          .contains(ChatTestFixtures.canonicalWhisperObserverMetadataText());
    }
  }

  @Test
  void telnetTellDeliversTargetView() throws Exception {
    ensureTestServicesStarted();
    entityStub().setRoomEntities(ChatTestFixtures.sampleEntities());

    try (GameplayTelnetScenarios.TwoPlayerScenario scenario =
        GameplayTelnetScenarios.openReadyPair(
            this::openTelnetClient,
            GameplayTelnetScenarios.Admission.named(
                "demo@example.com", "swordfish", "demo", "Emberline", READY_LOOK_TEXT),
            GameplayTelnetScenarios.Admission.named(
                "demo@example.com", "swordfish", "demo", "Sora", READY_LOOK_TEXT))) {
      SessionContextService sessionContextService = gameSession().bean(SessionContextService.class);
      ActiveTransportSessionRegistry sessionRegistry =
          gameSession().bean(ActiveTransportSessionRegistry.class);
      ScreenBufferService screenBufferService = gameSession().bean(ScreenBufferService.class);
      SessionContext targetContext =
          sessionContextService
              .findByGameplayName(TENANT_ID, DEMO_WORLD_INSTANCE_ID, "Sora")
              .orElseThrow();
      assertThat(sessionRegistry.find(targetContext.sessionId())).isPresent();

      scenario.actor().sendLine("TELL Sora Meet me at the forge");
      GameplayAsyncAssertions.assertBufferedScreenEventuallyContains(
          screenBufferService,
          targetContext,
          COMMAND_WAIT,
          ChatTestFixtures.canonicalTellTargetText());
      assertThat(scenario.actor().readLineContaining(ChatTestFixtures.canonicalTellText()))
          .contains(ChatTestFixtures.canonicalTellText());
      assertThat(scenario.target().readLineContaining(ChatTestFixtures.canonicalTellTargetText()))
          .contains(ChatTestFixtures.canonicalTellTargetText());
    }
  }

  private static synchronized void ensureTestServicesStarted() {
    if (STACK == null) {
      try {
        STACK =
            GameplayCrossServiceStack.builder()
                .withPostgres(
                    POSTGRES.getHost(),
                    POSTGRES.getMappedPort(5432),
                    POSTGRES.getDatabaseName(),
                    POSTGRES.getUsername(),
                    POSTGRES.getPassword())
                .withRedis(REDIS.getHost(), REDIS.getMappedPort(6379))
                .withDefaultAccountId(ACCOUNT_ID)
                .mapAccountId("sora@example.com", SORA_ACCOUNT_ID)
                .withSocialEnabled(true)
                .withGameLogicProps(
                    Map.of(
                        "spring.autoconfigure.exclude",
                        "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration"))
                .withGameSessionProps(
                    Map.of(
                        "game-session.require-authenticated-commands",
                        "true",
                        "spring.main.allow-bean-definition-overriding",
                        "true",
                        "firemud.auth.jwt-secret",
                        CROSS_SERVICE_TEST_JWT_SECRET,
                        "management.endpoint.health.group.readiness.include",
                        "readinessState,db,redis,gameplayPathReadiness"))
                .withGameLogicConfigs(NestedReadinessOverrides.class)
                .withGameSessionConfigs(
                    GameSessionTestOverrides.class, NestedReadinessOverrides.class)
                .start();
        DEFAULT_GAME_INSTANCE_ID = STACK.insertRunningGameInstance(TENANT_ID, ACCOUNT_ID, 7L, true);
      } catch (IOException e) {
        throw new IllegalStateException("Failed to start shared gameplay stack", e);
      }
    }
    if (GATEWAY == null) {
      GATEWAY = startGateway(STACK.gameSessionPort());
    }
  }

  private void seedLiveTargetSession() {
    gameSession()
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

  private GameplayTelnetDriver openTelnetClient() throws IOException {
    return GameplayTelnetDriver.connect("localhost", telnetServer.getPort(), COMMAND_WAIT);
  }

  private GameplayTelnetDriver openReadyTelnetClient() throws Exception {
    return GameplayTelnetScenarios.openReady(
        this::openTelnetClient,
        GameplayTelnetScenarios.Admission.unnamed(
            "demo@example.com", "swordfish", "demo", READY_LOOK_TEXT));
  }

  private static AccountRuntimeStubServer accountStub() {
    return STACK.accountStub();
  }

  private static EntityManagementStubServer entityStub() {
    return STACK.entityStub();
  }

  private static SocialGroupsStubServer socialStub() {
    return STACK.socialStub();
  }

  private static CrossServiceAppHarness.GameSessionHolder gameSession() {
    return STACK.gameSession();
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
