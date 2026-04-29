package net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;

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
import net.firedevops.firemud.gamesession.test.LookTestFixtures;
import net.firedevops.firemud.gamesession.testsupport.GameplayAsyncAssertions;
import net.firedevops.firemud.gamesession.testsupport.GameplayCrossServiceStack;
import net.firedevops.firemud.gamesession.testsupport.GameplayLoadScenarios;
import net.firedevops.firemud.gamesession.testsupport.GameplayWebSocketDriver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
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
  void tenConcurrentPlayersCanLoginPlayAndLookAgainstRealCrossServiceStack() throws Exception {
    ensureTestServicesStarted();
    List<GameplayLoadScenarios.PlayerSeed> players =
        GameplayLoadScenarios.seedPlayers(STACK, TENANT_ID, 1L, ACCOUNT_ID_BASE, CLIENT_COUNT, 7L);
    URI uri = URI.create("ws://localhost:" + gameSession().port() + "/ws/game");
    CountDownLatch start = new CountDownLatch(1);

    ExecutorService executor = Executors.newFixedThreadPool(CLIENT_COUNT);
    try {
      List<Future<PlayerRunResult>> futures = new ArrayList<>();
      for (GameplayLoadScenarios.PlayerSeed player : players) {
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

      SessionContextService sessionContextService = gameSession().bean(SessionContextService.class);
      for (GameplayLoadScenarios.PlayerSeed player : players) {
        assertThat(sessionContextService.findBySessionId(player.sessionId()))
            .hasValueSatisfying(
                context -> {
                  assertThat(context.tenantId()).isEqualTo(TENANT_ID);
                  assertThat(context.gameInstanceId()).isEqualTo(1L);
                  assertThat(context.characterId()).isEqualTo(player.accountId());
                  assertThat(context.roomInstanceId()).isEqualTo(LookTestFixtures.ROOM_ID);
                });
      }

      GameplayAsyncAssertions.assertMetricEventually(
          gameSession().bean(io.micrometer.core.instrument.MeterRegistry.class),
          COMMAND_WAIT,
          "gamesession.command.look.invocations",
          CLIENT_COUNT);
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static synchronized void ensureTestServicesStarted() throws Exception {
    if (STACK == null) {
      STACK =
          GameplayCrossServiceStack.builder()
              .withPostgres(
                  POSTGRES.getHost(),
                  POSTGRES.getMappedPort(5432),
                  POSTGRES.getDatabaseName(),
                  POSTGRES.getUsername(),
                  POSTGRES.getPassword())
              .withRedis(REDIS.getHost(), REDIS.getMappedPort(6379))
              .withDefaultAccountId(ACCOUNT_ID_BASE)
              .start();
    }
  }

  private PlayerRunResult runPlayerSequence(
      URI uri, GameplayLoadScenarios.PlayerSeed player, CountDownLatch start) throws Exception {
    try (GameplayWebSocketDriver client =
        GameplayLoadScenarios.openReadyPlayer(
            uri, COMMAND_WAIT, TENANT_ID, player, "demo", "Candle-lit Antechamber")) {
      assertThat(start.await(COMMAND_WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
      client.send("LOOK");
      client.awaitStartsWith("OK LOOK");
      return new PlayerRunResult(player, List.copyOf(client.responses()));
    }
  }

  private record PlayerRunResult(GameplayLoadScenarios.PlayerSeed player, List<String> responses) {}

  private static CrossServiceAppHarness.GameSessionHolder gameSession() {
    return STACK.gameSession();
  }
}
