package net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import javax.sql.DataSource;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.gamesession.test.LookTestFixtures;
import net.firedevops.firemud.gamesession.test.stubs.EntityManagementStubServer;
import net.firedevops.firemud.gamesession.test.stubs.WorldManagementStubServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.TestSocketUtils;
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

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("firemud")
          .withUsername("firemud")
          .withPassword("firemud");

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);

  private static AccountServiceStub ACCOUNT_STUB;
  private static WorldManagementStubServer WORLD_STUB;
  private static EntityManagementStubServer ENTITY_STUB;
  private static CrossServiceAppHarness.GameLogicHolder GAME_LOGIC;
  private static CrossServiceAppHarness.GameSessionHolder GAME_SESSION;

  @AfterAll
  static synchronized void stopServices() {
    CrossServiceAppHarness.GameSessionHolder gameSession = GAME_SESSION;
    GAME_SESSION = null;
    if (gameSession != null) {
      gameSession.close();
    }

    CrossServiceAppHarness.GameLogicHolder gameLogic = GAME_LOGIC;
    GAME_LOGIC = null;
    if (gameLogic != null) {
      gameLogic.close();
    }

    AccountServiceStub accountStub = ACCOUNT_STUB;
    ACCOUNT_STUB = null;
    if (accountStub != null) {
      accountStub.close();
    }

    WorldManagementStubServer worldStub = WORLD_STUB;
    WORLD_STUB = null;
    if (worldStub != null) {
      worldStub.close();
    }

    EntityManagementStubServer entityStub = ENTITY_STUB;
    ENTITY_STUB = null;
    if (entityStub != null) {
      entityStub.close();
    }
  }

  @Test
  void websocketLookFlowReportsCanonicalTranscriptAndMetrics() throws Exception {
    ensureTestServicesStarted();
    ACCOUNT_STUB.allowGameplayAdmission();
    long sessionId = prepareGameInstance();
    List<String> responses = runLookSequence(sessionId);

    assertThat(responses).hasSizeGreaterThanOrEqualTo(5);
    assertThat(responses.get(0)).startsWith("OK WORLDS");
    assertThat(responses.get(1)).startsWith("OK LOGIN");
    assertThat(responses.get(2)).startsWith("OK PLAY");
    assertThat(responses.get(3).trim()).isEqualTo(canonicalLookWithPrompt());
    assertThat(responses.get(4)).startsWith("ERROR ROOM_NOT_FOUND");

    assertMetricEventually("gamesession.command.look.invocations", 2.0, "tenantId", "1");
    assertMetricEventually(
        "gamesession.command.look.failures", 1.0, "tenantId", "1", "error", "ROOM_NOT_FOUND");
  }

  @Test
  void websocketQuickLookOmitsLongDescriptionButKeepsPrompt() throws Exception {
    ensureTestServicesStarted();
    ACCOUNT_STUB.allowGameplayAdmission();
    long sessionId = prepareGameInstance();
    List<String> responses = runQuickLookSequence(sessionId);

    assertThat(responses).hasSizeGreaterThanOrEqualTo(3);
    assertThat(responses.get(0)).startsWith("OK LOGIN");
    assertThat(responses.get(1)).startsWith("OK PLAY");
    assertThat(responses.get(2)).startsWith("OK QUICKLOOK");
    assertThat(responses.get(2)).contains("Room: Candle-lit Antechamber");
    assertThat(responses.get(2)).contains("Short:");
    assertThat(responses.get(2)).doesNotContain("Long:");
    assertThat(responses.get(2)).endsWith("demo> ");
  }

  @Test
  void websocketMovementReturnsDestinationLookAndPersistsRoomContext() throws Exception {
    ensureTestServicesStarted();
    ACCOUNT_STUB.allowGameplayAdmission();
    long sessionId = prepareGameInstance();
    List<String> responses = runMovementSequence(sessionId);

    assertThat(responses).hasSizeGreaterThanOrEqualTo(5);
    assertThat(responses.get(0)).startsWith("OK LOGIN");
    assertThat(responses.get(1)).startsWith("OK PLAY");
    assertThat(responses.get(2).trim())
        .matches(
            matchesCanonicalMoveRefreshWithOptionalPrompt(LookTestFixtures.DESTINATION_ROOM_ID));
    assertThat(responses.get(3).trim())
        .isEqualTo(canonicalLookWithPrompt(LookTestFixtures.DESTINATION_ROOM_ID));
    assertThat(responses.get(4)).startsWith("ERROR INVALID_EXIT");
  }

  @Test
  void websocketReconnectAfterMoveKeepsDestinationRoomContext() throws Exception {
    ensureTestServicesStarted();
    ACCOUNT_STUB.allowGameplayAdmission();
    long sessionId = prepareGameInstance();

    List<String> firstConnection = runMoveThenDisconnect(sessionId);
    assertThat(firstConnection).hasSizeGreaterThanOrEqualTo(3);
    assertThat(firstConnection.get(2).trim())
        .matches(
            matchesCanonicalMoveRefreshWithOptionalPrompt(LookTestFixtures.DESTINATION_ROOM_ID));

    List<String> reconnectLook = runLookAfterReconnect(sessionId);
    assertThat(reconnectLook).hasSizeGreaterThanOrEqualTo(4);
    String combinedReconnect = String.join("\n", reconnectLook);
    assertThat(combinedReconnect).contains("OK LOGIN");
    assertThat(combinedReconnect).contains("OK PLAY");
    assertThat(combinedReconnect).contains(canonicalLook(LookTestFixtures.DESTINATION_ROOM_ID));
  }

  @Test
  void websocketSecondConnectionTakesOverGameplayBinding() throws Exception {
    ensureTestServicesStarted();
    ACCOUNT_STUB.allowGameplayAdmission();
    long firstSessionId = prepareGameInstance();
    long secondSessionId = prepareAdditionalGameInstance();
    URI uri = URI.create("ws://localhost:" + GAME_SESSION.port() + "/ws/game");

    try (TrackingSocket first = connectTrackingSocket(uri, firstSessionId);
        TrackingSocket second = connectTrackingSocket(uri, secondSessionId)) {
      first.sendAndAwait("LOGIN demo@example.com swordfish", 1);
      first.sendAndAwait("PLAY demo", 2);
      first.sendAndAwait("LOOK", 3);
      assertThat(first.responses().get(2).trim()).isEqualTo(canonicalLookWithPrompt());

      second.sendAndAwait("LOGIN demo@example.com swordfish", 1);
      second.sendAndAwait("PLAY demo", 2);
      second.sendAndAwait("LOOK", 3);
      assertThat(second.responses().get(2).trim()).isEqualTo(canonicalLookWithPrompt());

      first.sendAndAwait("LOOK", 4);
      assertThat(first.responses().get(3)).startsWith("ERROR LOGIN_REQUIRED");
      assertMetricEventually("gamesession.session.takeover", 1.0, "tenantId", "1");
    }
  }

  @Test
  void websocketMovedPlayerStaysInGameAcrossGameLogicRestart() throws Exception {
    ensureTestServicesStarted();
    ACCOUNT_STUB.allowGameplayAdmission();
    long sessionId = prepareGameInstance();
    URI uri = URI.create("ws://localhost:" + GAME_SESSION.port() + "/ws/game");

    try (TrackingSocket socket = connectTrackingSocket(uri, sessionId)) {
      socket.sendAndAwait("LOGIN demo@example.com swordfish", 1);
      socket.sendAndAwait("PLAY demo", 2);
      socket.sendAndAwait("north", 3);
      assertThat(socket.responses().get(2).trim())
          .matches(
              matchesCanonicalMoveRefreshWithOptionalPrompt(LookTestFixtures.DESTINATION_ROOM_ID));

      GAME_LOGIC = GAME_LOGIC.restart();

      socket.sendAndAwait("LOOK", 4);
      assertThat(socket.responses().get(3).trim())
          .isEqualTo(canonicalLookWithPrompt(LookTestFixtures.DESTINATION_ROOM_ID));
    }
  }

  @Test
  void websocketReconnectAfterRevocationFailsClosed() throws Exception {
    ensureTestServicesStarted();
    ACCOUNT_STUB.allowGameplayAdmission();
    long sessionId = prepareGameInstance();

    List<String> firstConnection = runMoveThenDisconnect(sessionId);
    assertThat(firstConnection).hasSizeGreaterThanOrEqualTo(3);
    assertThat(firstConnection.get(2).trim())
        .matches(
            matchesCanonicalMoveRefreshWithOptionalPrompt(LookTestFixtures.DESTINATION_ROOM_ID));

    ACCOUNT_STUB.denyGameplayAdmission();
    List<String> reconnectResponses = runPlayAfterReconnect(sessionId);
    assertThat(reconnectResponses).hasSizeGreaterThanOrEqualTo(2);
    assertThat(reconnectResponses).anyMatch(response -> response.startsWith("OK LOGIN"));
    assertThat(reconnectResponses)
        .anyMatch(response -> response.startsWith("ERROR WORLD_ACCESS_DENIED"));
  }

  @Test
  void websocketReconnectAfterStaleSessionFreshEntersGameplay() throws Exception {
    ensureTestServicesStarted();
    ACCOUNT_STUB.allowGameplayAdmission();
    long sessionId = prepareGameInstance();

    List<String> firstConnection = runMoveThenDisconnect(sessionId);
    assertThat(firstConnection).hasSizeGreaterThanOrEqualTo(3);
    assertThat(firstConnection.get(2).trim())
        .matches(
            matchesCanonicalMoveRefreshWithOptionalPrompt(LookTestFixtures.DESTINATION_ROOM_ID));

    GAME_SESSION
        .bean(net.firedevops.firemud.gamesession.service.SessionContextService.class)
        .deleteBySessionId(TENANT_ID, sessionId);

    List<String> reconnectLook = runLookAfterReconnect(sessionId);
    assertThat(reconnectLook).hasSizeGreaterThanOrEqualTo(4);
    String combinedReconnect = String.join("\n", reconnectLook);
    assertThat(combinedReconnect).contains("OK LOGIN");
    assertThat(combinedReconnect).contains("OK PLAY");
    assertThat(combinedReconnect)
        .contains(canonicalMoveRefresh(LookTestFixtures.DESTINATION_ROOM_ID));
    assertThat(combinedReconnect).contains(canonicalLook());
  }

  @Test
  void websocketReconnectWithoutBufferedTranscriptStillGetsFreshLook() throws Exception {
    ensureTestServicesStarted();
    ACCOUNT_STUB.allowGameplayAdmission();
    long sessionId = prepareGameInstance();

    List<String> firstConnection = runPlayThenDisconnect(sessionId);
    assertThat(firstConnection).hasSizeGreaterThanOrEqualTo(2);
    assertThat(firstConnection.get(0)).startsWith("OK LOGIN");
    assertThat(firstConnection.get(1)).startsWith("OK PLAY");

    List<String> reconnectResponses = runPlayAfterReconnectExpectingFreshLook(sessionId);
    assertThat(reconnectResponses).hasSizeGreaterThanOrEqualTo(3);
    assertThat(reconnectResponses).anyMatch(response -> response.startsWith("OK LOGIN"));
    assertThat(reconnectResponses).anyMatch(response -> response.trim().equals(canonicalLook()));
    assertThat(reconnectResponses).anyMatch(response -> response.trim().equals("demo>"));
  }

  private static String canonicalLook() {
    return canonicalLook(LookTestFixtures.ROOM_ID);
  }

  private static String canonicalLook(String roomId) {
    return LookTestFixtures.canonicalLookText(roomId).trim();
  }

  private static Predicate<String> matchesCanonicalLookWithOptionalPrompt(String roomId) {
    String canonical = canonicalLook(roomId);
    String withPrompt = canonicalLookWithPrompt(roomId);
    return response -> response.equals(canonical) || response.equals(withPrompt);
  }

  private static String canonicalMoveRefresh(String roomId) {
    return LookTestFixtures.canonicalLookText(roomId).replaceFirst("\\nLong: .*\\n", "\n").trim();
  }

  private static Predicate<String> matchesCanonicalMoveRefreshWithOptionalPrompt(String roomId) {
    String canonical = canonicalMoveRefresh(roomId);
    String withPrompt = canonical + "\n\ndemo>";
    return response -> response.equals(canonical) || response.equals(withPrompt);
  }

  private static String canonicalLookWithPrompt() {
    return canonicalLookWithPrompt(LookTestFixtures.ROOM_ID);
  }

  private static String canonicalLookWithPrompt(String roomId) {
    return LookTestFixtures.canonicalLookText(roomId).trim() + "\n\ndemo>";
  }

  private static synchronized void ensureTestServicesStarted() throws Exception {
    if (ACCOUNT_STUB == null) {
      ACCOUNT_STUB = new AccountServiceStub(TestSocketUtils.findAvailableTcpPort());
    }
    if (WORLD_STUB == null) {
      WORLD_STUB = new WorldManagementStubServer(TestSocketUtils.findAvailableTcpPort());
    }
    if (ENTITY_STUB == null) {
      ENTITY_STUB = new EntityManagementStubServer(TestSocketUtils.findAvailableTcpPort());
    }
    if (GAME_LOGIC == null) {
      GAME_LOGIC = startGameLogic(WORLD_STUB.port(), ENTITY_STUB.port());
    }
    if (GAME_SESSION == null) {
      GAME_SESSION = startGameSession(GAME_LOGIC.grpcPort(), ACCOUNT_STUB.port());
    }
  }

  private static CrossServiceAppHarness.GameLogicHolder startGameLogic(
      int worldPort, int entityPort) {
    return CrossServiceAppHarness.startGameLogic(
        WORLD_STUB.endpoint(), ENTITY_STUB.endpoint(), null);
  }

  private static CrossServiceAppHarness.GameSessionHolder startGameSession(
      int gameLogicPort, int accountPort) {
    return CrossServiceAppHarness.startGameSession(
        gameLogicPort,
        accountPort,
        props -> {
          props.put("game.logic.default-room-id", LookTestFixtures.ROOM_ID);
          props.put("firemud.redis.host", REDIS.getHost());
          props.put("firemud.redis.port", REDIS.getMappedPort(6379));
          props.put("firemud.postgres.host", POSTGRES.getHost());
          props.put("firemud.postgres.port", POSTGRES.getMappedPort(5432));
          props.put("firemud.postgres.database", POSTGRES.getDatabaseName());
          props.put("firemud.postgres.username", POSTGRES.getUsername());
          props.put("firemud.postgres.password", POSTGRES.getPassword());
          props.put("firemud.database.enabled", "true");
          props.put("spring.jpa.hibernate.ddl-auto", "none");
        });
  }

  private long prepareGameInstance() {
    return insertGameInstance(true);
  }

  private long prepareAdditionalGameInstance() {
    return insertGameInstance(false);
  }

  private long insertGameInstance(boolean clearExisting) {
    DataSource dataSource = GAME_SESSION.bean(DataSource.class);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute(
        """
        CREATE TABLE IF NOT EXISTS game_instances (
          id BIGSERIAL PRIMARY KEY,
          tenant_id BIGINT NOT NULL,
          runtime_version VARCHAR(100) NOT NULL,
          script_patch_version VARCHAR(100),
          script_patch_pinned_at TIMESTAMP NULL,
          script_patch_pinned_by VARCHAR(200) NULL,
          script_patch_pinned_reason VARCHAR(500) NULL,
          owner_account_id BIGINT NOT NULL,
          status VARCHAR(20) NOT NULL
        )
        """);
    if (clearExisting) {
      GAME_SESSION
          .bean(StringRedisTemplate.class)
          .getConnectionFactory()
          .getConnection()
          .serverCommands()
          .flushAll();
      jdbc.update("DELETE FROM game_instances");
      GAME_SESSION.bean(ScreenBufferService.class).clear(TENANT_ID, 1L, ACCOUNT_ID);
    }
    return Optional.ofNullable(
            jdbc.queryForObject(
                "INSERT INTO game_instances (tenant_id, runtime_version, script_patch_version, owner_account_id, status) VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                TENANT_ID,
                "0.1.0",
                "initial",
                ACCOUNT_ID,
                "ACTIVE"))
        .orElseThrow(() -> new IllegalStateException("Game instance insert did not return an id"));
  }

  private List<String> runLookSequence(long sessionId) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    URI uri = URI.create("ws://localhost:" + GAME_SESSION.port() + "/ws/game");
    CopyOnWriteArrayList<String> responses = new CopyOnWriteArrayList<>();

    WebSocket webSocket =
        client
            .newWebSocketBuilder()
            .header("X-Game-Instance-Id", String.valueOf(sessionId))
            .header("X-Tenant-Id", String.valueOf(TENANT_ID))
            .buildAsync(
                uri,
                new Listener() {
                  @Override
                  public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                  }

                  @Override
                  public CompletionStage<?> onText(
                      WebSocket webSocket, CharSequence data, boolean last) {
                    responses.add(data.toString());
                    webSocket.request(1);
                    return Listener.super.onText(webSocket, data, last);
                  }
                })
            .join();

    webSocket.sendText("WORLDS", true).join();
    waitForResponseCount(responses, 1);
    webSocket.sendText("LOGIN demo@example.com swordfish", true).join();
    waitForResponseCount(responses, 2);
    webSocket.sendText("PLAY demo", true).join();
    waitForResponseCount(responses, 3);
    webSocket.sendText("LOOK", true).join();
    waitForResponseCount(responses, 4);
    WORLD_STUB.triggerNotFound("room missing for regression");
    webSocket.sendText("LOOK", true).join();
    waitForResponseCount(responses, 5);
    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    return responses;
  }

  private List<String> runMovementSequence(long sessionId) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    URI uri = URI.create("ws://localhost:" + GAME_SESSION.port() + "/ws/game");
    CopyOnWriteArrayList<String> responses = new CopyOnWriteArrayList<>();

    WebSocket webSocket =
        client
            .newWebSocketBuilder()
            .header("X-Game-Instance-Id", String.valueOf(sessionId))
            .buildAsync(
                uri,
                new Listener() {
                  @Override
                  public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                  }

                  @Override
                  public CompletionStage<?> onText(
                      WebSocket webSocket, CharSequence data, boolean last) {
                    responses.add(data.toString());
                    webSocket.request(1);
                    return Listener.super.onText(webSocket, data, last);
                  }
                })
            .join();

    webSocket.sendText("LOGIN demo@example.com swordfish", true).join();
    waitForResponseCount(responses, 1);
    webSocket.sendText("PLAY demo", true).join();
    waitForResponseCount(responses, 2);
    webSocket.sendText("north", true).join();
    waitForResponseCount(responses, 3);
    webSocket.sendText("LOOK", true).join();
    waitForResponseCount(responses, 4);
    webSocket.sendText("west", true).join();
    waitForResponseCount(responses, 5);
    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    return responses;
  }

  private List<String> runQuickLookSequence(long sessionId) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    URI uri = URI.create("ws://localhost:" + GAME_SESSION.port() + "/ws/game");
    CopyOnWriteArrayList<String> responses = new CopyOnWriteArrayList<>();

    WebSocket webSocket =
        client
            .newWebSocketBuilder()
            .header("X-Game-Instance-Id", String.valueOf(sessionId))
            .buildAsync(
                uri,
                new Listener() {
                  @Override
                  public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                  }

                  @Override
                  public CompletionStage<?> onText(
                      WebSocket webSocket, CharSequence data, boolean last) {
                    responses.add(data.toString());
                    webSocket.request(1);
                    return Listener.super.onText(webSocket, data, last);
                  }
                })
            .join();

    webSocket.sendText("LOGIN demo@example.com swordfish", true).join();
    waitForResponseCount(responses, 1);
    webSocket.sendText("PLAY demo", true).join();
    waitForResponseCount(responses, 2);
    webSocket.sendText("QUICKLOOK", true).join();
    waitForResponseCount(responses, 3);
    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    return responses;
  }

  private List<String> runMoveThenDisconnect(long sessionId) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    URI uri = URI.create("ws://localhost:" + GAME_SESSION.port() + "/ws/game");
    CopyOnWriteArrayList<String> responses = new CopyOnWriteArrayList<>();

    WebSocket webSocket =
        client
            .newWebSocketBuilder()
            .header("X-Game-Instance-Id", String.valueOf(sessionId))
            .buildAsync(
                uri,
                new Listener() {
                  @Override
                  public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                  }

                  @Override
                  public CompletionStage<?> onText(
                      WebSocket webSocket, CharSequence data, boolean last) {
                    responses.add(data.toString());
                    webSocket.request(1);
                    return Listener.super.onText(webSocket, data, last);
                  }
                })
            .join();

    webSocket.sendText("LOGIN demo@example.com swordfish", true).join();
    waitForResponseCount(responses, 1);
    webSocket.sendText("PLAY demo", true).join();
    waitForResponseCount(responses, 2);
    webSocket.sendText("north", true).join();
    waitForResponseCount(responses, 3);
    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    return responses;
  }

  private List<String> runPlayThenDisconnect(long sessionId) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    URI uri = URI.create("ws://localhost:" + GAME_SESSION.port() + "/ws/game");
    CopyOnWriteArrayList<String> responses = new CopyOnWriteArrayList<>();

    WebSocket webSocket =
        client
            .newWebSocketBuilder()
            .header("X-Game-Instance-Id", String.valueOf(sessionId))
            .buildAsync(
                uri,
                new Listener() {
                  @Override
                  public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                  }

                  @Override
                  public CompletionStage<?> onText(
                      WebSocket webSocket, CharSequence data, boolean last) {
                    responses.add(data.toString());
                    webSocket.request(1);
                    return Listener.super.onText(webSocket, data, last);
                  }
                })
            .join();

    webSocket.sendText("LOGIN demo@example.com swordfish", true).join();
    waitForResponseCount(responses, 1);
    webSocket.sendText("PLAY demo", true).join();
    waitForResponseCount(responses, 2);
    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    return responses;
  }

  private List<String> runLookAfterReconnect(long sessionId) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    URI uri = URI.create("ws://localhost:" + GAME_SESSION.port() + "/ws/game");
    CopyOnWriteArrayList<String> responses = new CopyOnWriteArrayList<>();
    AtomicInteger received = new AtomicInteger();
    CompletableFuture<Void> ready = new CompletableFuture<>();

    WebSocket webSocket =
        client
            .newWebSocketBuilder()
            .header("X-Game-Instance-Id", String.valueOf(sessionId))
            .buildAsync(
                uri,
                new Listener() {
                  @Override
                  public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                  }

                  @Override
                  public CompletionStage<?> onText(
                      WebSocket webSocket, CharSequence data, boolean last) {
                    responses.add(data.toString());
                    int count = received.incrementAndGet();
                    webSocket.request(1);
                    if (count >= 4) {
                      ready.complete(null);
                    }
                    return Listener.super.onText(webSocket, data, last);
                  }
                })
            .join();

    webSocket.sendText("LOGIN demo@example.com swordfish", true).join();
    waitForResponseCount(responses, 1);
    webSocket.sendText("PLAY demo", true).join();
    ready.get(COMMAND_WAIT.toMillis(), TimeUnit.MILLISECONDS);
    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    return responses;
  }

  private List<String> runPlayAfterReconnect(long sessionId) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    URI uri = URI.create("ws://localhost:" + GAME_SESSION.port() + "/ws/game");
    CopyOnWriteArrayList<String> responses = new CopyOnWriteArrayList<>();

    WebSocket webSocket =
        client
            .newWebSocketBuilder()
            .header("X-Game-Instance-Id", String.valueOf(sessionId))
            .buildAsync(
                uri,
                new Listener() {
                  @Override
                  public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                  }

                  @Override
                  public CompletionStage<?> onText(
                      WebSocket webSocket, CharSequence data, boolean last) {
                    responses.add(data.toString());
                    webSocket.request(1);
                    return Listener.super.onText(webSocket, data, last);
                  }
                })
            .join();

    webSocket.sendText("LOGIN demo@example.com swordfish", true).join();
    waitForResponseCount(responses, 1);
    webSocket.sendText("PLAY demo", true).join();
    waitForResponseCount(responses, 2);
    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    return responses;
  }

  private List<String> runPlayAfterReconnectExpectingFreshLook(long sessionId) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    URI uri = URI.create("ws://localhost:" + GAME_SESSION.port() + "/ws/game");
    CopyOnWriteArrayList<String> responses = new CopyOnWriteArrayList<>();

    WebSocket webSocket =
        client
            .newWebSocketBuilder()
            .header("X-Game-Instance-Id", String.valueOf(sessionId))
            .buildAsync(
                uri,
                new Listener() {
                  @Override
                  public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                  }

                  @Override
                  public CompletionStage<?> onText(
                      WebSocket webSocket, CharSequence data, boolean last) {
                    responses.add(data.toString());
                    webSocket.request(1);
                    return Listener.super.onText(webSocket, data, last);
                  }
                })
            .join();

    webSocket.sendText("LOGIN demo@example.com swordfish", true).join();
    waitForResponseCount(responses, 1);
    webSocket.sendText("PLAY demo", true).join();
    waitForResponseMatching(responses, response -> response.startsWith("OK LOOK"));
    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    return responses;
  }

  private void waitForResponseCount(List<String> responses, int expected)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + COMMAND_WAIT.toMillis();
    while (System.currentTimeMillis() < deadline) {
      if (responses.size() >= expected) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError(
        "Expected at least "
            + expected
            + " responses, got "
            + responses.size()
            + " responses: "
            + responses);
  }

  private void waitForResponseMatching(List<String> responses, Predicate<String> predicate)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + COMMAND_WAIT.toMillis();
    while (System.currentTimeMillis() < deadline) {
      if (responses.stream().anyMatch(predicate)) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Expected a response matching predicate, got " + responses);
  }

  private void assertMetricEventually(String meterName, double expectedValue, String... tags)
      throws Exception {
    MeterRegistry registry = GAME_SESSION.bean(MeterRegistry.class);
    long deadline = System.currentTimeMillis() + COMMAND_WAIT.toMillis();
    while (System.currentTimeMillis() < deadline) {
      Counter counter = registry.find(meterName).tags(tags).counter();
      if (counter != null && counter.count() >= expectedValue) {
        return;
      }
      Thread.sleep(100);
    }
    Counter counter = registry.find(meterName).tags(tags).counter();
    double actual = counter == null ? 0.0 : counter.count();
    throw new AssertionError(
        "Metric " + meterName + " did not reach " + expectedValue + "; actual=" + actual);
  }

  private TrackingSocket connectTrackingSocket(URI uri, long sessionId) {
    HttpClient client = HttpClient.newHttpClient();
    CopyOnWriteArrayList<String> responses = new CopyOnWriteArrayList<>();

    WebSocket webSocket =
        client
            .newWebSocketBuilder()
            .header("X-Game-Instance-Id", String.valueOf(sessionId))
            .header("X-Tenant-Id", String.valueOf(TENANT_ID))
            .buildAsync(
                uri,
                new Listener() {
                  @Override
                  public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                  }

                  @Override
                  public CompletionStage<?> onText(
                      WebSocket webSocket, CharSequence data, boolean last) {
                    responses.add(data.toString());
                    webSocket.request(1);
                    return Listener.super.onText(webSocket, data, last);
                  }
                })
            .join();
    return new TrackingSocket(webSocket, responses);
  }

  private final class TrackingSocket implements AutoCloseable {
    private final WebSocket webSocket;
    private final CopyOnWriteArrayList<String> responses;

    private TrackingSocket(WebSocket webSocket, CopyOnWriteArrayList<String> responses) {
      this.webSocket = webSocket;
      this.responses = responses;
    }

    private void sendAndAwait(String command, int expectedResponses) throws InterruptedException {
      webSocket.sendText(command, true).join();
      waitForResponseCount(responses, expectedResponses);
    }

    private List<String> responses() {
      return responses;
    }

    @Override
    public void close() {
      try {
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
      } catch (CompletionException ex) {
        if (!(ex.getCause() instanceof IOException)) {
          throw ex;
        }
      }
    }
  }

  private static final class AccountServiceStub implements AutoCloseable {
    private final Server server;
    private final int port;
    private volatile boolean gameplayAdmissionAllowed = true;

    AccountServiceStub(int port) throws IOException {
      this.port = port;
      this.server =
          ServerBuilder.forPort(port)
              .addService(
                  new AccountServiceGrpc.AccountServiceImplBase() {
                    @Override
                    public void authenticate(
                        AuthenticateRequest request,
                        io.grpc.stub.StreamObserver<AuthenticateResponse> responseObserver) {
                      AuthenticateResponse response =
                          AuthenticateResponse.newBuilder()
                              .setAccountId(String.valueOf(ACCOUNT_ID))
                              .setAuthToken("stub-token")
                              .build();
                      responseObserver.onNext(response);
                      responseObserver.onCompleted();
                    }

                    @Override
                    public void getTenantMembershipForRuntime(
                        GetTenantMembershipForRuntimeRequest request,
                        StreamObserver<GetTenantMembershipForRuntimeResponse> responseObserver) {
                      responseObserver.onNext(
                          GetTenantMembershipForRuntimeResponse.newBuilder()
                              .setAccountId(request.getAccountId())
                              .setTenantId(request.getTenantId())
                              .setGameplayAdmissionAllowed(gameplayAdmissionAllowed)
                              .setMembershipVersion(1L)
                              .setEvaluatedAt("2026-03-30T00:00:00Z")
                              .build());
                      responseObserver.onCompleted();
                    }

                    @Override
                    public void getTenantEntitlementsForRuntime(
                        GetTenantEntitlementsForRuntimeRequest request,
                        StreamObserver<GetTenantEntitlementsForRuntimeResponse> responseObserver) {
                      responseObserver.onNext(
                          GetTenantEntitlementsForRuntimeResponse.newBuilder()
                              .setTenantId(request.getTenantId())
                              .setGameplayAvailable(true)
                              .setEntitlementVersion(1L)
                              .setTenantBillingSequence(1L)
                              .setEvaluatedAt("2026-03-30T00:00:00Z")
                              .build());
                      responseObserver.onCompleted();
                    }
                  })
              .build()
              .start();
    }

    int port() {
      return port;
    }

    void allowGameplayAdmission() {
      gameplayAdmissionAllowed = true;
    }

    void denyGameplayAdmission() {
      gameplayAdmissionAllowed = false;
    }

    @Override
    public void close() {
      if (server != null) {
        server.shutdownNow();
      }
    }
  }
}
