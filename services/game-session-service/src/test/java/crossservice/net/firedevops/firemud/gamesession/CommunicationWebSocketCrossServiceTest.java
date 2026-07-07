package net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.gamesession.test.ChatTestFixtures;
import net.firedevops.firemud.gamesession.testsupport.GameplayAsyncAssertions;
import net.firedevops.firemud.gamesession.testsupport.GameplayCrossServiceStack;
import net.firedevops.firemud.gamesession.testsupport.GameplayEntityAssertions;
import net.firedevops.firemud.gamesession.testsupport.GameplaySocialAssertions;
import net.firedevops.firemud.gamesession.testsupport.GameplayStructuredCommandAssertions;
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
  private static final Duration COMMAND_WAIT = Duration.ofSeconds(10);
  private static final long TENANT_ID = 1L;
  private static final long ACCOUNT_ID = Long.parseLong(ChatTestFixtures.PLAYER_EMBERLINE);
  private static final long SORA_ACCOUNT_ID = Long.parseLong(ChatTestFixtures.PLAYER_SORA);
  private static final long DEMO_WORLD_INSTANCE_ID = 1L;
  private static final String READY_LOOK_TEXT = "Candle-lit Antechamber";
  private static final String FIRST_PARTY_CONNECT_SECRET = "cross-service-connect-context-secret";

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
            "Sora [acct #" + SORA_ACCOUNT_ID + "] - online in Demo World / Live Realm (idle)");

    assertThat(responses).hasSizeGreaterThanOrEqualTo(3);
    assertThat(responses)
        .anyMatch(
            response ->
                response.contains(
                    "Sora [acct #"
                        + SORA_ACCOUNT_ID
                        + "] - online in Demo World / Live Realm (idle)"));
    GameplaySocialAssertions.assertListFriendsRequest(
        socialStub().lastFriendsRequest(), Long.toString(TENANT_ID), Long.toString(ACCOUNT_ID));
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
                + SORA_ACCOUNT_ID
                + "] - online in Demo World / Live Realm (idle)");

    assertThat(responses)
        .anyMatch(
            response ->
                response.contains("Friends ONLINE [1/1]:")
                    && response.contains(
                        "1) Sora [acct #"
                            + SORA_ACCOUNT_ID
                            + "] - online in Demo World / Live Realm (idle)"));
    GameplaySocialAssertions.assertListFriendsRequest(
        socialStub().lastFriendsRequest(),
        Long.toString(TENANT_ID),
        Long.toString(ACCOUNT_ID),
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
                + SORA_ACCOUNT_ID
                + "] - online in Demo World / Live Realm (idle)");

    assertThat(responses)
        .anyMatch(
            response ->
                response.contains("Friends SHARED [1/1]:")
                    && response.contains(
                        "1) Sora [acct #"
                            + SORA_ACCOUNT_ID
                            + "] - online in Demo World / Live Realm (idle)"));
    GameplaySocialAssertions.assertListFriendsRequest(
        socialStub().lastFriendsRequest(),
        Long.toString(TENANT_ID),
        Long.toString(ACCOUNT_ID),
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
            "Sora [acct #" + SORA_ACCOUNT_ID + "] - online in Demo World / Live Realm (idle)");

    assertThat(responses)
        .anyMatch(
            response ->
                response.contains(
                    "Sora [acct #"
                        + SORA_ACCOUNT_ID
                        + "] - online in Demo World / Live Realm (idle)"));
  }

  @Test
  void websocketFriendsMutationFlowUsesCanonicalRosterSurface() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();

    try (GameplayWebSocketDriver client =
        openReadySessionClient(sessionId, "friends-mutate-conn")) {
      client.send("FRIENDS ADD 77");
      client.awaitContains("Friend #77 added.");
      client.send("FRIENDS REMOVE 77");
      client.awaitContains("Friend #77 removed.");
    }

    GameplaySocialAssertions.assertAddFriendRequest(
        socialStub().lastAddFriendRequest(),
        Long.toString(TENANT_ID),
        Long.toString(ACCOUNT_ID),
        "77");
    GameplaySocialAssertions.assertRemoveFriendRequest(
        socialStub().lastRemoveFriendRequest(),
        Long.toString(TENANT_ID),
        Long.toString(ACCOUNT_ID),
        "77");
  }

  @Test
  void websocketFriendsMutationByCharacterNameUsesCanonicalIdentityLookup() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();

    try (GameplayWebSocketDriver client =
        openReadySessionClient(sessionId, "friends-mutate-name-conn")) {
      client.send("FRIENDS ADD Sora");
      client.awaitContains("Sora [acct #" + SORA_ACCOUNT_ID + "] added.");
      client.send("FRIENDS REMOVE Sora");
      client.awaitContains("Sora [acct #" + SORA_ACCOUNT_ID + "] removed.");
    }

    GameplaySocialAssertions.assertAddFriendRequest(
        socialStub().lastAddFriendRequest(),
        Long.toString(TENANT_ID),
        Long.toString(ACCOUNT_ID),
        Long.toString(SORA_ACCOUNT_ID));
    GameplaySocialAssertions.assertRemoveFriendRequest(
        socialStub().lastRemoveFriendRequest(),
        Long.toString(TENANT_ID),
        Long.toString(ACCOUNT_ID),
        Long.toString(SORA_ACCOUNT_ID));
  }

  @Test
  void websocketFriendsRemoveByRosterOrdinalUsesRenderedRosterIdentity() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();

    try (GameplayWebSocketDriver client =
        openReadySessionClient(sessionId, "friends-remove-ordinal-conn")) {
      client.send("FRIENDS REMOVE #1");
      client.awaitContains("Sora [acct #" + SORA_ACCOUNT_ID + "] removed.");
    }

    GameplaySocialAssertions.assertRemoveFriendByOrdinalRequest(
        socialStub().lastRemoveFriendByOrdinalRequest(),
        Long.toString(TENANT_ID),
        Long.toString(ACCOUNT_ID),
        1);
  }

  @Test
  void websocketFriendsShowUsesCanonicalFriendDetailSurface() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();

    try (GameplayWebSocketDriver client =
        openReadySessionClient(sessionId, "friends-show-detail-conn")) {
      client.send("FRIENDS SHOW #1");
      client.awaitContains("Friend Sora [acct #" + SORA_ACCOUNT_ID + "]");
      client.awaitContains("Presence: online in Demo World / Live Realm (idle)");
      client.awaitContains("Roster entry: #1");
    }

    GameplaySocialAssertions.assertGetFriendByOrdinalRequest(
        socialStub().lastGetFriendByOrdinalRequest(),
        Long.toString(TENANT_ID),
        Long.toString(ACCOUNT_ID),
        1);
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
        socialStub().lastSummaryRequest(), Long.toString(TENANT_ID), Long.toString(ACCOUNT_ID));
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
        socialStub().lastGetVisibilityRequest(),
        Long.toString(TENANT_ID),
        Long.toString(ACCOUNT_ID));
    GameplaySocialAssertions.assertUpdateVisibilityRequest(
        socialStub().lastUpdateVisibilityRequest(),
        Long.toString(TENANT_ID),
        Long.toString(ACCOUNT_ID),
        FriendPresenceVisibilityPolicy.FRIEND_PRESENCE_VISIBILITY_POLICY_PRIVATE);
  }

  @Test
  void websocketFirstPartyFriendsViewsUseStructuredCanonicalPayloads() throws Exception {
    ensureTestServicesStarted();
    prepareGameInstance();

    try (GameplayWebSocketDriver client = openFirstPartyGameplayClient("friends-first-party")) {
      int baseline = client.responses().size();
      client.send("FRIENDS");
      JsonNode friends = awaitStructuredCommand(client, baseline, "FRIENDS");
      JsonNode friendsPayload = requirePayload(friends, "friends_view");
      assertThat(friends.path("accepted").asBoolean()).isTrue();
      assertThat(friendsPayload.path("filter").asText()).isEqualTo("ALL");
      assertThat(friendsPayload.path("friends").get(0).path("friendAccountId").asLong())
          .isEqualTo(SORA_ACCOUNT_ID);
      assertThat(friendsPayload.path("friends").get(0).path("playableStateScope").asText())
          .isEqualTo("SHARED");

      baseline = client.responses().size();
      client.send("FRIENDS SHOW #1");
      JsonNode detail = awaitStructuredCommand(client, baseline, "FRIENDS");
      JsonNode detailPayload = requirePayload(detail, "friend_detail_view");
      assertThat(detailPayload.path("friend").path("ordinal").asInt()).isEqualTo(1);
      assertThat(detailPayload.path("friend").path("friendAccountId").asLong())
          .isEqualTo(SORA_ACCOUNT_ID);

      baseline = client.responses().size();
      client.send("FRIENDS SUMMARY");
      JsonNode summary = awaitStructuredCommand(client, baseline, "FRIENDS");
      JsonNode summaryPayload = requirePayload(summary, "friend_roster_summary_view");
      assertThat(summaryPayload.path("totalCount").asInt()).isEqualTo(1);
      assertThat(summaryPayload.path("onlineCount").asInt()).isEqualTo(1);
      assertThat(summaryPayload.path("sharedCount").asInt()).isEqualTo(1);
    }
  }

  @Test
  void websocketFirstPartyFriendsVisibilityUsesStructuredPolicyPayloads() throws Exception {
    ensureTestServicesStarted();
    prepareGameInstance();

    try (GameplayWebSocketDriver client =
        openFirstPartyGameplayClient("friends-visibility-first-party")) {
      int baseline = client.responses().size();
      client.send("FRIENDS VISIBILITY");
      JsonNode visibility = awaitStructuredCommand(client, baseline, "FRIENDS");
      JsonNode visibilityPayload = requirePayload(visibility, "friend_presence_policy_view");
      assertThat(visibilityPayload.path("currentPolicy").asText()).isEqualTo("FRIENDS_ONLY");

      baseline = client.responses().size();
      client.send("FRIENDS VISIBILITY PRIVATE");
      JsonNode updated = awaitStructuredCommand(client, baseline, "FRIENDS");
      requirePayload(updated, "notice");
      JsonNode updatedPolicy = requirePayload(updated, "friend_presence_policy_view");
      assertThat(updatedPolicy.path("currentPolicy").asText()).isEqualTo("PRIVATE");
    }

    GameplaySocialAssertions.assertGetVisibilityRequest(
        socialStub().lastGetVisibilityRequest(),
        Long.toString(TENANT_ID),
        Long.toString(ACCOUNT_ID));
    GameplaySocialAssertions.assertUpdateVisibilityRequest(
        socialStub().lastUpdateVisibilityRequest(),
        Long.toString(TENANT_ID),
        Long.toString(ACCOUNT_ID),
        FriendPresenceVisibilityPolicy.FRIEND_PRESENCE_VISIBILITY_POLICY_PRIVATE);
  }

  @Test
  void websocketFirstPartyFriendsMutationsUseStructuredCanonicalPayloads() throws Exception {
    ensureTestServicesStarted();
    prepareGameInstance();

    try (GameplayWebSocketDriver client = openFirstPartyGameplayClient("friends-add-first-party")) {
      int baseline = client.responses().size();
      client.send("FRIENDS ADD 77");
      JsonNode added = awaitStructuredCommand(client, baseline, "FRIENDS");
      JsonNode addPayload = requirePayload(added, "friend_mutation_result");
      assertThat(addPayload.path("action").asText()).isEqualTo("ADD");
      assertThat(addPayload.path("friendAccountId").asLong()).isEqualTo(77L);
      assertThat(addPayload.path("displayName").asText()).isEqualTo("Friend #77");
    }

    prepareGameInstance();
    try (GameplayWebSocketDriver client =
        openFirstPartyGameplayClient("friends-remove-name-first-party")) {
      int baseline = client.responses().size();
      client.send("FRIENDS REMOVE Sora");
      JsonNode removedByName = awaitStructuredCommand(client, baseline, "FRIENDS");
      JsonNode removeByNamePayload = requirePayload(removedByName, "friend_mutation_result");
      assertThat(removeByNamePayload.path("action").asText()).isEqualTo("REMOVE");
      assertThat(removeByNamePayload.path("friendAccountId").asLong()).isEqualTo(SORA_ACCOUNT_ID);
      assertThat(removeByNamePayload.path("displayName").asText()).isEqualTo("Sora");
      assertThat(removeByNamePayload.path("characterName").asText()).isEqualTo("Sora");
    }

    prepareGameInstance();
    try (GameplayWebSocketDriver client =
        openFirstPartyGameplayClient("friends-remove-ordinal-first-party")) {
      int baseline = client.responses().size();
      client.send("FRIENDS REMOVE #1");
      JsonNode removedByOrdinal = awaitStructuredCommand(client, baseline, "FRIENDS");
      JsonNode removeByOrdinalPayload = requirePayload(removedByOrdinal, "friend_mutation_result");
      assertThat(removeByOrdinalPayload.path("action").asText()).isEqualTo("REMOVE");
      assertThat(removeByOrdinalPayload.path("friendAccountId").asLong())
          .isEqualTo(SORA_ACCOUNT_ID);
      assertThat(removeByOrdinalPayload.path("ordinal").asInt()).isEqualTo(1);
    }
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
            GameplayWebSocketScenarios.demoAdmission("Sora", READY_LOOK_TEXT),
            "observer-conn",
            GameplayWebSocketScenarios.demoAdmission("Nyx", READY_LOOK_TEXT))) {
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
            GameplayWebSocketScenarios.demoAdmission("Sora", READY_LOOK_TEXT))) {
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

      client.send("GET Torch");
      client.awaitContains("You pick up Torch.");
      client.awaitContains("Inventory:");
      client.awaitContains("- Torch [torch#1] (A small torch)");
      GameplayEntityAssertions.assertPickup(
          entityStub().lastPickupRequest(),
          String.valueOf(TENANT_ID),
          ChatTestFixtures.PLAYER_EMBERLINE,
          String.valueOf(DEMO_WORLD_INSTANCE_ID),
          ChatTestFixtures.ROOM_ID,
          "torch");

      client.send("CONTAINER Backpack");
      client.awaitContains("Container: Backpack [backpack#1]");
      client.awaitContains("- Ration [ration#1] (A dry trail ration)");

      client.send("PUT Torch INTO Backpack");
      client.awaitContains("You put Torch into Backpack.");
      client.awaitContains("- Torch [torch#1] (A small torch)");
      GameplayEntityAssertions.assertPut(
          entityStub().lastPutRequest(),
          String.valueOf(TENANT_ID),
          ChatTestFixtures.PLAYER_EMBERLINE,
          "container-backpack-1",
          "torch",
          "torch-ground-1");

      client.send("TAKE Torch FROM Backpack");
      client.awaitContains("You take Torch from Backpack.");
      client.awaitContains("- Ration [ration#1] (A dry trail ration)");
      GameplayEntityAssertions.assertTake(
          entityStub().lastTakeRequest(),
          String.valueOf(TENANT_ID),
          ChatTestFixtures.PLAYER_EMBERLINE,
          "container-backpack-1",
          "torch",
          "");

      client.send("DROP Torch");
      client.awaitContains("You drop Torch.");
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
          GameplayCrossServiceStack.defaultDemoBuilder(POSTGRES, REDIS, ACCOUNT_ID)
              .mapAccountId("sora@example.com", SORA_ACCOUNT_ID)
              .withInitialRoomEntities(ChatTestFixtures.sampleEntities())
              .withSocialEnabled(true)
              .withGameSessionProps(
                  Map.of("firemud.gateway.connect-context.jwt-secret", FIRST_PARTY_CONNECT_SECRET))
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
        ACCOUNT_ID,
        Long.parseLong(ChatTestFixtures.PLAYER_SORA),
        Long.parseLong(ChatTestFixtures.PLAYER_NYX));
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

  private GameplayWebSocketDriver openFirstPartyGameplayClient(String transportSessionId)
      throws Exception {
    GameplayWebSocketDriver client = openFirstPartyClient(transportSessionId);
    client.send("LOGIN");
    JsonNode login = awaitStructuredCommand(client, 0, "LOGIN");
    assertThat(login.path("accepted").asBoolean()).withFailMessage(login.toPrettyString()).isTrue();
    client.send("PLAY demo");
    JsonNode play = awaitStructuredCommand(client, 1, "PLAY");
    assertThat(play.path("accepted").asBoolean()).withFailMessage(play.toPrettyString()).isTrue();
    return client;
  }

  private URI gameSessionWebSocketUrl() {
    return URI.create("ws://localhost:" + gameSession().port() + "/ws/game");
  }

  private GameplayWebSocketDriver openFirstPartyClient(String transportSessionId) {
    return GameplayWebSocketDriver.connectFirstPartyWeb(
        URI.create("ws://localhost:" + gameSession().port() + "/ws/game"),
        COMMAND_WAIT,
        transportSessionId,
        FIRST_PARTY_CONNECT_SECRET,
        Long.toString(ACCOUNT_ID),
        Map.of(
            "accountId",
            Long.toString(ACCOUNT_ID),
            "tenantId",
            Long.toString(TENANT_ID),
            "worldSlug",
            "demo",
            "realmSlug",
            "production",
            "gameInstanceId",
            Long.toString(DEMO_WORLD_INSTANCE_ID),
            "pointerVersion",
            "1",
            "connectScopeId",
            "scope-" + transportSessionId,
            "connectTokenJti",
            "connect-jti-" + transportSessionId,
            "connectRequestId",
            "connect-req-" + transportSessionId,
            "gatewayRequestId",
            "gateway-req-" + transportSessionId));
  }

  private JsonNode awaitStructuredCommand(
      GameplayWebSocketDriver client, int responseBaseline, String commandType) throws Exception {
    return GameplayStructuredCommandAssertions.awaitStructuredCommand(
        client, responseBaseline, commandType);
  }

  private JsonNode requirePayload(JsonNode envelope, String payloadType) {
    return GameplayStructuredCommandAssertions.requirePayload(envelope, payloadType);
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
