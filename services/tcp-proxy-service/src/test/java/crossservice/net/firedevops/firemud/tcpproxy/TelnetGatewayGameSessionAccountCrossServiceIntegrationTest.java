package net.firedevops.firemud.tcpproxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
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
import net.firedevops.firemud.gamesession.testsupport.GameplayCrossServiceStack;
import net.firedevops.firemud.gamesession.testsupport.GameplayEntityAssertions;
import net.firedevops.firemud.gamesession.testsupport.GameplayTranscriptMatchers;
import net.firedevops.firemud.gamesession.testsupport.GameplayWebSocketDriver;
import net.firedevops.firemud.gamesession.testsupport.GameplayWebSocketScenarios;
import net.firedevops.firemud.tcpproxy.stub.GatewayStubApplication;
import net.firedevops.firemud.tcpproxy.telnet.TelnetServer;
import net.firedevops.firemud.tcpproxy.testsupport.GameplayTelnetDriver;
import net.firedevops.firemud.tcpproxy.testsupport.GameplayTelnetScenarios;
import net.firedevops.firemud.test.AccountRuntimeStubServer;
import net.firedevops.firemud.test.HttpTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
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

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = TcpProxyServiceApplication.class)
class TelnetGatewayGameSessionAccountCrossServiceIntegrationTest {

  // This suite boots multiple real service contexts and backing stores, so allow slower
  // round-trips than the lighter isolated telnet seam tests.
  private static final Duration COMMAND_WAIT = Duration.ofSeconds(20);
  private static final String CROSS_SERVICE_TEST_JWT_SECRET =
      "stub-secret-key-for-tests-1234567890";
  private static final long TENANT_ID = 1L;
  private static final long ACCOUNT_ID = 7L;
  private static final long SORA_ACCOUNT_ID = Long.parseLong(ChatTestFixtures.PLAYER_SORA);
  private static final long NYX_ACCOUNT_ID = Long.parseLong(ChatTestFixtures.PLAYER_NYX);
  private static final long DEMO_WORLD_INSTANCE_ID = 1L;
  private static final String READY_LOOK_TEXT = "Candle-lit Antechamber";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres())
          .withDatabaseName("firemud")
          .withUsername("firemud")
          .withPassword("firemud");

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

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
    awaitTrafficAdmissionReady();
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
                request.getEmail().equals(GameplayTelnetScenarios.DEMO_LOGIN_EMAIL)
                    && request.getPassword().equals(GameplayTelnetScenarios.DEMO_PASSWORD)
                    && request.getDescriptorForType().findFieldByName("tenant_id") == null);
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
                assertThat(client.readLineContaining("ERROR JOIN_REQUIRED"))
                    .contains("ERROR JOIN_REQUIRED")
                    .contains(
                        "Membership is required before PLAY; joining this world is not available yet."))) {
      assertThat(scenario.responses())
          .anyMatch(response -> response.contains("ERROR JOIN_REQUIRED"))
          .anyMatch(
              response ->
                  response.contains(
                      "Membership is required before PLAY; joining this world is not available yet."));
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
  void telnetAndGatewayWebSocketCanMoveNorthOnSharedRuntime() throws Exception {
    ensureTestServicesStarted();

    try (GameplayTelnetDriver telnetClient =
            GameplayTelnetScenarios.openReady(
                this::openTelnetClient,
                GameplayTelnetScenarios.demoAdmission("Emberline", READY_LOOK_TEXT));
        GameplayWebSocketDriver webSocketClient =
            openReadyGatewayWebSocketClient("sora@example.com", "Sora", "gateway-sora")) {
      SessionContextService sessionContextService = gameSession().bean(SessionContextService.class);
      telnetClient.sendLine("MOVE north");

      assertThat(
              telnetClient
                  .readBlockMatching(
                      GameplayTranscriptMatchers.matchesCanonicalMoveRefreshWithOptionalPrompt(
                          LookTestFixtures.DESTINATION_ROOM_ID),
                      "canonical telnet move refresh")
                  .trim())
          .matches(
              GameplayTranscriptMatchers.matchesCanonicalMoveRefreshWithOptionalPrompt(
                  LookTestFixtures.DESTINATION_ROOM_ID));

      webSocketClient.send("north");
      assertThat(
              webSocketClient.awaitCanonicalMoveOrLook(LookTestFixtures.DESTINATION_ROOM_ID).trim())
          .matches(
              GameplayTranscriptMatchers.matchesCanonicalMoveOrLookWithOptionalPrompt(
                  LookTestFixtures.DESTINATION_ROOM_ID));

      assertThat(
              sessionContextService.findByGameplayName(
                  TENANT_ID, DEMO_WORLD_INSTANCE_ID, "Emberline"))
          .hasValueSatisfying(
              context ->
                  assertThat(context.roomInstanceId())
                      .isEqualTo(LookTestFixtures.DESTINATION_ROOM_ID));
      assertThat(
              sessionContextService.findByGameplayName(TENANT_ID, DEMO_WORLD_INSTANCE_ID, "Sora"))
          .hasValueSatisfying(
              context ->
                  assertThat(context.roomInstanceId())
                      .isEqualTo(LookTestFixtures.DESTINATION_ROOM_ID));
    }
  }

  @Test
  void telnetNumericItemMutationsAreRefusedWhileEquipmentRemainsAvailable() throws Exception {
    ensureTestServicesStarted();
    try (GameplayTelnetDriver client = openReadyTelnetClient()) {
      client.sendLine("INV HERE");
      String groundBefore = client.readBlockContaining("Room Inventory:");
      client.sendLine("INV");
      String carriedBefore = client.readBlockContaining("Inventory:");
      assertThat(groundBefore).contains("Torch", "Backpack");
      assertThat(carriedBefore).contains("Leather Cap", "Iron Boots").doesNotContain("Torch");

      client.sendLine("GET Torch");
      assertThat(client.readLineContaining("ERROR INVENTORY_UNAVAILABLE"))
          .contains("ERROR INVENTORY_UNAVAILABLE");
      client.sendLine("DROP Leather Cap");
      assertThat(client.readLineContaining("ERROR INVENTORY_UNAVAILABLE"))
          .contains("ERROR INVENTORY_UNAVAILABLE");
      client.sendLine("INV HERE");
      assertThat(client.readBlockContaining("Room Inventory:"))
          .contains(
              "- Torch [torch#1] (A small torch)",
              "- Backpack [backpack#1] (A weathered backpack)");
      client.sendLine("INV");
      assertThat(client.readBlockContaining("Inventory:"))
          .contains(
              "- Leather Cap [cap#1] (A small cap)", "- Iron Boots [boots#1] (Heavy iron boots)")
          .doesNotContain("Torch");
      assertNoItemMutationRequests();
      client.sendLine("CONTAINER Backpack");
      assertThat(client.readBlockContaining("Container: Backpack [backpack#1]"))
          .contains("- Ration [ration#1] (A dry trail ration)")
          .doesNotContain("Torch");

      client.sendLine("EQUIPMENT");
      assertThat(client.readBlockContaining("You have nothing equipped."))
          .contains("You have nothing equipped.");
      client.sendLine("WEAR Leather Cap");
      assertThat(client.readLineContaining("You wear Leather Cap."))
          .contains("You wear Leather Cap.");
      client.sendLine("EQUIPMENT");
      assertThat(client.readBlockContaining("Equipment:"))
          .contains("- HEAD: Leather Cap [cap#1] (A small cap)");
      client.sendLine("REMOVE HEAD");
      assertThat(client.readLineContaining("You remove Leather Cap."))
          .contains("You remove Leather Cap.");
      client.sendLine("WEAR Iron Boots");
      assertThat(client.readLineContaining("ERROR SLOT_INCOMPATIBLE"))
          .contains("ERROR SLOT_INCOMPATIBLE", "Iron Boots cannot be worn by this body layout.");
    }
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
  void numericItemRefusalsPreserveSharedInventoryAcrossWebSocketLogout() throws Exception {
    ensureTestServicesStarted();
    try (GameplayWebSocketDriver client = openReadyGatewayWebSocketClient("gateway-1")) {
      int before = client.responseCount();
      client.send("INV HERE");
      assertThat(
              client.awaitTranscriptContainingAllAfter(
                  before, "Room Inventory:", "Torch", "Backpack"))
          .contains("Torch", "Backpack");
      before = client.responseCount();
      client.send("GET Torch");
      assertThat(client.awaitTranscriptContainingAllAfter(before, "ERROR INVENTORY_UNAVAILABLE"))
          .contains("ERROR INVENTORY_UNAVAILABLE");
      before = client.responseCount();
      client.send("DROP Leather Cap");
      assertThat(client.awaitTranscriptContainingAllAfter(before, "ERROR INVENTORY_UNAVAILABLE"))
          .contains("ERROR INVENTORY_UNAVAILABLE");
      before = client.responseCount();
      client.send("INV HERE");
      assertThat(
              client.awaitTranscriptContainingAllAfter(
                  before, "Room Inventory:", "Torch", "Backpack"))
          .contains("Torch", "Backpack");
      before = client.responseCount();
      client.send("INV");
      assertThat(
              client.awaitTranscriptContainingAllAfter(
                  before, "Inventory:", "Leather Cap", "Iron Boots"))
          .doesNotContain("Torch");
      assertNoItemMutationRequests();
      before = client.responseCount();
      client.send("LOGOUT");
      assertThat(client.awaitTranscriptContainingAllAfter(before, "OK LOGOUT", "Logged out."))
          .contains("OK LOGOUT");
    }
    try (GameplayTelnetDriver client = openReadyTelnetClient()) {
      client.sendLine("GET Torch");
      assertThat(client.readLineContaining("ERROR INVENTORY_UNAVAILABLE"))
          .contains("ERROR INVENTORY_UNAVAILABLE");
      client.sendLine("DROP Leather Cap");
      assertThat(client.readLineContaining("ERROR INVENTORY_UNAVAILABLE"))
          .contains("ERROR INVENTORY_UNAVAILABLE");
      client.sendLine("INV HERE");
      assertThat(client.readBlockContaining("Room Inventory:")).contains("Torch", "Backpack");
      client.sendLine("INV");
      assertThat(client.readBlockContaining("Inventory:"))
          .contains("Leather Cap", "Iron Boots")
          .doesNotContain("Torch");
      client.sendLine("CONTAINER Backpack");
      assertThat(client.readBlockContaining("Container: Backpack [backpack#1]"))
          .contains("Ration")
          .doesNotContain("Torch");
    }
    assertNoItemMutationRequests();
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
                assertThat(client.readLineContaining("ERROR JOIN_REQUIRED"))
                    .contains("ERROR JOIN_REQUIRED"))) {
      assertThat(scenario.responses())
          .anyMatch(response -> response.contains("ERROR JOIN_REQUIRED"));
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
    assertThat(telnetReplayResponse.trim()).isEqualTo("Emberline>");
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
  void telnetNumericWhisperAndTellAreRefusedWithoutSocialOrTargetWrites() throws Exception {
    ensureTestServicesStarted();
    entityStub().setRoomEntities(ChatTestFixtures.sampleEntities());
    seedLiveTargetSession();
    SessionContext target =
        gameSession()
            .bean(SessionContextService.class)
            .findByGameplayName(TENANT_ID, DEMO_WORLD_INSTANCE_ID, "Sora")
            .orElseThrow();
    ScreenBufferService buffers = gameSession().bean(ScreenBufferService.class);
    var targetBefore =
        buffers.get(target.tenantId(), target.gameInstanceId(), target.characterId());
    try (GameplayTelnetDriver client = openReadyTelnetClient()) {
      for (String command : List.of("WHISPER Sora Keep quiet", "TELL Sora Meet me at the forge")) {
        client.sendLine(command);
        assertThat(client.readLineContaining("ERROR COMMUNICATION_NOT_DELIVERED"))
            .contains("ERROR COMMUNICATION_NOT_DELIVERED", "Sender Account identity unavailable");
        assertThat(socialStub().lastRequest()).isEmpty();
        assertThat(buffers.get(target.tenantId(), target.gameInstanceId(), target.characterId()))
            .isEqualTo(targetBefore);
      }
    }
  }

  @Test
  void telnetNumericWhisperDoesNotWriteTargetOrObserverViews() throws Exception {
    ensureTestServicesStarted();
    entityStub().setRoomEntities(ChatTestFixtures.sampleEntities());
    try (GameplayTelnetScenarios.ThreePlayerScenario scenario =
        GameplayTelnetScenarios.openReadyTrio(
            this::openTelnetClient,
            characterAdmission(GameplayTelnetScenarios.DEMO_LOGIN_EMAIL, "Emberline"),
            characterAdmission("sora@example.com", "Sora"),
            characterAdmission("nyx@example.com", "Nyx"))) {
      SessionContextService contexts = gameSession().bean(SessionContextService.class);
      ActiveTransportSessionRegistry transports =
          gameSession().bean(ActiveTransportSessionRegistry.class);
      ScreenBufferService buffers = gameSession().bean(ScreenBufferService.class);
      SessionContext target =
          contexts.findByGameplayName(TENANT_ID, DEMO_WORLD_INSTANCE_ID, "Sora").orElseThrow();
      SessionContext observer =
          contexts.findByGameplayName(TENANT_ID, DEMO_WORLD_INSTANCE_ID, "Nyx").orElseThrow();
      assertThat(transports.find(target.sessionId())).isPresent();
      assertThat(transports.find(observer.sessionId())).isPresent();
      var targetBefore =
          buffers
              .get(target.tenantId(), target.gameInstanceId(), target.characterId())
              .map(
                  screen ->
                      screen.entries().stream()
                          .filter(entry -> !"look_view".equals(entry.payloadType()))
                          .toList());
      var observerBefore =
          buffers
              .get(observer.tenantId(), observer.gameInstanceId(), observer.characterId())
              .map(
                  screen ->
                      screen.entries().stream()
                          .filter(entry -> !"look_view".equals(entry.payloadType()))
                          .toList());
      scenario.actor().sendLine("WHISPER Sora Keep quiet");
      assertThat(scenario.actor().readLineContaining("ERROR COMMUNICATION_NOT_DELIVERED"))
          .contains("ERROR COMMUNICATION_NOT_DELIVERED", "Sender Account identity unavailable");
      assertThat(socialStub().lastRequest()).isEmpty();
      assertThat(
              buffers
                  .get(target.tenantId(), target.gameInstanceId(), target.characterId())
                  .map(
                      screen ->
                          screen.entries().stream()
                              .filter(entry -> !"look_view".equals(entry.payloadType()))
                              .toList()))
          .isEqualTo(targetBefore);
      assertThat(
              buffers
                  .get(observer.tenantId(), observer.gameInstanceId(), observer.characterId())
                  .map(
                      screen ->
                          screen.entries().stream()
                              .filter(entry -> !"look_view".equals(entry.payloadType()))
                              .toList()))
          .isEqualTo(observerBefore);
    }
  }

  @Test
  void telnetNumericSayDoesNotWriteRoomListenerView() throws Exception {
    ensureTestServicesStarted();
    entityStub().setRoomEntities(ChatTestFixtures.sampleEntities());
    try (GameplayTelnetScenarios.TwoPlayerScenario scenario =
        GameplayTelnetScenarios.openReadyPair(
            this::openTelnetClient,
            characterAdmission(GameplayTelnetScenarios.DEMO_LOGIN_EMAIL, "Emberline"),
            characterAdmission("sora@example.com", "Sora"))) {
      SessionContext target =
          gameSession()
              .bean(SessionContextService.class)
              .findByGameplayName(TENANT_ID, DEMO_WORLD_INSTANCE_ID, "Sora")
              .orElseThrow();
      assertThat(gameSession().bean(ActiveTransportSessionRegistry.class).find(target.sessionId()))
          .isPresent();
      ScreenBufferService buffers = gameSession().bean(ScreenBufferService.class);
      var targetBefore =
          buffers
              .get(target.tenantId(), target.gameInstanceId(), target.characterId())
              .map(
                  screen ->
                      screen.entries().stream()
                          .filter(entry -> !"look_view".equals(entry.payloadType()))
                          .toList());
      scenario.actor().sendLine("SAY hello travelers");
      assertThat(scenario.actor().readLineContaining("ERROR COMMUNICATION_NOT_DELIVERED"))
          .contains("ERROR COMMUNICATION_NOT_DELIVERED", "Sender Account identity unavailable");
      assertThat(socialStub().lastRequest()).isEmpty();
      assertThat(
              buffers
                  .get(target.tenantId(), target.gameInstanceId(), target.characterId())
                  .map(
                      screen ->
                          screen.entries().stream()
                              .filter(entry -> !"look_view".equals(entry.payloadType()))
                              .toList()))
          .isEqualTo(targetBefore);
    }
  }

  @Test
  void telnetNumericTellDoesNotWriteTargetView() throws Exception {
    ensureTestServicesStarted();
    entityStub().setRoomEntities(ChatTestFixtures.sampleEntities());
    try (GameplayTelnetScenarios.TwoPlayerScenario scenario =
        GameplayTelnetScenarios.openReadyPair(
            this::openTelnetClient,
            characterAdmission(GameplayTelnetScenarios.DEMO_LOGIN_EMAIL, "Emberline"),
            characterAdmission("sora@example.com", "Sora"))) {
      SessionContext target =
          gameSession()
              .bean(SessionContextService.class)
              .findByGameplayName(TENANT_ID, DEMO_WORLD_INSTANCE_ID, "Sora")
              .orElseThrow();
      assertThat(gameSession().bean(ActiveTransportSessionRegistry.class).find(target.sessionId()))
          .isPresent();
      ScreenBufferService buffers = gameSession().bean(ScreenBufferService.class);
      var targetBefore =
          buffers
              .get(target.tenantId(), target.gameInstanceId(), target.characterId())
              .map(
                  screen ->
                      screen.entries().stream()
                          .filter(entry -> !"look_view".equals(entry.payloadType()))
                          .toList());
      scenario.actor().sendLine("TELL Sora Meet me at the forge");
      assertThat(scenario.actor().readLineContaining("ERROR COMMUNICATION_NOT_DELIVERED"))
          .contains("ERROR COMMUNICATION_NOT_DELIVERED", "Sender Account identity unavailable");
      assertThat(socialStub().lastRequest()).isEmpty();
      assertThat(
              buffers
                  .get(target.tenantId(), target.gameInstanceId(), target.characterId())
                  .map(
                      screen ->
                          screen.entries().stream()
                              .filter(entry -> !"look_view".equals(entry.payloadType()))
                              .toList()))
          .isEqualTo(targetBefore);
    }
  }

  private static void assertNoItemMutationRequests() {
    assertThat(entityStub().lastPickupRequest()).isEmpty();
    assertThat(entityStub().lastDropRequest()).isEmpty();
    assertThat(entityStub().lastPutRequest()).isEmpty();
    assertThat(entityStub().lastTakeRequest()).isEmpty();
  }

  private static synchronized void ensureTestServicesStarted() {
    if (STACK != null && GATEWAY != null) {
      return;
    }

    GameplayCrossServiceStack stack = null;
    GatewayHolder gateway = null;
    try {
      stack =
          GameplayCrossServiceStack.defaultDemoBuilder(POSTGRES, REDIS, ACCOUNT_ID)
              .mapAccountId("sora@example.com", SORA_ACCOUNT_ID)
              .mapAccountId("nyx@example.com", NYX_ACCOUNT_ID)
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
      long defaultGameInstanceId =
          stack.freshGameplayBaseline(TENANT_ID, 1L, ACCOUNT_ID, 7L, ACCOUNT_ID);
      awaitGameSessionReadiness(stack.gameSessionPort());
      gateway = startGateway(stack.gameSessionPort());

      STACK = stack;
      GATEWAY = gateway;
      DEFAULT_GAME_INSTANCE_ID = defaultGameInstanceId;
    } catch (IOException e) {
      cleanupFailedStart(gateway, stack, e);
      throw new IllegalStateException("Failed to start shared gameplay stack", e);
    } catch (RuntimeException | Error e) {
      cleanupFailedStart(gateway, stack, e);
      throw e;
    }
  }

  private static void cleanupFailedStart(
      GatewayHolder gateway, GameplayCrossServiceStack stack, Throwable original) {
    try {
      if (gateway != null) {
        gateway.close();
      }
    } catch (RuntimeException | Error gatewayCloseFailure) {
      original.addSuppressed(gatewayCloseFailure);
    }
    try {
      if (stack != null) {
        stack.close();
      }
    } catch (RuntimeException | Error stackCloseFailure) {
      original.addSuppressed(stackCloseFailure);
    }
  }

  private static void awaitGameSessionReadiness(int gameSessionPort) {
    try {
      HttpTestSupport.awaitReadiness(
          "http://localhost:" + gameSessionPort + "/actuator/health/readiness", COMMAND_WAIT);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted waiting for Game Session readiness", e);
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

  private GameplayTelnetDriver openTelnetClient() throws Exception {
    awaitTrafficAdmissionReady();
    return GameplayTelnetDriver.connect("localhost", telnetServer.getPort(), COMMAND_WAIT);
  }

  private void awaitTrafficAdmissionReady() throws InterruptedException {
    HttpTestSupport.awaitReadiness(
        "http://localhost:" + port + "/actuator/health/readiness", COMMAND_WAIT);
  }

  private GameplayTelnetDriver openAdmittedTelnetClient() throws Exception {
    return GameplayTelnetScenarios.openAdmitted(this::openTelnetClient, READY_LOOK_TEXT);
  }

  private GameplayTelnetDriver openReadyTelnetClient() throws Exception {
    return GameplayTelnetScenarios.openReady(this::openTelnetClient, READY_LOOK_TEXT);
  }

  private GameplayWebSocketDriver openReadyGatewayWebSocketClient(String connectionId)
      throws Exception {
    return GameplayWebSocketScenarios.openReady(
        URI.create(Objects.requireNonNull(GATEWAY, "gateway must be started").websocketUrl()),
        COMMAND_WAIT,
        TENANT_ID,
        1L,
        READY_LOOK_TEXT,
        connectionId);
  }

  private GameplayWebSocketDriver openReadyGatewayWebSocketClient(
      String accountEmail, String characterName, String connectionId) throws Exception {
    return GameplayWebSocketScenarios.openReady(
        URI.create(Objects.requireNonNull(GATEWAY, "gateway must be started").websocketUrl()),
        COMMAND_WAIT,
        TENANT_ID,
        1L,
        GameplayWebSocketScenarios.Admission.named(
            accountEmail,
            GameplayWebSocketScenarios.DEMO_PASSWORD,
            GameplayWebSocketScenarios.DEMO_WORLD,
            characterName,
            READY_LOOK_TEXT),
        connectionId);
  }

  private static GameplayTelnetScenarios.Admission characterAdmission(
      String accountEmail, String characterName) {
    return GameplayTelnetScenarios.Admission.named(
        accountEmail,
        GameplayTelnetScenarios.DEMO_PASSWORD,
        GameplayTelnetScenarios.DEMO_WORLD,
        characterName,
        READY_LOOK_TEXT);
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
