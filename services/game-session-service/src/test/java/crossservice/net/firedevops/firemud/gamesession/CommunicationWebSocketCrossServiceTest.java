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
  private static final long ACCOUNT_ID = Long.parseLong(ChatTestFixtures.PLAYER_EMBERLINE);
  private static final long SORA_ACCOUNT_ID = Long.parseLong(ChatTestFixtures.PLAYER_SORA);
  private static final long NYX_ACCOUNT_ID = Long.parseLong(ChatTestFixtures.PLAYER_NYX);
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
              assertThat(request.getAccountId()).isEqualTo(Long.toString(ACCOUNT_ID));
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
  void websocketPlayRejectsContradictoryActiveMembershipBeforeEntityRosterRead() throws Exception {
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
                    response -> response.startsWith("ERROR AUTH_UNAVAILABLE"),
                    "Contradictory active membership before Entity roster lookup"))) {
      assertThat(scenario.driver().responses())
          .anyMatch(response -> response.startsWith("ERROR AUTH_UNAVAILABLE"))
          .noneMatch(response -> response.startsWith("ERROR JOIN_REQUIRED"));
    }

    assertThat(entityStub().lastListCharactersByAccountRequest()).isEmpty();
  }

  @Test
  void websocketPlayRequiresJoinForFreshMissingMembershipBeforeEntityRosterRead() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    STACK.accountStub().setMembershipExists(false);

    try (GameplayWebSocketScenarios.LoginThenPlayScenario scenario =
        GameplayWebSocketScenarios.loginThenAttemptPlay(
            GameplayWebSocketScenarios.proxyGatewayDriverFactory(
                gameSessionWebSocketUrl(), COMMAND_WAIT, TENANT_ID, sessionId),
            "missing-membership-play-" + sessionId,
            GameplayWebSocketScenarios.demoAdmission(READY_LOOK_TEXT),
            client ->
                client.awaitMatching(
                    response -> response.startsWith("ERROR JOIN_REQUIRED"),
                    "Fresh missing membership before Entity roster lookup"))) {
      assertThat(scenario.driver().responses())
          .anyMatch(response -> response.startsWith("ERROR JOIN_REQUIRED"))
          .noneMatch(response -> response.startsWith("ERROR WORLD_ACCESS_DENIED"));
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
            sessionId, "FRIENDS", "Sora [acct #" + SORA_ACCOUNT_ID + "] - online (idle)");

    assertThat(responses).hasSizeGreaterThanOrEqualTo(3);
    assertThat(responses)
        .anyMatch(
            response -> response.contains("Sora [acct #" + SORA_ACCOUNT_ID + "] - online (idle)"));
    assertThat(responses)
        .noneMatch(
            response ->
                response.contains("private-playtest")
                    || response.contains("Private Playtest World")
                    || response.contains("staff-preview")
                    || response.contains("Staff Preview Realm")
                    || response.contains("shared")
                    || response.contains("Pointer version"));
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
            "Friends ONLINE:\n" + "1) Sora [acct #" + SORA_ACCOUNT_ID + "] - online (idle)");

    assertThat(responses)
        .anyMatch(
            response ->
                response.contains("Friends ONLINE:\n")
                    && response.contains(
                        "1) Sora [acct #" + SORA_ACCOUNT_ID + "] - online (idle)"));
    GameplaySocialAssertions.assertListFriendsRequest(
        socialStub().lastFriendsRequest(),
        Long.toString(TENANT_ID),
        Long.toString(ACCOUNT_ID),
        net.firedevops.firemud.socialgroups.v1.FriendRosterFilter.FRIEND_ROSTER_FILTER_ONLINE);
  }

  @Test
  void websocketLocationScopeFiltersFailClosedWithoutSocialQuery() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();

    for (String filter : List.of("SHARED", "ISOLATED")) {
      List<String> responses =
          runCommunicationSequence(
              sessionId,
              "FRIENDS " + filter,
              "ERROR FRIEND_PRESENCE_UNAVAILABLE Friend presence unavailable");

      assertThat(responses)
          .anyMatch(
              response ->
                  response.contains(
                      "ERROR FRIEND_PRESENCE_UNAVAILABLE Friend presence unavailable"));
      assertThat(responses).noneMatch(response -> response.contains("Friends " + filter + ":"));
    }
    assertThat(socialStub().lastFriendsRequest()).isEmpty();
  }

  @Test
  void freshGameplayBaselineReappliesConfiguredFriendPresenceBaseline() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    socialStub().setFriendPresenceEntries(List.of());

    sessionId = prepareGameInstance();
    List<String> responses =
        runCommunicationSequence(
            sessionId, "FRIENDS", "Sora [acct #" + SORA_ACCOUNT_ID + "] - online (idle)");

    assertThat(responses)
        .anyMatch(
            response -> response.contains("Sora [acct #" + SORA_ACCOUNT_ID + "] - online (idle)"));
  }

  @Test
  void websocketFriendsDetailRedactsMissingPolicyPresenceWithoutDisconnecting() throws Exception {
    ensureTestServicesStarted();
    FriendPresenceEntry missingPolicyPresence =
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
      int baseline = client.responses().size();
      client.send("FRIENDS SHOW #1");
      client.awaitContains("Friend Sora [acct #" + SORA_ACCOUNT_ID + "]");
      client.awaitContains("Presence: online (idle)");
      client.awaitContains("Roster entry: #1");
      assertThat(client.responses().subList(baseline, client.responses().size()))
          .noneMatch(
              response ->
                  response.contains("private-playtest")
                      || response.contains("Private Playtest World")
                      || response.contains("staff-preview")
                      || response.contains("Staff Preview Realm")
                      || response.contains("shared")
                      || response.contains("Pointer version"));
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
      String summary =
          client.awaitResponseMatching(
              response -> response.contains("Friend roster summary:"),
              "friend roster summary response");
      assertThat(summary)
          .contains("Linked: 1")
          .doesNotContain("Online:", "Offline:", "Recent offline:");
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
  void websocketFirstPartyBareLoginFailsClosedWithoutAdmission() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    int authenticateRequestCount = STACK.accountStub().capturedAuthenticateRequests().size();
    var previousCharacterLookup = entityStub().lastListCharactersByAccountRequest();
    java.util.Map<String, Object> connectClaims =
        java.util.Map.of(
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
            "scope-first-party-" + sessionId,
            "connectTokenJti",
            "jti-first-party-" + sessionId,
            "connectRequestId",
            "request-first-party-" + sessionId,
            "gatewayRequestId",
            "gateway-first-party-" + sessionId);

    try (GameplayWebSocketDriver client =
        GameplayWebSocketDriver.connectFirstPartyWeb(
            gameSessionWebSocketUrl(),
            COMMAND_WAIT,
            Long.toString(sessionId),
            "stub-secret-key-for-tests-1234567890",
            Long.toString(ACCOUNT_ID),
            connectClaims)) {
      int baseline = client.responses().size();
      client.send("LOGIN");
      var login =
          net.firedevops.firemud.gamesession.testsupport.GameplayStructuredCommandAssertions
              .awaitStructuredCommand(client, baseline, "LOGIN");
      assertThat(login.path("accepted").asBoolean()).isFalse();
      assertThat(login.path("errorCode").asText()).isEqualTo("AUTH_UNAVAILABLE");

      baseline = client.responses().size();
      client.send("PLAY demo");
      var play =
          net.firedevops.firemud.gamesession.testsupport.GameplayStructuredCommandAssertions
              .awaitStructuredCommand(client, baseline, "PLAY");
      assertThat(play.path("accepted").asBoolean()).isFalse();
      assertThat(play.path("errorCode").asText()).isEqualTo("LOGIN_REQUIRED");
    }

    assertThat(STACK.accountStub().capturedAuthenticateRequests())
        .hasSize(authenticateRequestCount);
    assertThat(
            gameSession()
                .bean(net.firedevops.firemud.gamesession.service.SessionContextService.class)
                .findByTenantAndSessionId(TENANT_ID, sessionId))
        .satisfies(saved -> saved.ifPresent(context -> assertThat(context.accountId()).isZero()));
    assertThat(entityStub().lastListCharactersByAccountRequest())
        .isEqualTo(previousCharacterLookup);
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
                              .setVisibilityPolicy(
                                  FriendPresenceVisibilityPolicy
                                      .FRIEND_PRESENCE_VISIBILITY_POLICY_FRIENDS_ONLY)
                              .setWorldSlug("private-playtest")
                              .setWorldDisplayName("Private Playtest World")
                              .setRealmSlug("staff-preview")
                              .setRealmDisplayName("Staff Preview Realm")
                              .setPointerVersion(941L)
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
            ACCOUNT_ID,
            7L,
            ACCOUNT_ID,
            Long.parseLong(ChatTestFixtures.PLAYER_SORA),
            Long.parseLong(ChatTestFixtures.PLAYER_NYX));
    entityStub().resetCharacterRosterState();
    STACK.accountStub().mapAccountId(SORA_EMAIL, Long.parseLong(ChatTestFixtures.PLAYER_SORA));
    STACK.accountStub().mapAccountId(NYX_EMAIL, Long.parseLong(ChatTestFixtures.PLAYER_NYX));
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
