package net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.net.URI;
import java.sql.SQLException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.test.ChatTestFixtures;
import net.firedevops.firemud.gamesession.testsupport.GameplayAsyncAssertions;
import net.firedevops.firemud.gamesession.testsupport.GameplayCrossServiceStack;
import net.firedevops.firemud.gamesession.testsupport.GameplayEntityAssertions;
import net.firedevops.firemud.gamesession.testsupport.GameplayWebSocketDriver;
import net.firedevops.firemud.gamesession.testsupport.GameplayWebSocketScenarios;
import net.firedevops.firemud.socialgroups.v1.FriendPresenceActivityState;
import net.firedevops.firemud.socialgroups.v1.FriendPresenceEntry;
import net.firedevops.firemud.test.AccountRuntimeStubServer;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CommunicationWebSocketCrossServiceTest {
  private static final Duration COMMAND_WAIT = Duration.ofSeconds(45);
  private static final long TENANT_ID = 1L;
  private static final long ACCOUNT_ID = ChatTestFixtures.EMBERLINE_ACCOUNT_ROW_ID;
  private static final long SORA_ACCOUNT_ID = ChatTestFixtures.SORA_ACCOUNT_ROW_ID;
  private static final long NYX_ACCOUNT_ID = ChatTestFixtures.NYX_ACCOUNT_ROW_ID;
  private static final long DEMO_WORLD_INSTANCE_ID = 1L;
  private static final String READY_LOOK_TEXT = "Candle-lit Antechamber";

  private enum BaselineTruncateGuard {
    CANONICAL_LAUNCH(
        "game_session_canonical_instance_launch",
        "game_session_canonical_instance_launch_no_truncate"),
    AUTHORED_ACTOR_CORE(
        "gs_original_start_session_authored_actor_core_retention",
        "gs_original_start_session_actor_core_retention_no_truncate"),
    HUMAN_RUNTIME_COMPLETION(
        "game_session_canonical_human_runtime_completion",
        "game_session_canonical_human_runtime_completion_no_truncate");

    private final String tableName;
    private final String triggerName;

    BaselineTruncateGuard(String tableName, String triggerName) {
      this.tableName = tableName;
      this.triggerName = triggerName;
    }
  }

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

  @AfterAll
  static synchronized void stopServices() {
    GameplayCrossServiceStack stack = STACK;
    STACK = null;
    if (stack != null) {
      stack.close();
    }
  }

  @Test
  void websocketNumericSayRefusesWithoutSocialWriteAndReportsMetrics() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    List<String> responses =
        runCommunicationSequence(
            sessionId, "SAY hello travelers", "ERROR COMMUNICATION_NOT_DELIVERED");
    assertThat(responses).hasSizeGreaterThanOrEqualTo(3);
    assertThat(responses).anyMatch(response -> response.startsWith("OK LOGIN"));
    assertThat(responses).anyMatch(response -> response.startsWith("OK PLAY"));
    assertThat(responses)
        .anyMatch(
            response ->
                response.contains(
                    "ERROR COMMUNICATION_NOT_DELIVERED Sender Account identity unavailable"));
    assertThat(socialStub().messageRequests()).isEmpty();
    GameplayAsyncAssertions.assertMetricEventually(
        gameSession().bean(io.micrometer.core.instrument.MeterRegistry.class),
        COMMAND_WAIT,
        "gamesession.command.say.invocations",
        1.0);
    GameplayAsyncAssertions.assertMetricEventually(
        gameSession().bean(io.micrometer.core.instrument.MeterRegistry.class),
        COMMAND_WAIT,
        "gamesession.command.say.failures",
        1.0,
        "error",
        "INVALID_ARGUMENT");
  }

  @Test
  void websocketNumericSayDoesNotWriteLiveRoomListenerView() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    try (GameplayWebSocketScenarios.TwoPlayerScenario scenario =
        GameplayWebSocketScenarios.openReadyPair(
            GameplayWebSocketScenarios.proxyGatewayDriverFactory(
                gameSessionWebSocketUrl(), COMMAND_WAIT, TENANT_ID, sessionId),
            "numeric-actor-conn",
            GameplayWebSocketScenarios.demoAdmission("Emberline", READY_LOOK_TEXT),
            "numeric-target-conn",
            GameplayWebSocketScenarios.demoAdmission(
                "sora@example.com", "Sora", READY_LOOK_TEXT))) {
      var target =
          gameSession()
              .bean(SessionContextService.class)
              .findByGameplayName(TENANT_ID, DEMO_WORLD_INSTANCE_ID, "Sora")
              .orElseThrow();
      ScreenBufferService buffers = gameSession().bean(ScreenBufferService.class);
      var before = buffers.get(target.tenantId(), target.gameInstanceId(), target.characterId());
      int cursor = scenario.actor().responseCount();
      scenario.actor().send("SAY hello travelers");
      assertThat(
              scenario
                  .actor()
                  .awaitTranscriptContainingAllAfter(
                      cursor,
                      "ERROR COMMUNICATION_NOT_DELIVERED",
                      "Sender Account identity unavailable"))
          .contains("ERROR COMMUNICATION_NOT_DELIVERED");
      assertThat(socialStub().messageRequests()).isEmpty();
      assertThat(buffers.get(target.tenantId(), target.gameInstanceId(), target.characterId()))
          .isEqualTo(before);
    }
  }

  @Test
  void websocketNumericWhisperRefusesWithoutSocialWriteAndReportsMetrics() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    List<String> responses =
        runCommunicationSequence(
            sessionId, "WHISPER Sora keep quiet", "ERROR COMMUNICATION_NOT_DELIVERED");
    assertThat(responses).hasSizeGreaterThanOrEqualTo(3);
    assertThat(responses).anyMatch(response -> response.startsWith("OK LOGIN"));
    assertThat(responses).anyMatch(response -> response.startsWith("OK PLAY"));
    assertThat(responses)
        .anyMatch(
            response ->
                response.contains(
                    "ERROR COMMUNICATION_NOT_DELIVERED Sender Account identity unavailable"));
    assertThat(socialStub().messageRequests()).isEmpty();
    GameplayAsyncAssertions.assertMetricEventually(
        gameSession().bean(io.micrometer.core.instrument.MeterRegistry.class),
        COMMAND_WAIT,
        "gamesession.command.whisper.invocations",
        1.0);
    GameplayAsyncAssertions.assertMetricEventually(
        gameSession().bean(io.micrometer.core.instrument.MeterRegistry.class),
        COMMAND_WAIT,
        "gamesession.command.whisper.failures",
        1.0,
        "error",
        "INVALID_ARGUMENT");
  }

  @Test
  void websocketNumericTellRefusesWithoutSocialOrTargetWritesAndReportsMetrics() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    seedLiveTargetSession();
    var target =
        gameSession()
            .bean(SessionContextService.class)
            .findByGameplayName(TENANT_ID, DEMO_WORLD_INSTANCE_ID, "Sora")
            .orElseThrow();
    ScreenBufferService buffers = gameSession().bean(ScreenBufferService.class);
    var targetBefore =
        buffers.get(target.tenantId(), target.gameInstanceId(), target.characterId());
    List<String> responses =
        runCommunicationSequence(
            sessionId, "TELL Sora meet me at the forge", "ERROR COMMUNICATION_NOT_DELIVERED");
    assertThat(responses).hasSizeGreaterThanOrEqualTo(3);
    assertThat(responses).anyMatch(response -> response.startsWith("OK LOGIN"));
    assertThat(responses).anyMatch(response -> response.startsWith("OK PLAY"));
    assertThat(responses)
        .anyMatch(
            response ->
                response.contains(
                    "ERROR COMMUNICATION_NOT_DELIVERED Sender Account identity unavailable"));
    assertThat(socialStub().messageRequests()).isEmpty();
    assertThat(buffers.get(target.tenantId(), target.gameInstanceId(), target.characterId()))
        .isEqualTo(targetBefore);
    GameplayAsyncAssertions.assertMetricEventually(
        gameSession().bean(io.micrometer.core.instrument.MeterRegistry.class),
        COMMAND_WAIT,
        "gamesession.command.tell.invocations",
        1.0);
    GameplayAsyncAssertions.assertMetricEventually(
        gameSession().bean(io.micrometer.core.instrument.MeterRegistry.class),
        COMMAND_WAIT,
        "gamesession.command.tell.failures",
        1.0,
        "error",
        "INVALID_ARGUMENT");
  }

  @Test
  void websocketNumericFriendsReadRefusesBeforeSocial() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    try (GameplayWebSocketDriver client =
        openReadySessionClient(sessionId, "numeric-friend-conn")) {
      client.send("FRIENDS");
      client.awaitContains("ERROR FRIEND_PRESENCE_UNAVAILABLE");
      assertNoFriendRequests();
    }
  }

  @Test
  void websocketNumericOnlineFriendsReadRefusesBeforeSocial() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    try (GameplayWebSocketDriver client =
        openReadySessionClient(sessionId, "numeric-friend-conn")) {
      client.send("FRIENDS ONLINE");
      client.awaitContains("ERROR FRIEND_PRESENCE_UNAVAILABLE");
      assertNoFriendRequests();
    }
  }

  @Test
  void websocketNumericSharedFriendsReadRefusesBeforeSocial() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    try (GameplayWebSocketDriver client =
        openReadySessionClient(sessionId, "numeric-friend-conn")) {
      client.send("FRIENDS SHARED");
      client.awaitContains("ERROR FRIEND_PRESENCE_UNAVAILABLE");
      assertNoFriendRequests();
    }
  }

  @Test
  void freshGameplayBaselineRestoresFixturePresenceWhileNumericPlayerReadRefuses()
      throws Exception {
    ensureTestServicesStarted();
    prepareGameInstance();
    var client =
        gameSession().bean(net.firedevops.firemud.gamesession.client.SocialGroupsClient.class);
    String fixtureAccount = AccountRuntimeStubServer.accountUuidForTestFixture(ACCOUNT_ID);
    socialStub().setFriendPresenceEntries(List.of());
    assertThat(client.listFriendPresence(TENANT_ID, fixtureAccount).getPresencesList()).isEmpty();
    long sessionId = prepareGameInstance();
    var restored = client.listFriendPresence(TENANT_ID, fixtureAccount);
    assertThat(restored.getPresencesCount()).isEqualTo(1);
    assertThat(restored.getPresences(0).getFriendAccountId())
        .isEqualTo(Long.toString(SORA_ACCOUNT_ID));
    assertThat(restored.getPresences(0).getCharacterName()).isEqualTo("Sora");
    var priorPresenceRead = socialStub().lastPresenceRequest();
    List<String> responses =
        runCommunicationSequence(sessionId, "FRIENDS", "ERROR FRIEND_PRESENCE_UNAVAILABLE");
    assertThat(responses)
        .anyMatch(response -> response.contains("ERROR FRIEND_PRESENCE_UNAVAILABLE"));
    assertThat(socialStub().lastPresenceRequest()).isEqualTo(priorPresenceRead);
    assertNoFriendRequests();
  }

  @Test
  void freshGameplayBaselineRestoresAlwaysEnabledTruncateGuards() throws Exception {
    ensureTestServicesStarted();
    prepareGameInstance();
    JdbcTemplate jdbc = STACK.jdbc();
    Map<BaselineTruncateGuard, String> originalStates = new EnumMap<>(BaselineTruncateGuard.class);
    for (BaselineTruncateGuard guard : BaselineTruncateGuard.values()) {
      String originalState = truncateGuardState(jdbc, guard);
      assertThat(originalState).isIn("O", "A");
      originalStates.put(guard, originalState);
    }

    try {
      for (BaselineTruncateGuard guard : BaselineTruncateGuard.values()) {
        setTruncateGuardState(jdbc, guard, "A");
        assertThat(truncateGuardState(jdbc, guard)).isEqualTo("A");
      }

      prepareGameInstance();

      for (BaselineTruncateGuard guard : BaselineTruncateGuard.values()) {
        assertThat(truncateGuardState(jdbc, guard)).isEqualTo("A");
      }
      assertOrdinaryTruncateIsRejected(jdbc, BaselineTruncateGuard.AUTHORED_ACTOR_CORE);
      assertOrdinaryTruncateIsRejected(jdbc, BaselineTruncateGuard.HUMAN_RUNTIME_COMPLETION);
    } finally {
      originalStates.forEach((guard, state) -> setTruncateGuardState(jdbc, guard, state));
    }
    for (BaselineTruncateGuard guard : BaselineTruncateGuard.values()) {
      assertThat(truncateGuardState(jdbc, guard)).isEqualTo(originalStates.get(guard));
    }
  }

  @Test
  void websocketNumericFriendMutationsRefuseBeforeSocial() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    try (GameplayWebSocketDriver client =
        openReadySessionClient(sessionId, "numeric-friend-conn")) {
      client.send("FRIENDS ADD 77");
      client.awaitContains("ERROR FRIEND_ADD_UNAVAILABLE");
      assertNoFriendRequests();
      client.send("FRIENDS REMOVE 77");
      client.awaitContains("ERROR FRIEND_REMOVE_UNAVAILABLE");
      assertNoFriendRequests();
    }
  }

  @Test
  void websocketNumericFriendNameMutationsRefuseBeforeSocial() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    try (GameplayWebSocketDriver client =
        openReadySessionClient(sessionId, "numeric-friend-conn")) {
      client.send("FRIENDS ADD Sora");
      client.awaitContains("ERROR FRIEND_ADD_UNAVAILABLE");
      assertNoFriendRequests();
      client.send("FRIENDS REMOVE Sora");
      client.awaitContains("ERROR FRIEND_REMOVE_UNAVAILABLE");
      assertNoFriendRequests();
    }
  }

  @Test
  void websocketNumericFriendOrdinalRemovalRefusesBeforeSocial() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    try (GameplayWebSocketDriver client =
        openReadySessionClient(sessionId, "numeric-friend-conn")) {
      client.send("FRIENDS REMOVE #1");
      client.awaitContains("ERROR FRIEND_REMOVE_UNAVAILABLE");
      assertNoFriendRequests();
    }
  }

  @Test
  void websocketNumericFriendDetailRefusesBeforeSocial() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    try (GameplayWebSocketDriver client =
        openReadySessionClient(sessionId, "numeric-friend-conn")) {
      client.send("FRIENDS SHOW #1");
      client.awaitContains("ERROR FRIEND_DETAIL_UNAVAILABLE");
      assertNoFriendRequests();
    }
  }

  @Test
  void websocketNumericFriendSummaryRefusesBeforeSocial() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    try (GameplayWebSocketDriver client =
        openReadySessionClient(sessionId, "numeric-friend-conn")) {
      client.send("FRIENDS SUMMARY");
      client.awaitContains("ERROR FRIEND_SUMMARY_UNAVAILABLE");
      assertNoFriendRequests();
    }
  }

  @Test
  void websocketNumericVisibilityReadAndUpdateRefuseBeforeSocial() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    try (GameplayWebSocketDriver client =
        openReadySessionClient(sessionId, "numeric-friend-conn")) {
      client.send("FRIENDS VISIBILITY");
      client.awaitContains("ERROR FRIEND_VISIBILITY_UNAVAILABLE");
      assertNoFriendRequests();
      client.send("FRIENDS VISIBILITY PRIVATE");
      client.awaitContains("ERROR FRIEND_VISIBILITY_UPDATE_UNAVAILABLE");
      assertNoFriendRequests();
    }
  }

  @Test
  void websocketNumericWhisperDoesNotWriteLiveTargetOrObserverViews() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    try (GameplayWebSocketScenarios.ThreePlayerScenario scenario =
        GameplayWebSocketScenarios.openReadyTrio(
            GameplayWebSocketScenarios.proxyGatewayDriverFactory(
                gameSessionWebSocketUrl(), COMMAND_WAIT, TENANT_ID, sessionId),
            "numeric-actor-conn",
            GameplayWebSocketScenarios.demoAdmission("Emberline", READY_LOOK_TEXT),
            "numeric-target-conn",
            GameplayWebSocketScenarios.demoAdmission("sora@example.com", "Sora", READY_LOOK_TEXT),
            "numeric-observer-conn",
            GameplayWebSocketScenarios.demoAdmission("nyx@example.com", "Nyx", READY_LOOK_TEXT))) {
      var contexts = gameSession().bean(SessionContextService.class);
      var target =
          contexts.findByGameplayName(TENANT_ID, DEMO_WORLD_INSTANCE_ID, "Sora").orElseThrow();
      var observer =
          contexts.findByGameplayName(TENANT_ID, DEMO_WORLD_INSTANCE_ID, "Nyx").orElseThrow();
      ScreenBufferService buffers = gameSession().bean(ScreenBufferService.class);
      var targetBefore =
          buffers.get(target.tenantId(), target.gameInstanceId(), target.characterId());
      var observerBefore =
          buffers.get(observer.tenantId(), observer.gameInstanceId(), observer.characterId());
      int cursor = scenario.actor().responseCount();
      scenario.actor().send("WHISPER Sora Keep quiet");
      assertThat(
              scenario
                  .actor()
                  .awaitTranscriptContainingAllAfter(
                      cursor,
                      "ERROR COMMUNICATION_NOT_DELIVERED",
                      "Sender Account identity unavailable"))
          .contains("ERROR COMMUNICATION_NOT_DELIVERED");
      assertThat(socialStub().messageRequests()).isEmpty();
      assertThat(buffers.get(target.tenantId(), target.gameInstanceId(), target.characterId()))
          .isEqualTo(targetBefore);
      assertThat(
              buffers.get(observer.tenantId(), observer.gameInstanceId(), observer.characterId()))
          .isEqualTo(observerBefore);
    }
  }

  @Test
  void websocketNumericTellDoesNotWriteLiveTargetView() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    try (GameplayWebSocketScenarios.TwoPlayerScenario scenario =
        GameplayWebSocketScenarios.openReadyPair(
            GameplayWebSocketScenarios.proxyGatewayDriverFactory(
                gameSessionWebSocketUrl(), COMMAND_WAIT, TENANT_ID, sessionId),
            "numeric-actor-conn",
            GameplayWebSocketScenarios.demoAdmission("Emberline", READY_LOOK_TEXT),
            "numeric-target-conn",
            GameplayWebSocketScenarios.demoAdmission(
                "sora@example.com", "Sora", READY_LOOK_TEXT))) {
      var target =
          gameSession()
              .bean(SessionContextService.class)
              .findByGameplayName(TENANT_ID, DEMO_WORLD_INSTANCE_ID, "Sora")
              .orElseThrow();
      ScreenBufferService buffers = gameSession().bean(ScreenBufferService.class);
      var before = buffers.get(target.tenantId(), target.gameInstanceId(), target.characterId());
      int cursor = scenario.actor().responseCount();
      scenario.actor().send("TELL Sora Meet me at the forge");
      assertThat(
              scenario
                  .actor()
                  .awaitTranscriptContainingAllAfter(
                      cursor,
                      "ERROR COMMUNICATION_NOT_DELIVERED",
                      "Sender Account identity unavailable"))
          .contains("ERROR COMMUNICATION_NOT_DELIVERED");
      assertThat(socialStub().messageRequests()).isEmpty();
      assertThat(buffers.get(target.tenantId(), target.gameInstanceId(), target.characterId()))
          .isEqualTo(before);
    }
  }

  @Test
  void websocketNumericItemMutationsRefuseWithoutChangingInventoryOrContainers() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    try (GameplayWebSocketDriver client = openReadySessionClient(sessionId, "numeric-item-conn")) {
      int cursor = client.responseCount();
      client.send("INV HERE");
      assertThat(
              client.awaitTranscriptContainingAllAfter(
                  cursor, "Room Inventory:", "Torch", "Backpack"))
          .contains("Torch", "Backpack");
      cursor = client.responseCount();
      client.send("INV");
      assertThat(
              client.awaitTranscriptContainingAllAfter(
                  cursor, "Inventory:", "Leather Cap", "Iron Boots"))
          .doesNotContain("Torch");
      for (String command : List.of("GET Torch", "DROP Leather Cap")) {
        cursor = client.responseCount();
        client.send(command);
        assertThat(client.awaitTranscriptContainingAllAfter(cursor, "ERROR INVENTORY_UNAVAILABLE"))
            .contains("ERROR INVENTORY_UNAVAILABLE");
        assertThat(entityStub().lastPickupRequest()).isEmpty();
        assertThat(entityStub().lastDropRequest()).isEmpty();
        assertThat(entityStub().lastPutRequest()).isEmpty();
        assertThat(entityStub().lastTakeRequest()).isEmpty();
      }
      cursor = client.responseCount();
      client.send("INV HERE");
      assertThat(
              client.awaitTranscriptContainingAllAfter(
                  cursor, "Room Inventory:", "Torch", "Backpack"))
          .contains("Torch", "Backpack");
      cursor = client.responseCount();
      client.send("INV");
      assertThat(
              client.awaitTranscriptContainingAllAfter(
                  cursor, "Inventory:", "Leather Cap", "Iron Boots"))
          .doesNotContain("Torch");
      cursor = client.responseCount();
      client.send("CONTAINER Backpack");
      assertThat(client.awaitTranscriptContainingAllAfter(cursor, "Container: Backpack", "Ration"))
          .doesNotContain("Torch");
    }
  }

  @Test
  void websocketEquipmentLoopMovesCarriedItemThroughSlotAndBack() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();

    try (GameplayWebSocketDriver client =
        openReadySessionClient(sessionId, "equipment-loop-conn")) {

      client.send("EQUIPMENT");
      client.awaitContains("You have nothing equipped.");

      client.send("WEAR Leather Cap");
      client.awaitContains("You wear Leather Cap.");
      GameplayEntityAssertions.assertWear(
          entityStub().lastWearRequest(),
          String.valueOf(TENANT_ID),
          ChatTestFixtures.PLAYER_EMBERLINE,
          "leather-cap",
          "cap-carried-1");

      client.send("EQUIPMENT");
      client.awaitContains("- HEAD: Leather Cap [cap#1] (A small cap)");

      client.send("REMOVE HEAD");
      client.awaitContains("You remove Leather Cap.");
      GameplayEntityAssertions.assertRemove(
          entityStub().lastRemoveRequest(),
          String.valueOf(TENANT_ID),
          ChatTestFixtures.PLAYER_EMBERLINE,
          "HEAD");

      client.send("EQUIPMENT");
      client.awaitContains("You have nothing equipped.");

      client.send("WEAR Iron Boots");
      client.awaitContains(
          "ERROR SLOT_INCOMPATIBLE Iron Boots cannot be worn by this body layout.");
      GameplayEntityAssertions.assertWear(
          entityStub().lastWearRequest(),
          String.valueOf(TENANT_ID),
          ChatTestFixtures.PLAYER_EMBERLINE,
          "iron-boots",
          "boots-carried-1");
    }
  }

  private static void assertNoFriendRequests() {
    assertThat(socialStub().lastFriendsRequest()).isEmpty();
    assertThat(socialStub().lastAddFriendRequest()).isEmpty();
    assertThat(socialStub().lastRemoveFriendRequest()).isEmpty();
    assertThat(socialStub().lastGetFriendRequest()).isEmpty();
    assertThat(socialStub().lastGetFriendByOrdinalRequest()).isEmpty();
    assertThat(socialStub().lastRemoveFriendByOrdinalRequest()).isEmpty();
    assertThat(socialStub().lastSummaryRequest()).isEmpty();
    assertThat(socialStub().lastGetVisibilityRequest()).isEmpty();
    assertThat(socialStub().lastUpdateVisibilityRequest()).isEmpty();
  }

  private static synchronized void ensureTestServicesStarted() throws Exception {
    if (STACK == null) {
      STACK =
          GameplayCrossServiceStack.defaultDemoBuilder(POSTGRES, REDIS, ACCOUNT_ID)
              .mapAccountId("sora@example.com", SORA_ACCOUNT_ID)
              .mapAccountId("nyx@example.com", NYX_ACCOUNT_ID)
              .withInitialRoomEntities(ChatTestFixtures.sampleEntities())
              .withSocialEnabled(true)
              .withInitialFriendPresenceResponse(
                  net.firedevops.firemud.socialgroups.v1.ListFriendPresenceResponse.newBuilder()
                      .addPresences(
                          FriendPresenceEntry.newBuilder()
                              .setFriendAccountId(Long.toString(SORA_ACCOUNT_ID))
                              .setOnline(true)
                              .setCharacterId(ChatTestFixtures.PLAYER_SORA)
                              .setCharacterName("Sora")
                              .setPlayableStateScope(
                                  net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                                      .PLAYABLE_STATE_SCOPE_SHARED)
                              .setWorldSlug("demo")
                              .setWorldDisplayName("Demo World")
                              .setRealmSlug("production")
                              .setRealmDisplayName("Live Realm")
                              .setActivityState(
                                  FriendPresenceActivityState
                                      .FRIEND_PRESENCE_ACTIVITY_STATE_AUTO_AFK)
                              .build())
                      .build())
              .start();
    }
  }

  private long prepareGameInstance() {
    return STACK.freshGameplayBaseline(
        TENANT_ID,
        DEMO_WORLD_INSTANCE_ID,
        ACCOUNT_ID,
        7L,
        ChatTestFixtures.EMBERLINE_CHARACTER_ID,
        ChatTestFixtures.SORA_CHARACTER_ID,
        ChatTestFixtures.NYX_CHARACTER_ID);
  }

  private static String truncateGuardState(JdbcTemplate jdbc, BaselineTruncateGuard guard) {
    List<String> states =
        jdbc.query(
            """
            SELECT tgenabled::text
            FROM pg_trigger
            WHERE tgrelid = CAST(? AS regclass)
              AND tgname = CAST(? AS name)
              AND NOT tgisinternal
            """,
            (row, rowNumber) -> row.getString(1),
            guard.tableName,
            guard.triggerName);
    assertThat(states).hasSize(1);
    return states.getFirst();
  }

  private static void setTruncateGuardState(
      JdbcTemplate jdbc, BaselineTruncateGuard guard, String state) {
    String enableMode =
        switch (state) {
          case "O" -> "ENABLE";
          case "A" -> "ENABLE ALWAYS";
          default -> throw new IllegalArgumentException("Unsupported enabled trigger state");
        };
    jdbc.execute(
        "ALTER TABLE " + guard.tableName + " " + enableMode + " TRIGGER " + guard.triggerName);
  }

  private static void assertOrdinaryTruncateIsRejected(
      JdbcTemplate jdbc, BaselineTruncateGuard guard) {
    Throwable failure = catchThrowable(() -> jdbc.execute("TRUNCATE TABLE " + guard.tableName));
    assertThat(failure).isNotNull();
    SQLException sqlException = mostSpecificSqlException(failure);
    assertThat((Throwable) sqlException).isNotNull();
    assertThat(sqlException.getSQLState()).isEqualTo("23514");
  }

  private static SQLException mostSpecificSqlException(Throwable failure) {
    SQLException result = null;
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException) {
        result = sqlException;
      }
    }
    return result;
  }

  private void seedLiveTargetSession() {
    STACK.seedLiveSession(
        ChatTestFixtures.soraRecipientSession(90210L, TENANT_ID, DEMO_WORLD_INSTANCE_ID));
  }

  private List<String> runCommunicationSequence(
      long sessionId, String commandText, String expectedResponseSubstring) throws Exception {
    try (GameplayWebSocketDriver client =
        openReadySessionClient(sessionId, "flow-" + commandText.hashCode())) {
      client.send(commandText);
      client.awaitContains(expectedResponseSubstring);
      return client.responses();
    }
  }

  private GameplayWebSocketDriver openReadySessionClient(long sessionId, String proxyConnectionId)
      throws Exception {
    return GameplayWebSocketScenarios.openReady(
        GameplayWebSocketScenarios.proxyGatewayDriverFactory(
            gameSessionWebSocketUrl(), COMMAND_WAIT, TENANT_ID, sessionId),
        proxyConnectionId,
        READY_LOOK_TEXT);
  }

  private URI gameSessionWebSocketUrl() {
    return URI.create("ws://localhost:" + gameSession().port() + "/ws/game");
  }

  private static CrossServiceAppHarness.GameSessionHolder gameSession() {
    return STACK.gameSession();
  }

  private static net.firedevops.firemud.gamesession.test.stubs.SocialGroupsStubServer socialStub() {
    return STACK.socialStub();
  }

  private static net.firedevops.firemud.gamesession.test.stubs.EntityManagementStubServer
      entityStub() {
    return STACK.entityStub();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "not-a-valid-context", "incomplete.context"})
  void unqualifiedFirstPartyTransportDeniesBeforeCommunicationFriendsOrInventoryEffects(
      String context) throws Exception {
    ensureTestServicesStarted();
    prepareGameInstance();
    var priorChat = socialStub().lastRequest();
    var priorFriends = socialStub().lastSummaryRequest();
    var priorFriendMutation = socialStub().lastRemoveFriendByOrdinalRequest();
    var priorVisibility = socialStub().lastUpdateVisibilityRequest();
    var priorPickup = entityStub().lastPickupRequest();
    var priorWear = entityStub().lastWearRequest();
    var priorRemove = entityStub().lastRemoveRequest();
    var sessions =
        gameSession().bean(net.firedevops.firemud.gamesession.service.SessionContextService.class);
    assertThat(sessions.findBySessionId(91001L)).isEmpty();
    try (GameplayWebSocketDriver client =
        GameplayWebSocketDriver.connect(
            gameSessionWebSocketUrl(),
            COMMAND_WAIT,
            Map.of(
                "X-Firemud-Connection-Mode",
                "first_party_web",
                "X-Firemud-Transport-Session-Id",
                "91001",
                "X-Firemud-Connect-Context",
                context))) {
      var close = client.awaitClosed();
      assertThat(close.statusCode()).isEqualTo(1008);
      assertThat(close.reason()).isEqualTo("CONNECT_CONTEXT_INVALID");
      assertThat(client.responses()).isEmpty();
    }
    assertThat(sessions.findBySessionId(91001L)).isEmpty();
    assertThat(socialStub().lastRequest()).isEqualTo(priorChat);
    assertThat(socialStub().lastSummaryRequest()).isEqualTo(priorFriends);
    assertThat(socialStub().lastRemoveFriendByOrdinalRequest()).isEqualTo(priorFriendMutation);
    assertThat(socialStub().lastUpdateVisibilityRequest()).isEqualTo(priorVisibility);
    assertThat(entityStub().lastPickupRequest()).isEqualTo(priorPickup);
    assertThat(entityStub().lastWearRequest()).isEqualTo(priorWear);
    assertThat(entityStub().lastRemoveRequest()).isEqualTo(priorRemove);
  }
}
