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
  private static final Duration COMMAND_WAIT = Duration.ofSeconds(20);
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
            GameplayWebSocketScenarios.demoAdmission("Sora", READY_LOOK_TEXT))) {
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
  void websocketFirstPartySayUsesStructuredCommunicationMetadata() throws Exception {
    ensureTestServicesStarted();
    prepareGameInstance();

    try (GameplayWebSocketDriver client = openFirstPartyGameplayClient("say-first-party")) {
      int baseline = client.responses().size();
      client.send("SAY hello travelers");
      JsonNode say = awaitStructuredCommand(client, baseline, "SAY");
      GameplayStructuredCommandAssertions.requireStructuredCommand(
          say, "SAY", "say", "SOCIAL", "COMMUNICATION");
      assertThat(say.path("accepted").asBoolean()).isTrue();
    }
  }

  @Test
  void websocketFirstPartyAuthoredCommunicationUsesStructuredMetadata() throws Exception {
    ensureTestServicesStarted();
    prepareGameInstance();

    try (GameplayWebSocketDriver client = openFirstPartyGameplayClient("authored-first-party")) {
      int baseline = client.responses().size();
      client.send("SALUTE captain");
      JsonNode authored = awaitStructuredCommand(client, baseline, "AUTHORED");
      GameplayStructuredCommandAssertions.requireStructuredCommand(
          authored, "AUTHORED", "wave-salute", "SOCIAL", "AUTHORING", "COMMUNICATION");
      assertThat(authored.path("accepted").asBoolean()).isTrue();
    }
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
  void websocketFirstPartyInventoryViewsExposeTypedItemMetadata() throws Exception {
    ensureTestServicesStarted();
    prepareGameInstance();

    try (GameplayWebSocketDriver client = openFirstPartyGameplayClient("inventory-first-party")) {
      int baseline = client.responses().size();
      client.send("GET Torch");
      JsonNode pickup = awaitStructuredCommand(client, baseline, "GET");
      assertThat(pickup.path("accepted").asBoolean())
          .withFailMessage(pickup.toPrettyString())
          .isTrue();
      List<JsonNode> pickupViews = requirePayloads(pickup, "inventory_view");
      assertThat(pickupViews).hasSize(2);
      JsonNode carriedInventory = requireInventoryViewBySource(pickupViews, "INVENTORY");
      assertThat(carriedInventory.path("entries")).hasSize(1);
      assertThat(carriedInventory.path("entries").get(0).path("visibleRef").asText())
          .isEqualTo("torch#1");
      assertThat(carriedInventory.path("entries").get(0).path("itemName").asText())
          .isEqualTo("Torch");
      JsonNode roomGroundInventory = requireInventoryViewBySource(pickupViews, "ROOM_GROUND");
      assertThat(roomGroundInventory.path("entries")).hasSize(1);
      assertThat(roomGroundInventory.path("entries").get(0).path("visibleRef").asText())
          .isEqualTo("backpack#1");
      assertThat(roomGroundInventory.path("entries").get(0).path("itemName").asText())
          .isEqualTo("Backpack");

      baseline = client.responses().size();
      client.send("CONTAINER Backpack");
      JsonNode container = awaitStructuredCommand(client, baseline, "CONTAINER");
      assertThat(container.path("accepted").asBoolean())
          .withFailMessage(container.toPrettyString())
          .isTrue();
      JsonNode containerPayload = requirePayload(container, "inventory_view");
      assertThat(containerPayload.path("source").asText()).isEqualTo("CONTAINER");
      assertThat(containerPayload.path("context").path("displayName").asText())
          .isEqualTo("Backpack");
      assertThat(containerPayload.path("context").path("containerInstanceId").asText())
          .isEqualTo("container-backpack-1");
      assertThat(containerPayload.path("entries")).hasSize(1);
      assertThat(containerPayload.path("entries").get(0).path("itemName").asText())
          .isEqualTo("Ration");
      assertThat(containerPayload.path("entries").get(0).path("visibleRef").asText())
          .isEqualTo("ration#1");

      baseline = client.responses().size();
      client.send("WEAR Leather Cap");
      JsonNode wear = awaitStructuredCommand(client, baseline, "WEAR");
      assertThat(wear.path("accepted").asBoolean()).withFailMessage(wear.toPrettyString()).isTrue();

      baseline = client.responses().size();
      client.send("EQUIPMENT");
      JsonNode equipment = awaitStructuredCommand(client, baseline, "EQUIPMENT");
      assertThat(equipment.path("accepted").asBoolean())
          .withFailMessage(equipment.toPrettyString())
          .isTrue();
      JsonNode equipmentPayload = requirePayload(equipment, "inventory_view");
      assertThat(equipmentPayload.path("source").asText()).isEqualTo("EQUIPMENT");
      assertThat(equipmentPayload.path("entries")).hasSize(1);
      assertThat(equipmentPayload.path("entries").get(0).path("slot").asText()).isEqualTo("HEAD");
      assertThat(equipmentPayload.path("entries").get(0).path("itemName").asText())
          .isEqualTo("Leather Cap");
      assertThat(equipmentPayload.path("entries").get(0).path("visibleRef").asText())
          .isEqualTo("cap#1");
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
                  "You are not carrying anything.",
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
          GameplayCrossServiceStack.defaultDemoBuilder(POSTGRES, REDIS, ACCOUNT_ID)
              .mapAccountId("sora@example.com", SORA_ACCOUNT_ID)
              .withInitialRoomEntities(ChatTestFixtures.sampleEntities())
              .withSocialEnabled(true)
              .withGameSessionProps(
                  Map.of(
                      "firemud.gateway.connect-context.jwt-secret",
                      FIRST_PARTY_CONNECT_SECRET,
                      "game-session.authored-actions.actions[0].action-id",
                      "wave-salute",
                      "game-session.authored-actions.actions[0].command-id",
                      "wave-salute",
                      "game-session.authored-actions.actions[0].aliases[0]",
                      "salute",
                      "game-session.authored-actions.actions[0].stage-requirement",
                      "GAMEPLAY",
                      "game-session.authored-actions.actions[0].prompt-policy",
                      "WHEN_GAMEPLAY",
                      "game-session.authored-actions.actions[0].action-category",
                      "SOCIAL",
                      "game-session.authored-actions.actions[0].action-tags[0]",
                      "AUTHORING",
                      "game-session.authored-actions.actions[0].action-tags[1]",
                      "COMMUNICATION"))
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
    GameplayStructuredCommandAssertions.requireStructuredCommand(
        login, "LOGIN", "login", "META", "SESSION");
    assertThat(login.path("accepted").asBoolean()).withFailMessage(login.toPrettyString()).isTrue();
    client.send("PLAY demo");
    JsonNode play = awaitStructuredCommand(client, 1, "PLAY");
    GameplayStructuredCommandAssertions.requireStructuredCommand(
        play, "PLAY", "play", "META", "SESSION");
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

  private List<JsonNode> requirePayloads(JsonNode envelope, String payloadType) {
    return GameplayStructuredCommandAssertions.requirePayloads(envelope, payloadType);
  }

  private JsonNode requireInventoryViewBySource(List<JsonNode> payloads, String source) {
    return payloads.stream()
        .filter(payload -> source.equals(payload.path("source").asText()))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "Missing inventory_view source=" + source + " in payloads: " + payloads));
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
