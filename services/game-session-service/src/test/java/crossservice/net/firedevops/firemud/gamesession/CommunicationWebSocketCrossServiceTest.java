package net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.List;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CommunicationWebSocketCrossServiceTest {
  private static final Duration COMMAND_WAIT = Duration.ofSeconds(45);
  private static final long TENANT_ID = 1L;
  private static final String ACCOUNT_UUID = ChatTestFixtures.ACCOUNT_UUID_EMBERLINE;
  private static final String SORA_ACCOUNT_UUID = ChatTestFixtures.ACCOUNT_UUID_SORA;
  private static final String NYX_ACCOUNT_UUID = ChatTestFixtures.ACCOUNT_UUID_NYX;
  private static final long EMBERLINE_CHARACTER_ID =
      Long.parseLong(ChatTestFixtures.PLAYER_EMBERLINE);
  private static final long SORA_CHARACTER_ID = Long.parseLong(ChatTestFixtures.PLAYER_SORA);
  private static final long NYX_CHARACTER_ID = Long.parseLong(ChatTestFixtures.PLAYER_NYX);
  private static final long MANAGEMENT_OWNER_SELECTOR = EMBERLINE_CHARACTER_ID;
  private static final long DEMO_WORLD_INSTANCE_ID = 1L;
  private static final String READY_LOOK_TEXT = "Candle-lit Antechamber";
  private static final String SORA_EMAIL = "sora@example.com";
  private static final String NYX_EMAIL = "nyx@example.com";

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
    assertThat(entityStub().lastListCharactersByAccountRequest())
        .hasValueSatisfying(
            request -> {
              assertThat(request.getTenantId()).isEqualTo(Long.toString(TENANT_ID));
              assertThat(request.getAccountId()).isEqualTo(ACCOUNT_UUID);
              assertThat(request.getGameInstanceId())
                  .isEqualTo(Long.toString(DEMO_WORLD_INSTANCE_ID));
              assertThat(request.getPlayableStateScope())
                  .isEqualTo(
                      net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                          .PLAYABLE_STATE_SCOPE_SHARED);
            });
    assertThat(socialStub().lastRequest())
        .hasValueSatisfying(
            request -> {
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
  void websocketPlayDeniesBeforeReadingEntityRosterWhenAccountAdmissionIsDenied() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    STACK.accountStub().denyGameplayAdmission();

    try (GameplayWebSocketScenarios.LoginThenPlayScenario scenario =
        GameplayWebSocketScenarios.loginThenAttemptPlay(
            GameplayWebSocketScenarios.proxyGatewayDriverFactory(
                gameSessionWebSocketUrl(), COMMAND_WAIT, TENANT_ID, sessionId),
            "account-denied-play-" + sessionId,
            GameplayWebSocketScenarios.demoAdmission(READY_LOOK_TEXT),
            client ->
                client.awaitMatching(
                    response -> response.startsWith("ERROR JOIN_REQUIRED"),
                    "Account admission denial before Entity roster lookup"))) {
      assertThat(scenario.driver().responses())
          .anyMatch(response -> response.startsWith("ERROR JOIN_REQUIRED"));
    }

    assertThat(entityStub().lastListCharactersByAccountRequest()).isEmpty();
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
            namedAdmission(SORA_EMAIL, "Sora"))) {
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
  void websocketFriendsDetailRedactsMissingPolicyPresenceWithoutDisconnecting() throws Exception {
    ensureTestServicesStarted();
    FriendPresenceEntry missingPolicyPresence =
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
            .setActivityState(FriendPresenceActivityState.FRIEND_PRESENCE_ACTIVITY_STATE_AUTO_AFK)
            .build();
    long sessionId = prepareGameInstance();
    socialStub().setFriendPresenceEntries(List.of(missingPolicyPresence));
    try (GameplayWebSocketDriver client =
        openReadySessionClient(sessionId, "friends-missing-policy-detail")) {
      int baseline = client.responses().size();
      client.send("FRIENDS SHOW #1");
      client.awaitContains("Presence: presence unavailable");
      assertThat(client.responses().subList(baseline, client.responses().size()))
          .noneMatch(
              response ->
                  response.contains("Sora")
                      || response.contains("Demo World")
                      || response.contains("Live Realm"));
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
            GameplayWebSocketScenarios.demoAdmission(READY_LOOK_TEXT),
            "target-conn",
            namedAdmission(SORA_EMAIL, "Sora"),
            "observer-conn",
            namedAdmission(NYX_EMAIL, "Nyx"))) {
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
            namedAdmission(SORA_EMAIL, "Sora"))) {
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
      client.send("INV HERE");
      client.awaitContains("Room Inventory:");
      client.awaitContains("- Torch [torch#1] (A small torch)");
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
          GameplayCrossServiceStack.defaultDemoBuilder(POSTGRES, REDIS, ACCOUNT_UUID)
              .mapAccountUuid("sora@example.com", SORA_ACCOUNT_UUID)
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
                              .setVisibilityPolicy(
                                  FriendPresenceVisibilityPolicy
                                      .FRIEND_PRESENCE_VISIBILITY_POLICY_FRIENDS_ONLY)
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
    long sessionId =
        STACK.freshGameplayBaseline(
            TENANT_ID,
            DEMO_WORLD_INSTANCE_ID,
            MANAGEMENT_OWNER_SELECTOR,
            7L,
            EMBERLINE_CHARACTER_ID,
            SORA_CHARACTER_ID,
            NYX_CHARACTER_ID);
    entityStub().resetCharacterRosterState();
    STACK.accountStub().mapAccountUuid(SORA_EMAIL, SORA_ACCOUNT_UUID);
    STACK.accountStub().mapAccountUuid(NYX_EMAIL, NYX_ACCOUNT_UUID);
    return sessionId;
  }

  private static GameplayWebSocketScenarios.Admission namedAdmission(
      String email, String characterName) {
    return GameplayWebSocketScenarios.Admission.named(
        email,
        GameplayWebSocketScenarios.DEMO_PASSWORD,
        GameplayWebSocketScenarios.DEMO_WORLD,
        characterName,
        READY_LOOK_TEXT);
  }

  private void seedLiveTargetSession() {
    STACK.seedLiveSession(
        90210L,
        TENANT_ID,
        ChatTestFixtures.characterByName("Sora").getAccountId(),
        "sora@example.com",
        SORA_CHARACTER_ID,
        "Sora",
        DEMO_WORLD_INSTANCE_ID,
        ChatTestFixtures.ROOM_ID,
        "target-jwt",
        "demo",
        "production",
        1L,
        "SHARED");
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
}
