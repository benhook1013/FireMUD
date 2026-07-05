package net.firedevops.firemud.tcpproxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.gamesession.CrossServiceAppHarness;
import net.firedevops.firemud.gamesession.service.ActiveTransportSessionRegistry;
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
import net.firedevops.firemud.gamesession.testsupport.GameplayWebSocketDriver;
import net.firedevops.firemud.socialgroups.v1.ChatType;
import net.firedevops.firemud.tcpproxy.stub.GatewayStubApplication;
import net.firedevops.firemud.tcpproxy.telnet.TelnetServer;
import net.firedevops.firemud.tcpproxy.testsupport.GameplayTelnetDriver;
import net.firedevops.firemud.tcpproxy.testsupport.GameplayTelnetScenarios;
import net.firedevops.firemud.test.AccountRuntimeStubServer;
import net.firedevops.firemud.test.HttpTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
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
  void clearSharedRuntimeState() throws Exception {
    ensureTestServicesStarted();
    STACK.freshGameplayBaseline(TENANT_ID, DEFAULT_GAME_INSTANCE_ID, ACCOUNT_ID, 7L, ACCOUNT_ID);
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
    try (GameplayTelnetDriver client = openAdmittedTelnetClient()) {
      telnetLoginResponse = "Logged in as demo@example.com";
      assertThat(telnetLoginResponse).contains("Logged in as demo@example.com");
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
    try (GameplayTelnetDriver firstClient = openAdmittedTelnetClient()) {}

    accountStub().setGameplayAdmissionAllowed(false);

    try (GameplayTelnetScenarios.LoginThenPlayScenario scenario =
        GameplayTelnetScenarios.loginThenAttemptPlay(
            this::openTelnetClient,
            GameplayTelnetScenarios.demoAdmission(READY_LOOK_TEXT),
            client ->
                assertThat(client.readLineContaining("ERROR WORLD_ACCESS_DENIED"))
                    .contains("ERROR WORLD_ACCESS_DENIED")
                    .contains("You are not allowed to enter that world."))) {
      assertThat(scenario.responses())
          .anyMatch(response -> response.contains("ERROR WORLD_ACCESS_DENIED"))
          .anyMatch(response -> response.contains("You are not allowed to enter that world."));
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
  void telnetItemLoopStillSucceedsAfterWebSocketLogoutOnSharedRuntime() throws Exception {
    ensureTestServicesStarted();

    try (GameplayWebSocketDriver webSocketClient = openReadyWebSocketClient(1L)) {
      webSocketClient.send("INV HERE");
      assertThat(
              webSocketClient.awaitResponseMatching(
                  response ->
                      response.contains("Room Inventory:")
                          && response.contains("Torch")
                          && response.contains("Backpack"),
                  "room inventory before pickup"))
          .contains("Room Inventory:");
      webSocketClient.send("GET Torch");
      assertThat(
              webSocketClient.awaitResponseMatching(
                  response -> response.contains("You pick up Torch."), "pickup response"))
          .contains("You pick up Torch.");
      webSocketClient.send("CONTAINER Backpack");
      assertThat(
              webSocketClient.awaitResponseMatching(
                  response ->
                      response.contains("Container: Backpack [backpack#1]")
                          && response.contains("Ration"),
                  "container view"))
          .contains("Container: Backpack [backpack#1]");
      webSocketClient.send("PUT Torch INTO Backpack");
      assertThat(
              webSocketClient.awaitResponseMatching(
                  response -> response.contains("You put Torch into Backpack."), "put response"))
          .contains("You put Torch into Backpack.");
      assertThat(
              webSocketClient.awaitResponseMatching(
                  response ->
                      response.contains("Container: Backpack [backpack#1]")
                          && response.contains("Torch"),
                  "container view after put"))
          .contains("Container: Backpack [backpack#1]");
      webSocketClient.send("TAKE Torch FROM Backpack");
      assertThat(
              webSocketClient.awaitResponseMatching(
                  response -> response.contains("You take Torch from Backpack."), "take response"))
          .contains("You take Torch from Backpack.");
      assertThat(
              webSocketClient.awaitResponseMatching(
                  response ->
                      response.contains("Container: Backpack [backpack#1]")
                          && response.contains("Ration"),
                  "container view after take"))
          .contains("Container: Backpack [backpack#1]");
      webSocketClient.send("DROP Torch");
      assertThat(
              webSocketClient.awaitResponseMatching(
                  response -> response.contains("You drop Torch."), "drop response"))
          .contains("You drop Torch.");
      webSocketClient.send("LOGOUT");
      assertThat(
              webSocketClient.awaitResponseMatching(
                  response -> response.contains("OK LOGOUT") && response.contains("Logged out."),
                  "logout response"))
          .contains("OK LOGOUT");
    }

    try (GameplayTelnetDriver telnetClient = openReadyTelnetClient()) {
      telnetClient.sendLine("GET Torch");
      assertThat(telnetClient.readBlockContaining("You pick up Torch."))
          .contains("You pick up Torch.");
      telnetClient.sendLine("PUT Torch INTO Backpack");
      assertThat(telnetClient.readBlockContaining("You put Torch into Backpack."))
          .contains("You put Torch into Backpack.");
      telnetClient.sendLine("TAKE Torch FROM Backpack");
      assertThat(telnetClient.readBlockContaining("You take Torch from Backpack."))
          .contains("You take Torch from Backpack.");
    }
  }

  @Test
  void telnetReconnectAfterRevocationFailsClosed() throws Exception {
    ensureTestServicesStarted();
    try (GameplayTelnetScenarios.ReconnectScenario scenario =
        GameplayTelnetScenarios.reconnectAfterReady(
            this::openTelnetClient,
            READY_LOOK_TEXT,
            firstClient -> {
              firstClient.sendLine("MOVE north");
              assertThat(firstClient.readBlockContainingOrTimeout("OK LOOK"))
                  .contains(LookTestFixtures.DESTINATION_ROOM_ID);
            })) {
      assertThat(scenario.firstResponses())
          .anyMatch(response -> response.contains(LookTestFixtures.DESTINATION_ROOM_ID));
    }

    accountStub().setGameplayAdmissionAllowed(false);

    try (GameplayTelnetScenarios.LoginThenPlayScenario scenario =
        GameplayTelnetScenarios.loginThenAttemptPlay(
            this::openTelnetClient,
            READY_LOOK_TEXT,
            client ->
                assertThat(client.readLineContaining("ERROR WORLD_ACCESS_DENIED"))
                    .contains("ERROR WORLD_ACCESS_DENIED"))) {
      assertThat(scenario.responses())
          .anyMatch(response -> response.contains("ERROR WORLD_ACCESS_DENIED"));
    }
  }

  @Test
  void telnetReconnectAfterStaleSessionFreshEntersGameplay() throws Exception {
    ensureTestServicesStarted();
    String telnetMoveResponse;
    String telnetReplayResponse;
    String telnetReconnectLookResponse;

    try (GameplayTelnetDriver client = openAdmittedTelnetClient()) {
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

    try (GameplayTelnetScenarios.LoginThenPlayScenario scenario =
        GameplayTelnetScenarios.loginThenAttemptPlay(
            this::openTelnetClient,
            READY_LOOK_TEXT,
            client ->
                assertThat(client.readLineContaining("OK PLAY Entered world: demo"))
                    .isNotBlank())) {
      GameplayTelnetDriver client = scenario.driver();
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
    try (GameplayTelnetScenarios.TakeoverScenario scenario =
        GameplayTelnetScenarios.takeoverAfterAdmitted(
            this::openTelnetClient,
            READY_LOOK_TEXT,
            firstClient -> {
              firstClient.sendLine("LOOK");
              assertThat(firstClient.readBlockContainingOrTimeout("OK LOOK").trim())
                  .matches(GameplayTranscriptMatchers.matchesCanonicalLookWithOptionalPrompt());
            })) {
      scenario.takeover().sendLine("LOOK");
      assertThat(scenario.takeover().readBlockContainingOrTimeout("OK LOOK").trim())
          .matches(GameplayTranscriptMatchers.matchesCanonicalLookWithOptionalPrompt());
      scenario.first().sendLine("LOOK");
      assertThat(scenario.first().readLineContaining("ERROR LOGIN_REQUIRED"))
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
            GameplayTelnetScenarios.demoAdmission(READY_LOOK_TEXT),
            GameplayTelnetScenarios.demoAdmission("Sora", READY_LOOK_TEXT),
            GameplayTelnetScenarios.demoAdmission("Nyx", READY_LOOK_TEXT))) {
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
            GameplayTelnetScenarios.demoAdmission("Emberline", READY_LOOK_TEXT),
            GameplayTelnetScenarios.demoAdmission("Sora", READY_LOOK_TEXT))) {
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
            GameplayCrossServiceStack.defaultDemoBuilder(POSTGRES, REDIS, ACCOUNT_ID)
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
                    GatewayBackedGameSessionTestOverrides.class, NestedReadinessOverrides.class)
                .start();
        DEFAULT_GAME_INSTANCE_ID =
            STACK.freshGameplayBaseline(TENANT_ID, 1L, ACCOUNT_ID, 7L, ACCOUNT_ID);
      } catch (IOException e) {
        throw new IllegalStateException("Failed to start shared gameplay stack", e);
      }
    }
    if (GATEWAY == null) {
      GATEWAY = startGateway(STACK.gameSessionPort());
    }
  }

  private void seedLiveTargetSession() {
    STACK.seedLiveSession(
        90210L,
        TENANT_ID,
        Long.parseLong(ChatTestFixtures.PLAYER_SORA),
        "sora@example.com",
        Long.parseLong(ChatTestFixtures.PLAYER_SORA),
        "Sora",
        DEMO_WORLD_INSTANCE_ID,
        LookTestFixtures.ROOM_ID,
        "target-jwt",
        "demo",
        "production",
        1L,
        "SHARED");
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
        Objects.requireNonNull(
                ((WebServerApplicationContext) context).getWebServer(),
                "gateway stub web server must be available")
            .getPort();
    return new GatewayHolder(context, port);
  }

  private GameplayTelnetDriver openTelnetClient() throws IOException {
    return GameplayTelnetDriver.connect("localhost", telnetServer.getPort(), COMMAND_WAIT);
  }

  private GameplayTelnetDriver openAdmittedTelnetClient() throws Exception {
    return GameplayTelnetScenarios.openAdmitted(this::openTelnetClient, READY_LOOK_TEXT);
  }

  private GameplayTelnetDriver openReadyTelnetClient() throws Exception {
    return GameplayTelnetScenarios.openReady(this::openTelnetClient, READY_LOOK_TEXT);
  }

  private GameplayWebSocketDriver openReadyWebSocketClient(long sessionId) throws Exception {
    GameplayWebSocketDriver client =
        GameplayWebSocketDriver.connectGameplaySession(
            URI.create(Objects.requireNonNull(GATEWAY, "gateway must be started").websocketUrl()),
            COMMAND_WAIT,
            TENANT_ID,
            sessionId);
    try {
      client.enterGameplayAndWaitReady("demo@example.com", "swordfish", "demo", READY_LOOK_TEXT);
      return client;
    } catch (Exception ex) {
      client.close();
      throw ex;
    }
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
  static class NestedReadinessOverrides {

    @Bean("trafficAdmissionReadiness")
    HealthIndicator trafficAdmissionReadinessHealthIndicator() {
      return () -> Health.up().withDetail("stub", "UP").build();
    }
  }
}
