package net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.net.URI;
import java.sql.SQLException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.gamesession.test.ChatTestFixtures;
import net.firedevops.firemud.gamesession.testsupport.GameplayAsyncAssertions;
import net.firedevops.firemud.gamesession.testsupport.GameplayCrossServiceStack;
import net.firedevops.firemud.gamesession.testsupport.GameplayEntityAssertions;
import net.firedevops.firemud.gamesession.testsupport.GameplaySocialAssertions;
import net.firedevops.firemud.gamesession.testsupport.GameplayWebSocketDriver;
import net.firedevops.firemud.gamesession.testsupport.GameplayWebSocketScenarios;
import net.firedevops.firemud.socialgroups.v1.ChatType;
import net.firedevops.firemud.socialgroups.v1.FriendPresenceActivityState;
import net.firedevops.firemud.socialgroups.v1.FriendPresenceEntry;
import net.firedevops.firemud.socialgroups.v1.FriendPresenceVisibilityPolicy;
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
  private static final String ACCOUNT_UUID =
      AccountRuntimeStubServer.accountUuidForTestFixture(ACCOUNT_ID);
  private static final String SORA_ACCOUNT_UUID =
      AccountRuntimeStubServer.accountUuidForTestFixture(SORA_ACCOUNT_ID);
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
  void websocketSayFlowReportsCanonicalTranscriptAndMetrics() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    List<String> responses =
        runCommunicationSequence(
            sessionId, "SAY hello travelers", ChatTestFixtures.canonicalSayText());

    assertThat(responses).hasSizeGreaterThanOrEqualTo(3);
    assertThat(responses).anyMatch(response -> response.startsWith("OK LOGIN"));
    assertThat(responses).anyMatch(response -> response.startsWith("OK PLAY"));
    assertThat(responses)
        .anyMatch(response -> response.contains(ChatTestFixtures.canonicalSayText()));
    assertThat(socialStub().lastRequest())
        .hasValueSatisfying(
            request -> {
              assertThat(request.getSenderId()).isEqualTo(ACCOUNT_UUID);
              assertThat(request.getContent()).isEqualTo("hello travelers");
              assertThat(request.getType())
                  .isEqualTo(net.firedevops.firemud.socialgroups.v1.ChatType.CHAT_TYPE_SAY);
              assertThat(request.getEffectId()).isNotBlank();
            });

    GameplayAsyncAssertions.assertMetricEventually(
        gameSession().bean(io.micrometer.core.instrument.MeterRegistry.class),
        COMMAND_WAIT,
        "gamesession.command.say.invocations",
        1.0);
  }

  @Test
  void websocketSayPushesRoomListenerViewToLiveRecipient() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();

    try (GameplayWebSocketScenarios.TwoPlayerScenario scenario =
        GameplayWebSocketScenarios.openReadyPair(
            GameplayWebSocketScenarios.proxyGatewayDriverFactory(
                gameSessionWebSocketUrl(), COMMAND_WAIT, TENANT_ID, sessionId),
            "actor-say-conn",
            GameplayWebSocketScenarios.demoAdmission("Emberline", READY_LOOK_TEXT),
            "target-say-conn",
            GameplayWebSocketScenarios.demoAdmission(
                "sora@example.com", "Sora", READY_LOOK_TEXT))) {
      scenario.actor().send("SAY hello travelers");
      scenario.actor().awaitContains(ChatTestFixtures.canonicalSayText());
      scenario.target().awaitContains(ChatTestFixtures.canonicalSayListenerText());
    }
  }

  @Test
  void websocketWhisperFlowReportsCanonicalTranscriptAndMetadata() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    List<String> responses =
        runCommunicationSequence(
            sessionId, "WHISPER Sora keep quiet", ChatTestFixtures.canonicalWhisperText());

    assertThat(responses).hasSizeGreaterThanOrEqualTo(3);
    assertThat(responses)
        .anyMatch(response -> response.contains(ChatTestFixtures.canonicalWhisperText()));
    GameplayEntityAssertions.assertMessage(
        socialStub().lastRequest(),
        ChatType.CHAT_TYPE_WHISPER,
        ChatTestFixtures.PLAYER_SORA,
        "keep quiet",
        true);
    assertThat(socialStub().lastRequest())
        .hasValueSatisfying(request -> assertThat(request.getSenderId()).isEqualTo(ACCOUNT_UUID));

    GameplayAsyncAssertions.assertMetricEventually(
        gameSession().bean(io.micrometer.core.instrument.MeterRegistry.class),
        COMMAND_WAIT,
        "gamesession.command.whisper.invocations",
        1.0);
  }

  @Test
  void websocketTellFlowReportsCanonicalTranscriptAndMetadata() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    seedLiveTargetSession();
    List<String> responses =
        runCommunicationSequence(
            sessionId, "TELL Sora meet me at the forge", ChatTestFixtures.canonicalTellText());

    assertThat(responses).hasSizeGreaterThanOrEqualTo(3);
    assertThat(responses)
        .anyMatch(response -> response.contains(ChatTestFixtures.canonicalTellText()));
    GameplayEntityAssertions.assertMessage(
        socialStub().lastRequest(),
        ChatType.CHAT_TYPE_TELL,
        ChatTestFixtures.PLAYER_SORA,
        "meet me at the forge",
        true);
    assertThat(socialStub().lastRequest())
        .hasValueSatisfying(request -> assertThat(request.getSenderId()).isEqualTo(ACCOUNT_UUID));

    GameplayAsyncAssertions.assertMetricEventually(
        gameSession().bean(io.micrometer.core.instrument.MeterRegistry.class),
        COMMAND_WAIT,
        "gamesession.command.tell.invocations",
        1.0);
  }

  @Test
  void websocketFriendsShowsCanonicalCrossGamePresence() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();

    List<String> responses =
        runCommunicationSequence(
            sessionId,
            "FRIENDS",
            "Sora [acct #" + SORA_ACCOUNT_UUID + "] - online in Demo World / Live Realm (idle)");

    assertThat(responses).hasSizeGreaterThanOrEqualTo(3);
    assertThat(responses)
        .anyMatch(
            response ->
                response.contains(
                    "Sora [acct #"
                        + SORA_ACCOUNT_UUID
                        + "] - online in Demo World / Live Realm (idle)"));
    GameplaySocialAssertions.assertListFriendsRequest(
        socialStub().lastFriendsRequest(), Long.toString(TENANT_ID), ACCOUNT_UUID);
  }

  @Test
  void websocketFriendsOnlineFiltersCanonicalRoster() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();

    List<String> responses =
        runCommunicationSequence(
            sessionId,
            "FRIENDS ONLINE",
            "Friends ONLINE [1/1]:\n"
                + "1) Sora [acct #"
                + SORA_ACCOUNT_UUID
                + "] - online in Demo World / Live Realm (idle)");

    assertThat(responses)
        .anyMatch(
            response ->
                response.contains("Friends ONLINE [1/1]:")
                    && response.contains(
                        "1) Sora [acct #"
                            + SORA_ACCOUNT_UUID
                            + "] - online in Demo World / Live Realm (idle)"));
    GameplaySocialAssertions.assertListFriendsRequest(
        socialStub().lastFriendsRequest(),
        Long.toString(TENANT_ID),
        ACCOUNT_UUID,
        net.firedevops.firemud.socialgroups.v1.FriendRosterFilter.FRIEND_ROSTER_FILTER_ONLINE);
  }

  @Test
  void websocketFriendsSharedFiltersCanonicalRoster() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();

    List<String> responses =
        runCommunicationSequence(
            sessionId,
            "FRIENDS SHARED",
            "Friends SHARED [1/1]:\n"
                + "1) Sora [acct #"
                + SORA_ACCOUNT_UUID
                + "] - online in Demo World / Live Realm (idle)");

    assertThat(responses)
        .anyMatch(
            response ->
                response.contains("Friends SHARED [1/1]:")
                    && response.contains(
                        "1) Sora [acct #"
                            + SORA_ACCOUNT_UUID
                            + "] - online in Demo World / Live Realm (idle)"));
    GameplaySocialAssertions.assertListFriendsRequest(
        socialStub().lastFriendsRequest(),
        Long.toString(TENANT_ID),
        ACCOUNT_UUID,
        net.firedevops.firemud.socialgroups.v1.FriendRosterFilter.FRIEND_ROSTER_FILTER_SHARED);
  }

  @Test
  void freshGameplayBaselineReappliesConfiguredFriendPresenceBaseline() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    socialStub().setFriendPresenceEntries(List.of());

    sessionId = prepareGameInstance();
    List<String> responses =
        runCommunicationSequence(
            sessionId,
            "FRIENDS",
            "Sora [acct #" + SORA_ACCOUNT_UUID + "] - online in Demo World / Live Realm (idle)");

    assertThat(responses)
        .anyMatch(
            response ->
                response.contains(
                    "Sora [acct #"
                        + SORA_ACCOUNT_UUID
                        + "] - online in Demo World / Live Realm (idle)"));
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
  void websocketFriendsMutationFlowUsesCanonicalRosterSurface() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();

    try (GameplayWebSocketDriver client =
        openReadySessionClient(sessionId, "friends-mutate-conn")) {
      client.send("FRIENDS ADD " + SORA_ACCOUNT_UUID);
      client.awaitContains("Friend #" + SORA_ACCOUNT_UUID + " added.");
      client.send("FRIENDS REMOVE " + SORA_ACCOUNT_UUID);
      client.awaitContains("Friend #" + SORA_ACCOUNT_UUID + " removed.");
    }

    GameplaySocialAssertions.assertAddFriendRequest(
        socialStub().lastAddFriendRequest(),
        Long.toString(TENANT_ID),
        ACCOUNT_UUID,
        SORA_ACCOUNT_UUID);
    GameplaySocialAssertions.assertRemoveFriendRequest(
        socialStub().lastRemoveFriendRequest(),
        Long.toString(TENANT_ID),
        ACCOUNT_UUID,
        SORA_ACCOUNT_UUID);
  }

  @Test
  void websocketFriendsMutationByCharacterNameUsesCanonicalIdentityLookup() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();

    try (GameplayWebSocketDriver client =
        openReadySessionClient(sessionId, "friends-mutate-name-conn")) {
      client.send("FRIENDS ADD Sora");
      client.awaitContains("Sora [acct #" + SORA_ACCOUNT_UUID + "] added.");
      client.send("FRIENDS REMOVE Sora");
      client.awaitContains("Sora [acct #" + SORA_ACCOUNT_UUID + "] removed.");
    }

    GameplaySocialAssertions.assertAddFriendRequest(
        socialStub().lastAddFriendRequest(),
        Long.toString(TENANT_ID),
        ACCOUNT_UUID,
        SORA_ACCOUNT_UUID);
    GameplaySocialAssertions.assertRemoveFriendRequest(
        socialStub().lastRemoveFriendRequest(),
        Long.toString(TENANT_ID),
        ACCOUNT_UUID,
        SORA_ACCOUNT_UUID);
  }

  @Test
  void websocketFriendsRemoveByRosterOrdinalUsesRenderedRosterIdentity() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();

    try (GameplayWebSocketDriver client =
        openReadySessionClient(sessionId, "friends-remove-ordinal-conn")) {
      client.send("FRIENDS REMOVE #1");
      client.awaitContains("Sora [acct #" + SORA_ACCOUNT_UUID + "] removed.");
    }

    GameplaySocialAssertions.assertRemoveFriendByOrdinalRequest(
        socialStub().lastRemoveFriendByOrdinalRequest(), Long.toString(TENANT_ID), ACCOUNT_UUID, 1);
  }

  @Test
  void websocketFriendsShowUsesCanonicalFriendDetailSurface() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();

    try (GameplayWebSocketDriver client =
        openReadySessionClient(sessionId, "friends-show-detail-conn")) {
      client.send("FRIENDS SHOW #1");
      client.awaitContains("Friend Sora [acct #" + SORA_ACCOUNT_UUID + "]");
      client.awaitContains("Presence: online in Demo World / Live Realm (idle)");
      client.awaitContains("Roster entry: #1");
    }

    GameplaySocialAssertions.assertGetFriendByOrdinalRequest(
        socialStub().lastGetFriendByOrdinalRequest(), Long.toString(TENANT_ID), ACCOUNT_UUID, 1);
  }

  @Test
  void websocketFriendsSummaryUsesCanonicalRosterSummarySurface() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();

    try (GameplayWebSocketDriver client =
        openReadySessionClient(sessionId, "friends-summary-conn")) {
      client.send("FRIENDS SUMMARY");
      client.awaitContains("Friend roster summary:");
      client.awaitContains("Linked: 1");
      client.awaitContains("Online: 1");
      client.awaitContains("Offline: 0");
      client.awaitContains("Recent offline: 0");
    }

    GameplaySocialAssertions.assertFriendRosterSummaryRequest(
        socialStub().lastSummaryRequest(), Long.toString(TENANT_ID), ACCOUNT_UUID);
  }

  @Test
  void websocketFriendsVisibilityShowsCurrentPolicy() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();

    try (GameplayWebSocketDriver client =
        openReadySessionClient(sessionId, "friends-visibility-conn")) {
      client.send("FRIENDS VISIBILITY");
      client.awaitContains("Friend presence visibility: FRIENDS_ONLY");
      client.send("FRIENDS VISIBILITY PRIVATE");
      client.awaitContains("Friend presence visibility set to PRIVATE.");
      client.awaitContains("Friend presence visibility: PRIVATE");
    }

    GameplaySocialAssertions.assertGetVisibilityRequest(
        socialStub().lastGetVisibilityRequest(), Long.toString(TENANT_ID), ACCOUNT_UUID);
    GameplaySocialAssertions.assertUpdateVisibilityRequest(
        socialStub().lastUpdateVisibilityRequest(),
        Long.toString(TENANT_ID),
        ACCOUNT_UUID,
        FriendPresenceVisibilityPolicy.FRIEND_PRESENCE_VISIBILITY_POLICY_PRIVATE);
  }

  @Test
  void websocketWhisperPushesTargetAndObserverViewsToLiveRecipients() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();

    try (GameplayWebSocketScenarios.ThreePlayerScenario scenario =
        GameplayWebSocketScenarios.openReadyTrio(
            GameplayWebSocketScenarios.proxyGatewayDriverFactory(
                gameSessionWebSocketUrl(), COMMAND_WAIT, TENANT_ID, sessionId),
            "actor-conn",
            GameplayWebSocketScenarios.demoAdmission("Emberline", READY_LOOK_TEXT),
            "target-conn",
            GameplayWebSocketScenarios.demoAdmission("sora@example.com", "Sora", READY_LOOK_TEXT),
            "observer-conn",
            GameplayWebSocketScenarios.demoAdmission("nyx@example.com", "Nyx", READY_LOOK_TEXT))) {
      scenario.actor().send("WHISPER Sora Keep quiet");
      scenario.actor().awaitContains(ChatTestFixtures.canonicalWhisperText());
      scenario.target().awaitContains(ChatTestFixtures.canonicalWhisperTargetText());
      scenario.observer().awaitContains(ChatTestFixtures.canonicalWhisperObserverMetadataText());
    }
  }

  @Test
  void websocketTellPushesTargetViewToLiveRecipient() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();

    try (GameplayWebSocketScenarios.TwoPlayerScenario scenario =
        GameplayWebSocketScenarios.openReadyPair(
            GameplayWebSocketScenarios.proxyGatewayDriverFactory(
                gameSessionWebSocketUrl(), COMMAND_WAIT, TENANT_ID, sessionId),
            "actor-tell-conn",
            GameplayWebSocketScenarios.demoAdmission("Emberline", READY_LOOK_TEXT),
            "target-tell-conn",
            GameplayWebSocketScenarios.demoAdmission(
                "sora@example.com", "Sora", READY_LOOK_TEXT))) {
      scenario.actor().send("TELL Sora Meet me at the forge");
      scenario.actor().awaitContains(ChatTestFixtures.canonicalTellText());
      scenario.target().awaitContains(ChatTestFixtures.canonicalTellTargetText());
    }
  }

  @Test
  void websocketItemLoopMovesRoomItemThroughInventoryAndBack() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();

    try (GameplayWebSocketDriver client = openReadySessionClient(sessionId, "item-loop-conn")) {

      client.send("INV HERE");
      client.awaitContains("Room Inventory:");
      client.awaitContains("- Torch [torch#1] (A small torch)");
      client.awaitContains("- Backpack [backpack#1] (A weathered backpack)");

      int pickupResponseCount = client.responseCount();
      client.send("GET Torch");
      assertThat(
              client.awaitTranscriptContainingAllAfter(
                  pickupResponseCount,
                  "You pick up Torch.",
                  "Inventory:",
                  "- Torch [torch#1] (A small torch)",
                  "Room Inventory:",
                  "- Backpack [backpack#1] (A weathered backpack)"))
          .contains("Room Inventory:");
      GameplayEntityAssertions.assertPickup(
          entityStub().lastPickupRequest(),
          String.valueOf(TENANT_ID),
          ChatTestFixtures.PLAYER_EMBERLINE,
          String.valueOf(DEMO_WORLD_INSTANCE_ID),
          ChatTestFixtures.ROOM_ID,
          "torch");

      int containerResponseCount = client.responseCount();
      client.send("CONTAINER Backpack");
      client.awaitTranscriptContainingAllAfter(
          containerResponseCount,
          "Container: Backpack [backpack#1]",
          "- Ration [ration#1] (A dry trail ration)");

      int putResponseCount = client.responseCount();
      client.send("PUT Torch INTO Backpack");
      client.awaitTranscriptContainingAllAfter(
          putResponseCount, "You put Torch into Backpack.", "- Torch [torch#1] (A small torch)");
      GameplayEntityAssertions.assertPut(
          entityStub().lastPutRequest(),
          String.valueOf(TENANT_ID),
          ChatTestFixtures.PLAYER_EMBERLINE,
          "container-backpack-1",
          "torch",
          "torch-ground-1");

      int takeResponseCount = client.responseCount();
      client.send("TAKE Torch FROM Backpack");
      client.awaitTranscriptContainingAllAfter(
          takeResponseCount,
          "You take Torch from Backpack.",
          "- Ration [ration#1] (A dry trail ration)");
      GameplayEntityAssertions.assertTake(
          entityStub().lastTakeRequest(),
          String.valueOf(TENANT_ID),
          ChatTestFixtures.PLAYER_EMBERLINE,
          "container-backpack-1",
          "torch",
          "");

      int dropResponseCount = client.responseCount();
      client.send("DROP Torch");
      assertThat(
              client.awaitTranscriptContainingAllAfter(
                  dropResponseCount,
                  "You drop Torch.",
                  "Inventory:",
                  "- Leather Cap [cap#1] (A small cap)",
                  "- Iron Boots [boots#1] (Heavy iron boots)",
                  "Room Inventory:",
                  "- Torch [torch#1] (A small torch)"))
          .contains("Room Inventory:");
      int finalInventoryCount = client.responseCount();
      client.send("INV HERE");
      client.awaitTranscriptContainingAllAfter(
          finalInventoryCount, "Room Inventory:", "- Torch [torch#1] (A small torch)");
      GameplayEntityAssertions.assertDrop(
          entityStub().lastDropRequest(),
          String.valueOf(TENANT_ID),
          ChatTestFixtures.PLAYER_EMBERLINE,
          String.valueOf(DEMO_WORLD_INSTANCE_ID),
          ChatTestFixtures.ROOM_ID,
          "torch");
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
                              .setFriendAccountId(SORA_ACCOUNT_UUID)
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
