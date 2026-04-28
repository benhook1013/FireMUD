package net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.test.GameInstanceTestFixtures;
import net.firedevops.firemud.gamesession.test.LookTestFixtures;
import net.firedevops.firemud.gamesession.test.stubs.EntityManagementStubServer;
import net.firedevops.firemud.gamesession.test.stubs.WorldManagementStubServer;
import net.firedevops.firemud.gamesession.testsupport.GameplayWebSocketDriver;
import net.firedevops.firemud.test.AccountRuntimeStubServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.TestSocketUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class MultiplayerLoadProofCrossServiceTest {
  private static final Duration COMMAND_WAIT = Duration.ofSeconds(8);
  private static final long TENANT_ID = 1L;
  private static final int CLIENT_COUNT = 10;
  private static final long ACCOUNT_ID_BASE = 7000L;

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("firemud")
          .withUsername("firemud")
          .withPassword("firemud");

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);

  private static AccountRuntimeStubServer ACCOUNT_STUB;
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

    AccountRuntimeStubServer accountStub = ACCOUNT_STUB;
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
  void tenConcurrentPlayersCanLoginPlayAndLookAgainstRealCrossServiceStack() throws Exception {
    ensureTestServicesStarted();
    List<PlayerSeed> players = prepareGameInstances(CLIENT_COUNT);
    URI uri = URI.create("ws://localhost:" + GAME_SESSION.port() + "/ws/game");
    CountDownLatch start = new CountDownLatch(1);

    ExecutorService executor = Executors.newFixedThreadPool(CLIENT_COUNT);
    try {
      List<Future<PlayerRunResult>> futures = new ArrayList<>();
      for (PlayerSeed player : players) {
        futures.add(executor.submit(() -> runPlayerSequence(uri, player, start)));
      }

      start.countDown();

      List<PlayerRunResult> results = new ArrayList<>();
      for (Future<PlayerRunResult> future : futures) {
        results.add(future.get(COMMAND_WAIT.toSeconds(), TimeUnit.SECONDS));
      }

      assertThat(results).hasSize(CLIENT_COUNT);
      assertThat(results)
          .allSatisfy(
              result -> {
                assertThat(result.responses())
                    .anyMatch(payload -> payload.startsWith("OK LOGIN"))
                    .anyMatch(payload -> payload.startsWith("OK PLAY"))
                    .anyMatch(
                        payload ->
                            payload.startsWith("OK LOOK")
                                && payload.contains("Room: Candle-lit Antechamber"));
                assertThat(result.responses()).noneMatch(payload -> payload.startsWith("ERROR "));
              });

      SessionContextService sessionContextService = GAME_SESSION.bean(SessionContextService.class);
      for (PlayerSeed player : players) {
        assertThat(sessionContextService.findBySessionId(player.sessionId()))
            .hasValueSatisfying(
                context -> {
                  assertThat(context.tenantId()).isEqualTo(TENANT_ID);
                  assertThat(context.gameInstanceId()).isEqualTo(1L);
                  assertThat(context.characterId()).isEqualTo(player.accountId());
                  assertThat(context.roomInstanceId()).isEqualTo(LookTestFixtures.ROOM_ID);
                });
      }

      assertMetricEventually("gamesession.command.look.invocations", CLIENT_COUNT);
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static synchronized void ensureTestServicesStarted() throws Exception {
    if (ACCOUNT_STUB == null) {
      ACCOUNT_STUB = new AccountRuntimeStubServer(0);
      ACCOUNT_STUB.setDefaultAccountId(ACCOUNT_ID_BASE);
    }
    if (WORLD_STUB == null) {
      WORLD_STUB = new WorldManagementStubServer(TestSocketUtils.findAvailableTcpPort());
    }
    if (ENTITY_STUB == null) {
      ENTITY_STUB = new EntityManagementStubServer(TestSocketUtils.findAvailableTcpPort());
    }
    if (GAME_LOGIC == null) {
      GAME_LOGIC =
          CrossServiceAppHarness.startGameLogic(
              WORLD_STUB.endpoint(), ENTITY_STUB.endpoint(), null);
    }
    if (GAME_SESSION == null) {
      GAME_SESSION =
          CrossServiceAppHarness.startGameSession(
              GAME_LOGIC.grpcPort(),
              ACCOUNT_STUB.port(),
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
  }

  private List<PlayerSeed> prepareGameInstances(int count) {
    JdbcTemplate jdbc = new JdbcTemplate(GAME_SESSION.bean(javax.sql.DataSource.class));
    GameInstanceTestFixtures.ensureGameInstancesTable(jdbc);
    GAME_SESSION
        .bean(org.springframework.data.redis.core.StringRedisTemplate.class)
        .getConnectionFactory()
        .getConnection()
        .serverCommands()
        .flushAll();
    jdbc.update("DELETE FROM game_instances");

    List<PlayerSeed> players = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      long accountId = ACCOUNT_ID_BASE + i + 1;
      long sessionId =
          GameInstanceTestFixtures.insertRunningGameInstance(jdbc, TENANT_ID, accountId, 7L);
      ACCOUNT_STUB.mapAccountId("player" + (i + 1) + "@example.com", accountId);
      players.add(
          new PlayerSeed(
              sessionId, accountId, "player" + (i + 1) + "@example.com", "player-" + (i + 1)));
    }
    return players;
  }

  private PlayerRunResult runPlayerSequence(URI uri, PlayerSeed player, CountDownLatch start)
      throws Exception {
    try (GameplayWebSocketDriver client =
        GameplayWebSocketDriver.connect(
            uri,
            COMMAND_WAIT,
            java.util.Map.of(
                "X-Game-Instance-Id", Long.toString(player.sessionId()),
                "X-Tenant-Id", Long.toString(TENANT_ID)))) {
      assertThat(start.await(COMMAND_WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
      client.login(player.username(), "swordfish");
      client.play("demo");
      client.send("LOOK");
      client.awaitStartsWith("OK LOOK");
      return new PlayerRunResult(player, List.copyOf(client.responses()));
    }
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

  private record PlayerSeed(long sessionId, long accountId, String username, String label) {}

  private record PlayerRunResult(PlayerSeed player, List<String> responses) {}
}
