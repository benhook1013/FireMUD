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
  private static final Duration COMMAND_WAIT = Duration.ofSeconds(30);
  private static final Duration ENTRY_PHASE_BUDGET = Duration.ofSeconds(20);
  private static final Duration FOLLOW_UP_PHASE_BUDGET = Duration.ofSeconds(15);
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
    TimedResult<List<PlayerRunResult>> playerRuns =
        timed(
            () -> {
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
                return results;
              } finally {
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
              }
            });

    assertCompletesWithin(
        "concurrent LOGIN -> PLAY -> LOOK entry", playerRuns.duration(), ENTRY_PHASE_BUDGET);
    List<PlayerRunResult> results = playerRuns.result();
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
  }

  @Test
  void tenConcurrentPlayersCanMoveNorthAfterConcurrentEntry() throws Exception {
    ensureTestServicesStarted();
    List<GameplayLoadScenarios.PlayerSeed> players =
        GameplayLoadScenarios.seedPlayers(STACK, TENANT_ID, 1L, ACCOUNT_ID_BASE, CLIENT_COUNT, 7L);
    URI uri = URI.create("ws://localhost:" + gameSession().port() + "/ws/game");

    TimedResult<List<PlayerSessionDriver>> readyPlayers =
        timed(() -> openReadyPlayersConcurrently(uri, players));
    assertCompletesWithin(
        "concurrent ready-player bootstrap", readyPlayers.duration(), ENTRY_PHASE_BUDGET);
    List<PlayerSessionDriver> connectedPlayers = readyPlayers.result();
    try {
      TimedResult<Void> followUp =
          timed(
              () -> {
                runConcurrentNorthMovement(connectedPlayers);
                runConcurrentDestinationLook(connectedPlayers);
                return null;
              });
      assertCompletesWithin(
          "concurrent north movement plus destination LOOK churn",
          followUp.duration(),
          FOLLOW_UP_PHASE_BUDGET);

      SessionContextService sessionContextService = gameSession().bean(SessionContextService.class);
      for (PlayerSessionDriver connectedPlayer : connectedPlayers) {
        assertThat(sessionContextService.findBySessionId(connectedPlayer.player().sessionId()))
            .hasValueSatisfying(
                context ->
                    assertThat(context.roomInstanceId())
                        .isEqualTo(LookTestFixtures.DESTINATION_ROOM_ID));
      }

      GameplayAsyncAssertions.assertMetricEventually(
          gameSession().bean(io.micrometer.core.instrument.MeterRegistry.class),
          COMMAND_WAIT,
          "gamesession.command.move.invocations",
          CLIENT_COUNT);
    } finally {
      for (PlayerSessionDriver connectedPlayer : connectedPlayers) {
        connectedPlayer.driver().close();
      }
    }
  }

  private static synchronized void ensureTestServicesStarted() throws Exception {
    if (STACK == null) {
      STACK =
          GameplayCrossServiceStack.defaultDemoBuilder(POSTGRES, REDIS, ACCOUNT_ID_BASE).start();
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

  private List<PlayerSessionDriver> openReadyPlayersConcurrently(
      URI uri, List<GameplayLoadScenarios.PlayerSeed> players) throws Exception {
    return runConcurrently(
        players,
        player -> {
          GameplayWebSocketDriver driver =
              GameplayLoadScenarios.openReadyPlayer(
                  uri, COMMAND_WAIT, TENANT_ID, player, "demo", "Candle-lit Antechamber");
          return new PlayerSessionDriver(player, driver);
        });
  }

  private void runConcurrentNorthMovement(List<PlayerSessionDriver> connectedPlayers)
      throws Exception {
    runConcurrently(
        connectedPlayers,
        connectedPlayer -> {
          connectedPlayer.driver().send("north");
          connectedPlayer.driver().awaitCanonicalMoveOrLook(LookTestFixtures.DESTINATION_ROOM_ID);
          return null;
        });
  }

  private void runConcurrentDestinationLook(List<PlayerSessionDriver> connectedPlayers)
      throws Exception {
    runConcurrently(
        connectedPlayers,
        connectedPlayer -> {
          connectedPlayer.driver().send("LOOK");
          connectedPlayer.driver().awaitCanonicalLook(LookTestFixtures.DESTINATION_ROOM_ID);
          return null;
        });
  }

  private <T, R> List<R> runConcurrently(List<T> items, ConcurrentTask<T, R> task)
      throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(items.size());
    try {
      List<Future<R>> futures = new ArrayList<>();
      for (T item : items) {
        futures.add(
            executor.submit(
                () -> {
                  assertThat(start.await(COMMAND_WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
                  return task.run(item);
                }));
      }

      start.countDown();

      List<R> results = new ArrayList<>();
      for (Future<R> future : futures) {
        results.add(future.get(COMMAND_WAIT.toSeconds(), TimeUnit.SECONDS));
      }
      return results;
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private record PlayerRunResult(GameplayLoadScenarios.PlayerSeed player, List<String> responses) {}

  private record TimedResult<T>(T result, Duration duration) {}

  private record PlayerSessionDriver(
      GameplayLoadScenarios.PlayerSeed player, GameplayWebSocketDriver driver) {}

  @FunctionalInterface
  private interface ConcurrentTask<T, R> {
    R run(T item) throws Exception;
  }

  @FunctionalInterface
  private interface TimedOperation<T> {
    T run() throws Exception;
  }

  private <T> TimedResult<T> timed(TimedOperation<T> operation) throws Exception {
    long startedAt = System.nanoTime();
    T result = operation.run();
    return new TimedResult<>(result, Duration.ofNanos(System.nanoTime() - startedAt));
  }

  private void assertCompletesWithin(String phase, Duration actual, Duration budget) {
    assertThat(actual)
        .withFailMessage("%s took %s, expected within %s", phase, actual, budget)
        .isLessThanOrEqualTo(budget);
  }

  private static CrossServiceAppHarness.GameSessionHolder gameSession() {
    return STACK.gameSession();
  }
}
