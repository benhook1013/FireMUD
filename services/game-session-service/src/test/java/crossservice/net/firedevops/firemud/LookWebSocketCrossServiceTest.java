package net.firedevops.firemud;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.test.LookTestFixtures;
import net.firedevops.firemud.test.stubs.EntityManagementStubServer;
import net.firedevops.firemud.test.stubs.WorldManagementStubServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.TestSocketUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class LookWebSocketCrossServiceTest {
  private static final Duration COMMAND_WAIT = Duration.ofSeconds(5);
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
  private static GameLogicHolder GAME_LOGIC;
  private static GameSessionHolder GAME_SESSION;

  @AfterAll
  static synchronized void stopServices() {
    GameSessionHolder gameSession = GAME_SESSION;
    GAME_SESSION = null;
    if (gameSession != null) {
      gameSession.close();
    }

    GameLogicHolder gameLogic = GAME_LOGIC;
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
    long sessionId = prepareGameInstance();
    List<String> responses = runLookSequence(sessionId);

    assertThat(responses).hasSizeGreaterThanOrEqualTo(3);
    assertThat(responses.get(0)).startsWith("OK LOGIN");
    assertThat(responses.get(1).trim()).isEqualTo(LookTestFixtures.canonicalLookText().trim());
    assertThat(responses.get(2)).startsWith("ERROR ROOM_NOT_FOUND");

    assertMetricEventually("gamesession_command_look_invocations_total{tenantId=\"1\"}", 2.0);
    assertMetricEventually(
        "gamesession_command_look_failures_total{tenantId=\"1\",error=\"ROOM_NOT_FOUND\"}", 1.0);
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

  private static GameLogicHolder startGameLogic(int worldPort, int entityPort) {
    int grpcPort = TestSocketUtils.findAvailableTcpPort();
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("server.port", "0");
    props.put("grpc.server.port", String.valueOf(grpcPort));
    props.put("grpc.server.security.enabled", "false");
    props.put("firemud.grpc.plaintext", "true");
    props.put("firemud.services.worldManagementService", WORLD_STUB.endpoint());
    props.put("firemud.services.entityManagementService", ENTITY_STUB.endpoint());
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(GameLogicServiceApplication.class).properties(props).run();
    return new GameLogicHolder(context, grpcPort);
  }

  private static GameSessionHolder startGameSession(int gameLogicPort, int accountPort) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("server.port", "0");
    props.put("grpc.server.port", "0");
    props.put("firemud.services.gameLogicService", "localhost:" + gameLogicPort);
    props.put("firemud.services.accountService", "localhost:" + accountPort);
    props.put("firemud.grpc.plaintext", "true");
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
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(GameSessionServiceApplication.class).properties(props).run();
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    return new GameSessionHolder(context, port);
  }

  private long prepareGameInstance() {
    DataSource dataSource = GAME_SESSION.dataSource();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute(
        """
        CREATE TABLE IF NOT EXISTS game_instances (
          id BIGSERIAL PRIMARY KEY,
          tenant_id BIGINT NOT NULL,
          runtime_version VARCHAR(100) NOT NULL,
          script_patch_version VARCHAR(100),
          owner_account_id BIGINT NOT NULL,
          status VARCHAR(20) NOT NULL
        )
        """);
    jdbc.update("DELETE FROM game_instances");
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
                    if (count >= 3) {
                      ready.complete(null);
                    }
                    return Listener.super.onText(webSocket, data, last);
                  }
                })
            .join();

    webSocket.sendText("LOGIN demo@example.com swordfish", true).join();
    waitForResponseCount(responses, 1);
    webSocket.sendText("LOOK", true).join();
    waitForResponseCount(responses, 2);
    WORLD_STUB.triggerNotFound("room missing for regression");
    webSocket.sendText("LOOK", true).join();
    ready.get(COMMAND_WAIT.toMillis(), TimeUnit.MILLISECONDS);
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
        "Expected at least " + expected + " responses, got " + responses.size());
  }

  private void assertMetricEventually(String metric, double expectedValue) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    URI uri = URI.create("http://localhost:" + GAME_SESSION.port() + "/actuator/prometheus");
    long deadline = System.currentTimeMillis() + COMMAND_WAIT.toMillis();
    while (System.currentTimeMillis() < deadline) {
      HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.body().contains(metric + " " + expectedValue)) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("Metric " + metric + " did not reach " + expectedValue);
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
                        io.grpc.stub.StreamObserver<AuthenticateResponse> responseObserver) {
                      AuthenticateResponse response =
                          AuthenticateResponse.newBuilder()
                              .setAccountId(String.valueOf(ACCOUNT_ID))
                              .setAuthToken("stub-token")
                              .build();
                      responseObserver.onNext(response);
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

  private static final class GameLogicHolder {
    private final ConfigurableApplicationContext context;
    private final int grpcPort;

    GameLogicHolder(ConfigurableApplicationContext context, int grpcPort) {
      this.context = context;
      this.grpcPort = grpcPort;
    }

    int grpcPort() {
      return grpcPort;
    }

    void close() {
      context.close();
    }
  }

  private static final class GameSessionHolder {
    private final ConfigurableApplicationContext context;
    private final int webPort;

    GameSessionHolder(ConfigurableApplicationContext context, int webPort) {
      this.context = context;
      this.webPort = webPort;
    }

    int port() {
      return webPort;
    }

    DataSource dataSource() {
      return context.getBean(DataSource.class);
    }

    void close() {
      context.close();
    }
  }
}
