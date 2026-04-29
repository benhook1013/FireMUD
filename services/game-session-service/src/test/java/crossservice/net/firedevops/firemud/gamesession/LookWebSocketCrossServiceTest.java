package net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import net.firedevops.firemud.gamesession.test.GameInstanceTestFixtures;
import net.firedevops.firemud.gamesession.test.LookTestFixtures;
import net.firedevops.firemud.gamesession.testsupport.GameplayAsyncAssertions;
import net.firedevops.firemud.gamesession.testsupport.GameplayCrossServiceStack;
import net.firedevops.firemud.gamesession.testsupport.GameplayTranscriptMatchers;
import net.firedevops.firemud.gamesession.testsupport.GameplayWebSocketDriver;
import net.firedevops.firemud.gamesession.testsupport.GameplayWebSocketScenarios;
import net.firedevops.firemud.test.AccountRuntimeStubServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class LookWebSocketCrossServiceTest {
  private static final Duration COMMAND_WAIT = Duration.ofSeconds(8);
  private static final long TENANT_ID = 1L;
  private static final long ACCOUNT_ID = 7L;
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

  @AfterAll
  static synchronized void stopServices() {
    GameplayCrossServiceStack stack = STACK;
    STACK = null;
    if (stack != null) {
      stack.close();
    }
  }

  @Test
  void websocketLookFlowReportsCanonicalTranscriptAndMetrics() throws Exception {
    ensureTestServicesStarted();
    accountStub().allowGameplayAdmission();
    long sessionId = prepareGameInstance();
    List<String> responses = runLookSequence(sessionId);

    assertThat(responses).hasSizeGreaterThanOrEqualTo(5);
    assertThat(responses.get(0)).startsWith("OK WORLDS");
    assertThat(responses.get(1)).startsWith("OK LOGIN");
    assertThat(responses.get(2)).startsWith("OK PLAY");
    assertThat(responses.get(3).trim())
        .isEqualTo(GameplayTranscriptMatchers.canonicalLookWithPrompt());
    assertThat(responses.get(4)).startsWith("ERROR ROOM_NOT_FOUND");

    GameplayAsyncAssertions.assertMetricEventually(
        gameSession().bean(io.micrometer.core.instrument.MeterRegistry.class),
        COMMAND_WAIT,
        "gamesession.command.look.invocations",
        2.0);
    GameplayAsyncAssertions.assertMetricEventually(
        gameSession().bean(io.micrometer.core.instrument.MeterRegistry.class),
        COMMAND_WAIT,
        "gamesession.command.look.failures",
        1.0,
        "error",
        "ROOM_NOT_FOUND");
  }

  @Test
  void websocketQuickLookOmitsLongDescriptionButKeepsPrompt() throws Exception {
    ensureTestServicesStarted();
    accountStub().allowGameplayAdmission();
    long sessionId = prepareGameInstance();
    List<String> responses = runQuickLookSequence(sessionId);

    assertThat(responses).hasSizeGreaterThanOrEqualTo(4);
    assertThat(responses.get(0)).startsWith("OK LOGIN");
    assertThat(responses.get(1)).startsWith("OK PLAY");
    assertThat(responses)
        .anyMatch(
            response ->
                response.startsWith("OK QUICKLOOK")
                    && response.contains("Room: Candle-lit Antechamber")
                    && response.contains("Short:")
                    && !response.contains("Long:")
                    && response.endsWith("demo> "));
  }

  @Test
  void websocketMovementReturnsDestinationLookAndPersistsRoomContext() throws Exception {
    ensureTestServicesStarted();
    accountStub().allowGameplayAdmission();
    long sessionId = prepareGameInstance();
    List<String> responses = runMovementSequence(sessionId);

    assertThat(responses).hasSizeGreaterThanOrEqualTo(5);
    assertThat(responses.get(0)).startsWith("OK LOGIN");
    assertThat(responses.get(1)).startsWith("OK PLAY");
    assertThat(responses)
        .anyMatch(
            response ->
                GameplayTranscriptMatchers.matchesCanonicalMoveRefreshWithOptionalPrompt(
                        LookTestFixtures.DESTINATION_ROOM_ID)
                    .test(response.trim()));
    assertThat(responses)
        .anyMatch(
            response ->
                response
                    .trim()
                    .equals(
                        GameplayTranscriptMatchers.canonicalLookWithPrompt(
                            LookTestFixtures.DESTINATION_ROOM_ID)));
    assertThat(responses).anyMatch(response -> response.startsWith("ERROR INVALID_EXIT"));
  }

  @Test
  void websocketReconnectAfterMoveKeepsDestinationRoomContext() throws Exception {
    ensureTestServicesStarted();
    accountStub().allowGameplayAdmission();
    long sessionId = prepareGameInstance();

    List<String> firstConnection = runMoveThenDisconnect(sessionId);
    assertThat(firstConnection).hasSizeGreaterThanOrEqualTo(4);
    assertThat(firstConnection)
        .anyMatch(
            response ->
                GameplayTranscriptMatchers.matchesCanonicalMoveOrLookWithOptionalPrompt(
                        LookTestFixtures.DESTINATION_ROOM_ID)
                    .test(response.trim()));

    List<String> reconnectLook = runLookAfterReconnect(sessionId);
    assertThat(reconnectLook).hasSizeGreaterThanOrEqualTo(4);
    String combinedReconnect = String.join("\n", reconnectLook);
    assertThat(combinedReconnect).contains("OK LOGIN");
    assertThat(combinedReconnect).contains("OK PLAY");
    assertThat(combinedReconnect)
        .contains(GameplayTranscriptMatchers.canonicalLook(LookTestFixtures.DESTINATION_ROOM_ID));
  }

  @Test
  void websocketSecondConnectionTakesOverGameplayBinding() throws Exception {
    ensureTestServicesStarted();
    accountStub().allowGameplayAdmission();
    long firstSessionId = prepareGameInstance();
    long secondSessionId = prepareAdditionalGameInstance();

    try (GameplayWebSocketDriver first = openReadySession(firstSessionId);
        GameplayWebSocketDriver second = openReadySession(secondSessionId)) {
      assertThat(first.responses())
          .anyMatch(
              response ->
                  response.trim().equals(GameplayTranscriptMatchers.canonicalLookWithPrompt()));

      assertThat(second.responses())
          .anyMatch(
              response ->
                  response.trim().equals(GameplayTranscriptMatchers.canonicalLookWithPrompt())
                      || response.trim().equals(GameplayTranscriptMatchers.canonicalLook()));

      first.send("LOOK");
      first.awaitStartsWith("ERROR LOGIN_REQUIRED");
      assertThat(first.responses())
          .anyMatch(response -> response.startsWith("ERROR LOGIN_REQUIRED"));
      GameplayAsyncAssertions.assertMetricEventually(
          gameSession().bean(io.micrometer.core.instrument.MeterRegistry.class),
          COMMAND_WAIT,
          "gamesession.session.takeover",
          1.0);
    }
  }

  @Test
  void websocketMovedPlayerStaysInGameAcrossGameLogicRestart() throws Exception {
    ensureTestServicesStarted();
    accountStub().allowGameplayAdmission();
    long sessionId = prepareGameInstance();

    try (GameplayWebSocketDriver socket = openReadySession(sessionId)) {
      socket.send("north");
      socket.awaitMatching(
          response ->
              GameplayTranscriptMatchers.matchesCanonicalMoveRefreshWithOptionalPrompt(
                      LookTestFixtures.DESTINATION_ROOM_ID)
                  .test(response.trim()),
          "destination move refresh");
      assertThat(socket.responses())
          .anyMatch(
              response ->
                  GameplayTranscriptMatchers.matchesCanonicalMoveRefreshWithOptionalPrompt(
                          LookTestFixtures.DESTINATION_ROOM_ID)
                      .test(response.trim()));

      STACK.restartGameLogic();

      socket.send("LOOK");
      socket.awaitMatching(
          response ->
              response
                  .trim()
                  .equals(
                      GameplayTranscriptMatchers.canonicalLookWithPrompt(
                          LookTestFixtures.DESTINATION_ROOM_ID)),
          "destination look after restart");
      assertThat(socket.responses())
          .matches(
              responses ->
                  responses.stream()
                      .anyMatch(
                          response ->
                              response
                                  .trim()
                                  .equals(
                                      GameplayTranscriptMatchers.canonicalLookWithPrompt(
                                          LookTestFixtures.DESTINATION_ROOM_ID))));
    }
  }

  @Test
  void websocketReconnectAfterRevocationFailsClosed() throws Exception {
    ensureTestServicesStarted();
    accountStub().allowGameplayAdmission();
    long sessionId = prepareGameInstance();

    List<String> firstConnection = runMoveThenDisconnect(sessionId);
    assertThat(firstConnection).hasSizeGreaterThanOrEqualTo(4);
    assertThat(firstConnection)
        .anyMatch(
            response ->
                GameplayTranscriptMatchers.matchesCanonicalMoveRefreshWithOptionalPrompt(
                        LookTestFixtures.DESTINATION_ROOM_ID)
                    .test(response.trim()));

    accountStub().denyGameplayAdmission();
    List<String> reconnectResponses = runPlayAfterReconnect(sessionId);
    assertThat(reconnectResponses).hasSizeGreaterThanOrEqualTo(2);
    assertThat(reconnectResponses).anyMatch(response -> response.startsWith("OK LOGIN"));
    assertThat(reconnectResponses)
        .anyMatch(response -> response.startsWith("ERROR WORLD_ACCESS_DENIED"));
  }

  @Test
  void websocketReconnectAfterStaleSessionFreshEntersGameplay() throws Exception {
    ensureTestServicesStarted();
    accountStub().allowGameplayAdmission();
    long sessionId = prepareGameInstance();

    List<String> firstConnection = runMoveThenDisconnect(sessionId);
    assertThat(firstConnection).hasSizeGreaterThanOrEqualTo(4);
    assertThat(firstConnection)
        .anyMatch(
            response ->
                GameplayTranscriptMatchers.matchesCanonicalMoveRefreshWithOptionalPrompt(
                        LookTestFixtures.DESTINATION_ROOM_ID)
                    .test(response.trim()));

    gameSession()
        .bean(net.firedevops.firemud.gamesession.service.SessionContextService.class)
        .deleteBySessionId(TENANT_ID, sessionId);

    List<String> reconnectLook = runLookAfterReconnect(sessionId);
    assertThat(reconnectLook).hasSizeGreaterThanOrEqualTo(3);
    String combinedReconnect = String.join("\n", reconnectLook);
    assertThat(combinedReconnect).contains("OK LOGIN");
    assertThat(combinedReconnect).contains("OK PLAY");
    assertThat(combinedReconnect).contains("demo>");
    assertThat(combinedReconnect).contains(GameplayTranscriptMatchers.canonicalLook());
  }

  @Test
  void websocketReconnectWithoutBufferedTranscriptStillGetsFreshLook() throws Exception {
    ensureTestServicesStarted();
    accountStub().allowGameplayAdmission();
    long sessionId = prepareGameInstance();

    List<String> firstConnection = runPlayThenDisconnect(sessionId);
    assertThat(firstConnection).hasSizeGreaterThanOrEqualTo(2);
    assertThat(firstConnection.get(0)).startsWith("OK LOGIN");
    assertThat(firstConnection.get(1)).startsWith("OK PLAY");

    List<String> reconnectResponses = runPlayAfterReconnectExpectingFreshLook(sessionId);
    assertThat(reconnectResponses).hasSizeGreaterThanOrEqualTo(3);
    assertThat(reconnectResponses).anyMatch(response -> response.startsWith("OK LOGIN"));
    assertThat(reconnectResponses)
        .anyMatch(response -> response.trim().equals(GameplayTranscriptMatchers.canonicalLook()));
  }

  private static synchronized void ensureTestServicesStarted() throws Exception {
    if (STACK == null) {
      STACK = GameplayCrossServiceStack.defaultDemoBuilder(POSTGRES, REDIS, ACCOUNT_ID).start();
    }
  }

  private long prepareGameInstance() {
    return insertGameInstance(true);
  }

  private long prepareAdditionalGameInstance() {
    return insertGameInstance(false);
  }

  private long insertGameInstance(boolean clearExisting) {
    if (clearExisting) {
      return STACK.freshGameplayBaseline(TENANT_ID, 1L, ACCOUNT_ID, 7L, ACCOUNT_ID);
    }
    DataSource dataSource = gameSession().bean(DataSource.class);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    GameInstanceTestFixtures.ensureGameInstancesTable(jdbc);
    return GameInstanceTestFixtures.insertRunningGameInstance(jdbc, TENANT_ID, ACCOUNT_ID, 7L);
  }

  private List<String> runLookSequence(long sessionId) throws Exception {
    try (GameplayWebSocketDriver client = openSessionClient(sessionId)) {
      client.send("WORLDS");
      client.awaitStartsWith("OK WORLDS");
      enterGameplay(client);
      client.send("LOOK");
      client.awaitStartsWith("OK LOOK");
      worldStub().triggerNotFound("room missing for regression");
      client.send("LOOK");
      client.awaitStartsWith("ERROR ROOM_NOT_FOUND");
      return client.responses();
    }
  }

  private List<String> runMovementSequence(long sessionId) throws Exception {
    try (GameplayWebSocketDriver client = openSessionClient(sessionId)) {
      enterGameplay(client);
      client.send("north");
      client.awaitMatching(
          response ->
              GameplayTranscriptMatchers.matchesCanonicalMoveRefreshWithOptionalPrompt(
                      LookTestFixtures.DESTINATION_ROOM_ID)
                  .test(response.trim()),
          "destination move refresh");
      client.send("LOOK");
      client.awaitMatching(
          response ->
              response
                  .trim()
                  .equals(
                      GameplayTranscriptMatchers.canonicalLookWithPrompt(
                          LookTestFixtures.DESTINATION_ROOM_ID)),
          "destination look with prompt");
      client.send("west");
      client.awaitStartsWith("ERROR INVALID_EXIT");
      return client.responses();
    }
  }

  private List<String> runQuickLookSequence(long sessionId) throws Exception {
    try (GameplayWebSocketDriver client = openSessionClient(sessionId)) {
      enterGameplay(client);
      client.send("QUICKLOOK");
      client.awaitStartsWith("OK QUICKLOOK");
      return client.responses();
    }
  }

  private List<String> runMoveThenDisconnect(long sessionId) throws Exception {
    try (GameplayWebSocketScenarios.ReconnectScenario scenario =
        GameplayWebSocketScenarios.reconnectAfterReady(
            ignored -> openSessionClient(sessionId),
            "session-" + sessionId + "-first",
            "session-" + sessionId + "-unused",
            GameplayWebSocketScenarios.Admission.unnamed(
                "demo@example.com", "swordfish", "demo", READY_LOOK_TEXT),
            GameplayWebSocketScenarios.DisconnectMode.CLOSE,
            client -> {
              client.send("north");
              client.awaitMatching(
                  response ->
                      GameplayTranscriptMatchers.matchesCanonicalMoveOrLookWithOptionalPrompt(
                              LookTestFixtures.DESTINATION_ROOM_ID)
                          .test(response.trim()),
                  "destination move refresh");
            })) {
      return scenario.firstResponses();
    }
  }

  private List<String> runPlayThenDisconnect(long sessionId) throws Exception {
    try (GameplayWebSocketDriver client = openReadySession(sessionId)) {
      return client.responses();
    }
  }

  private List<String> runLookAfterReconnect(long sessionId) throws Exception {
    try (GameplayWebSocketScenarios.ReconnectScenario scenario =
        GameplayWebSocketScenarios.reconnectAfterReady(
            ignored -> openSessionClient(sessionId),
            "session-" + sessionId + "-first",
            "session-" + sessionId + "-reconnect",
            GameplayWebSocketScenarios.Admission.unnamed(
                "demo@example.com", "swordfish", "demo", READY_LOOK_TEXT),
            GameplayWebSocketScenarios.DisconnectMode.CLOSE,
            client -> {
              client.send("north");
              client.awaitMatching(
                  response ->
                      GameplayTranscriptMatchers.matchesCanonicalMoveOrLookWithOptionalPrompt(
                              LookTestFixtures.DESTINATION_ROOM_ID)
                          .test(response.trim()),
                  "destination move refresh");
            })) {
      scenario.reconnecting().send("LOOK");
      scenario
          .reconnecting()
          .awaitMatching(
              response ->
                  GameplayTranscriptMatchers.matchesCanonicalLookWithOptionalPrompt(
                              LookTestFixtures.ROOM_ID)
                          .test(response.trim())
                      || GameplayTranscriptMatchers.matchesCanonicalLookWithOptionalPrompt(
                              LookTestFixtures.DESTINATION_ROOM_ID)
                          .test(response.trim()),
              "reconnect look response");
      return scenario.reconnecting().responses();
    }
  }

  private List<String> runPlayAfterReconnect(long sessionId) throws Exception {
    try (GameplayWebSocketDriver client = openSessionClient(sessionId)) {
      client.login("demo@example.com", "swordfish");
      client.send("PLAY demo");
      client.awaitMatching(
          response ->
              response.startsWith("OK PLAY") || response.startsWith("ERROR WORLD_ACCESS_DENIED"),
          "play acceptance or admission denial");
      return client.responses();
    }
  }

  private List<String> runPlayAfterReconnectExpectingFreshLook(long sessionId) throws Exception {
    try (GameplayWebSocketDriver client = openSessionClient(sessionId)) {
      client.login("demo@example.com", "swordfish");
      client.send("PLAY demo");
      client.awaitStartsWith("OK LOOK");
      return client.responses();
    }
  }

  private GameplayWebSocketDriver openSessionClient(long sessionId) {
    return GameplayWebSocketDriver.connectGameplaySession(
        URI.create("ws://localhost:" + gameSession().port() + "/ws/game"),
        COMMAND_WAIT,
        TENANT_ID,
        sessionId);
  }

  private GameplayWebSocketDriver openReadySession(long sessionId) throws Exception {
    return GameplayWebSocketScenarios.openReady(
        ignored -> openSessionClient(sessionId),
        "session-" + sessionId,
        GameplayWebSocketScenarios.Admission.unnamed(
            "demo@example.com", "swordfish", "demo", READY_LOOK_TEXT));
  }

  private void enterGameplay(GameplayWebSocketDriver client) throws Exception {
    client.enterGameplayAndWaitReady("demo@example.com", "swordfish", "demo", READY_LOOK_TEXT);
  }

  private static AccountRuntimeStubServer accountStub() {
    return STACK.accountStub();
  }

  private static net.firedevops.firemud.gamesession.test.stubs.WorldManagementStubServer
      worldStub() {
    return STACK.worldStub();
  }

  private static CrossServiceAppHarness.GameSessionHolder gameSession() {
    return STACK.gameSession();
  }
}
