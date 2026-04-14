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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.EnsurePublicProductionPlayerMembershipRequest;
import net.firedevops.firemud.account.v1.EnsurePublicProductionPlayerMembershipResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.test.ChatTestFixtures;
import net.firedevops.firemud.gamesession.test.GameInstanceTestFixtures;
import net.firedevops.firemud.gamesession.test.stubs.ChatEntityManagementStubServer;
import net.firedevops.firemud.gamesession.test.stubs.SocialGroupsStubServer;
import net.firedevops.firemud.gamesession.test.stubs.WorldManagementStubServer;
import net.firedevops.firemud.socialgroups.v1.ChatType;
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
class CommunicationWebSocketCrossServiceTest {
  private static final Duration COMMAND_WAIT = Duration.ofSeconds(5);
  private static final long TENANT_ID = 1L;
  private static final long ACCOUNT_ID = Long.parseLong(ChatTestFixtures.PLAYER_EMBERLINE);
  private static final long SORA_ACCOUNT_ID = Long.parseLong(ChatTestFixtures.PLAYER_SORA);
  private static final long DEMO_WORLD_INSTANCE_ID = 1L;

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
  private static ChatEntityManagementStubServer ENTITY_STUB;
  private static SocialGroupsStubServer SOCIAL_STUB;
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

    SocialGroupsStubServer socialStub = SOCIAL_STUB;
    SOCIAL_STUB = null;
    if (socialStub != null) {
      socialStub.close();
    }

    ChatEntityManagementStubServer entityStub = ENTITY_STUB;
    ENTITY_STUB = null;
    if (entityStub != null) {
      entityStub.close();
    }

    WorldManagementStubServer worldStub = WORLD_STUB;
    WORLD_STUB = null;
    if (worldStub != null) {
      worldStub.close();
    }

    AccountServiceStub accountStub = ACCOUNT_STUB;
    ACCOUNT_STUB = null;
    if (accountStub != null) {
      accountStub.close();
    }
  }

  @Test
  void websocketSayFlowReportsCanonicalTranscriptAndMetrics() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    List<String> responses = runCommunicationSequence(sessionId, "SAY hello travelers");

    assertThat(responses).hasSizeGreaterThanOrEqualTo(3);
    assertThat(responses.get(0)).startsWith("OK LOGIN");
    assertThat(responses.get(1)).startsWith("OK PLAY");
    assertThat(responses.get(2)).contains(ChatTestFixtures.canonicalSayText());
    assertThat(SOCIAL_STUB.lastRequest())
        .hasValueSatisfying(
            request -> {
              assertThat(request.getContent()).isEqualTo("hello travelers");
              assertThat(request.getType())
                  .isEqualTo(net.firedevops.firemud.socialgroups.v1.ChatType.CHAT_TYPE_SAY);
            });

    assertMetricEventually("gamesession.command.say.invocations", 1.0);
  }

  @Test
  void websocketWhisperFlowReportsCanonicalTranscriptAndMetadata() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    List<String> responses = runCommunicationSequence(sessionId, "WHISPER Sora keep quiet");

    assertThat(responses).hasSizeGreaterThanOrEqualTo(3);
    assertThat(responses.get(2)).contains(ChatTestFixtures.canonicalWhisperText());
    assertThat(SOCIAL_STUB.lastRequest())
        .hasValueSatisfying(
            request -> {
              assertThat(request.getContent()).isEqualTo("keep quiet");
              assertThat(request.getType()).isEqualTo(ChatType.CHAT_TYPE_WHISPER);
              assertThat(request.getRecipientId()).isEqualTo(ChatTestFixtures.PLAYER_SORA);
            });

    assertMetricEventually("gamesession.command.whisper.invocations", 1.0);
  }

  @Test
  void websocketTellFlowReportsCanonicalTranscriptAndMetadata() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();
    seedLiveTargetSession();
    List<String> responses = runCommunicationSequence(sessionId, "TELL Sora meet me at the forge");

    assertThat(responses).hasSizeGreaterThanOrEqualTo(3);
    assertThat(responses.get(2)).contains(ChatTestFixtures.canonicalTellText());
    assertThat(SOCIAL_STUB.lastRequest())
        .hasValueSatisfying(
            request -> {
              assertThat(request.getContent()).isEqualTo("meet me at the forge");
              assertThat(request.getType()).isEqualTo(ChatType.CHAT_TYPE_TELL);
              assertThat(request.getRecipientId()).isEqualTo(ChatTestFixtures.PLAYER_SORA);
            });

    assertMetricEventually("gamesession.command.tell.invocations", 1.0);
  }

  @Test
  void websocketWhisperPushesTargetAndObserverViewsToLiveRecipients() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();

    try (RecordingWebSocketClient actor = openSessionClient(sessionId, "actor-conn");
        RecordingWebSocketClient target = openSessionClient(sessionId, "target-conn");
        RecordingWebSocketClient observer = openSessionClient(sessionId, "observer-conn")) {
      actor.send("LOGIN demo@example.com swordfish");
      actor.awaitResponseCount(1);
      actor.send("PLAY demo");
      actor.awaitResponseCount(2);

      target.send("LOGIN demo@example.com swordfish");
      target.awaitResponseCount(1);
      target.send("PLAY demo Sora");
      target.awaitResponseCount(2);

      observer.send("LOGIN demo@example.com swordfish");
      observer.awaitResponseCount(1);
      observer.send("PLAY demo Nyx");
      observer.awaitResponseCount(2);

      actor.send("WHISPER Sora Keep quiet");
      actor.awaitContains(ChatTestFixtures.canonicalWhisperText());
      target.awaitContains(ChatTestFixtures.canonicalWhisperTargetText());
      observer.awaitContains(ChatTestFixtures.canonicalWhisperObserverMetadataText());
    }
  }

  @Test
  void websocketTellPushesTargetViewToLiveRecipient() throws Exception {
    ensureTestServicesStarted();
    long sessionId = prepareGameInstance();

    try (RecordingWebSocketClient actor = openSessionClient(sessionId, "actor-tell-conn");
        RecordingWebSocketClient target = openSessionClient(sessionId, "target-tell-conn")) {
      actor.send("LOGIN demo@example.com swordfish");
      actor.awaitResponseCount(1);
      actor.send("PLAY demo Emberline");
      actor.awaitResponseCount(2);

      target.send("LOGIN demo@example.com swordfish");
      target.awaitResponseCount(1);
      target.send("PLAY demo Sora");
      target.awaitResponseCount(2);

      actor.send("TELL Sora Meet me at the forge");
      actor.awaitContains(ChatTestFixtures.canonicalTellText());
      target.awaitContains(ChatTestFixtures.canonicalTellTargetText());
    }
  }

  private static synchronized void ensureTestServicesStarted() throws Exception {
    if (ACCOUNT_STUB == null) {
      ACCOUNT_STUB = new AccountServiceStub(TestSocketUtils.findAvailableTcpPort());
    }
    if (WORLD_STUB == null) {
      WORLD_STUB = new WorldManagementStubServer(TestSocketUtils.findAvailableTcpPort());
    }
    if (ENTITY_STUB == null) {
      ENTITY_STUB = new ChatEntityManagementStubServer(TestSocketUtils.findAvailableTcpPort());
    }
    if (SOCIAL_STUB == null) {
      SOCIAL_STUB = new SocialGroupsStubServer(TestSocketUtils.findAvailableTcpPort());
    }
    if (GAME_LOGIC == null) {
      GAME_LOGIC = startGameLogic(WORLD_STUB.port(), ENTITY_STUB.port(), SOCIAL_STUB.port());
    }
    if (GAME_SESSION == null) {
      GAME_SESSION = startGameSession(GAME_LOGIC.grpcPort(), ACCOUNT_STUB.port());
    }
  }

  private static CrossServiceAppHarness.GameLogicHolder startGameLogic(
      int worldPort, int entityPort, int socialPort) {
    return CrossServiceAppHarness.startGameLogic(
        WORLD_STUB.endpoint(), ENTITY_STUB.endpoint(), SOCIAL_STUB.endpoint());
  }

  private static CrossServiceAppHarness.GameSessionHolder startGameSession(
      int gameLogicPort, int accountPort) {
    return CrossServiceAppHarness.startGameSession(
        gameLogicPort,
        accountPort,
        props -> {
          props.put("game.logic.default-room-id", ChatTestFixtures.ROOM_ID);
          props.put("firemud.redis.host", REDIS.getHost());
          props.put("firemud.redis.port", REDIS.getMappedPort(6379));
          props.put("firemud.postgres.host", POSTGRES.getHost());
          props.put("firemud.postgres.port", POSTGRES.getMappedPort(5432));
          props.put("firemud.postgres.database", POSTGRES.getDatabaseName());
          props.put("firemud.postgres.username", POSTGRES.getUsername());
          props.put("firemud.postgres.password", POSTGRES.getPassword());
          props.put("firemud.database.enabled", "true");
          props.put("spring.jpa.hibernate.ddl-auto", "none");
          props.put("firemud.services.entityManagementService", ENTITY_STUB.endpoint());
        });
  }

  private long prepareGameInstance() {
    GAME_SESSION
        .bean(StringRedisTemplate.class)
        .getConnectionFactory()
        .getConnection()
        .serverCommands()
        .flushAll();
    GAME_SESSION
        .bean(ScreenBufferService.class)
        .clear(TENANT_ID, DEMO_WORLD_INSTANCE_ID, ACCOUNT_ID);
    GAME_SESSION
        .bean(ScreenBufferService.class)
        .clear(TENANT_ID, DEMO_WORLD_INSTANCE_ID, Long.parseLong(ChatTestFixtures.PLAYER_SORA));
    GAME_SESSION
        .bean(ScreenBufferService.class)
        .clear(TENANT_ID, DEMO_WORLD_INSTANCE_ID, Long.parseLong(ChatTestFixtures.PLAYER_NYX));
    JdbcTemplate jdbc = new JdbcTemplate(GAME_SESSION.bean(javax.sql.DataSource.class));
    GameInstanceTestFixtures.ensureGameInstancesTable(jdbc);
    jdbc.update("DELETE FROM game_instances");
    return GameInstanceTestFixtures.insertRunningGameInstance(jdbc, TENANT_ID, ACCOUNT_ID, 7L);
  }

  private void seedLiveTargetSession() {
    SessionContextService sessionContextService = GAME_SESSION.bean(SessionContextService.class);
    sessionContextService.save(
        new SessionContext(
            90210L,
            TENANT_ID,
            Long.parseLong(ChatTestFixtures.PLAYER_SORA),
            "sora@example.com",
            Long.parseLong(ChatTestFixtures.PLAYER_SORA),
            "Sora",
            DEMO_WORLD_INSTANCE_ID,
            ChatTestFixtures.ROOM_ID,
            "target-jwt"));
  }

  private List<String> runCommunicationSequence(long sessionId, String commandText)
      throws Exception {
    try (RecordingWebSocketClient client =
        openSessionClient(sessionId, "flow-" + commandText.hashCode())) {
      client.send("LOGIN demo@example.com swordfish");
      client.awaitResponseCount(1);
      client.send("PLAY demo");
      client.awaitResponseCount(2);
      client.send(commandText);
      client.awaitResponseCount(3);
      return List.copyOf(client.responses);
    }
  }

  private RecordingWebSocketClient openSessionClient(long sessionId, String proxyConnectionId) {
    return new RecordingWebSocketClient(sessionId, proxyConnectionId);
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
        "Expected at least " + expected + " responses, got " + responses.size());
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

  private static final class AccountServiceStub implements AutoCloseable {
    private final Server server;
    private final int port;

    AccountServiceStub(int port) throws IOException {
      this.port = port;
      this.server =
          ServerBuilder.forPort(port)
              .addService(
                  new AccountServiceGrpc.AccountServiceImplBase() {
                    @Override
                    public void authenticate(
                        AuthenticateRequest request,
                        StreamObserver<AuthenticateResponse> responseObserver) {
                      long accountId =
                          switch (request.getUsername()) {
                            case "sora@example.com" -> SORA_ACCOUNT_ID;
                            default -> ACCOUNT_ID;
                          };
                      AuthenticateResponse response =
                          AuthenticateResponse.newBuilder()
                              .setAccountId(String.valueOf(accountId))
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
                              .setGameplayAdmissionAllowed(true)
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

                    @Override
                    public void ensurePublicProductionPlayerMembership(
                        EnsurePublicProductionPlayerMembershipRequest request,
                        StreamObserver<EnsurePublicProductionPlayerMembershipResponse>
                            responseObserver) {
                      responseObserver.onNext(
                          EnsurePublicProductionPlayerMembershipResponse.newBuilder()
                              .setAccountId(request.getAccountId())
                              .setTenantId(request.getTenantId())
                              .setRealmSlug(request.getRealmSlug())
                              .setGameplayAdmissionAllowed(true)
                              .setMembershipVersion(1L)
                              .setCreated(true)
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

    @Override
    public void close() {
      if (server != null) {
        server.shutdownNow();
      }
    }
  }

  private final class RecordingWebSocketClient implements AutoCloseable {
    private final CopyOnWriteArrayList<String> responses = new CopyOnWriteArrayList<>();
    private final AtomicInteger received = new AtomicInteger();
    private final WebSocket webSocket;

    private RecordingWebSocketClient(long sessionId, String proxyConnectionId) {
      HttpClient client = HttpClient.newHttpClient();
      URI uri = URI.create("ws://localhost:" + GAME_SESSION.port() + "/ws/game");
      this.webSocket =
          client
              .newWebSocketBuilder()
              .header("X-Game-Instance-Id", String.valueOf(sessionId))
              .header("X-Tenant-Id", String.valueOf(TENANT_ID))
              .header("X-Proxy-Connection-Id", proxyConnectionId)
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
                      received.incrementAndGet();
                      webSocket.request(1);
                      return Listener.super.onText(webSocket, data, last);
                    }
                  })
              .join();
    }

    private void send(String text) {
      webSocket.sendText(text, true).join();
    }

    private void awaitResponseCount(int expected) throws Exception {
      long deadline = System.currentTimeMillis() + COMMAND_WAIT.toMillis();
      while (System.currentTimeMillis() < deadline) {
        if (received.get() >= expected) {
          return;
        }
        Thread.sleep(50);
      }
      throw new AssertionError(
          "Expected at least " + expected + " responses, got " + received.get());
    }

    private void awaitContains(String expectedSubstring) throws Exception {
      long deadline = System.currentTimeMillis() + COMMAND_WAIT.toMillis();
      while (System.currentTimeMillis() < deadline) {
        if (responses.stream().anyMatch(response -> response.contains(expectedSubstring))) {
          return;
        }
        Thread.sleep(50);
      }
      throw new AssertionError(
          "Expected a response containing '" + expectedSubstring + "', got " + responses);
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
}
