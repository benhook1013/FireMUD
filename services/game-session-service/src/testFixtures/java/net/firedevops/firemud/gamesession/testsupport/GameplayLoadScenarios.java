package net.firedevops.firemud.gamesession.testsupport;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.firedevops.firemud.gamesession.test.GameInstanceTestFixtures;

/** Shared load-oriented gameplay scenario helpers above the base cross-service stack. */
public final class GameplayLoadScenarios {

  private GameplayLoadScenarios() {}

  public static List<PlayerSeed> seedPlayers(
      GameplayCrossServiceStack stack,
      long tenantId,
      long gameplayInstanceId,
      long firstAccountId,
      int count,
      long gameTemplateId) {
    stack.resetScenarioState();
    stack.clearRedis();
    GameInstanceTestFixtures.ensureGameInstancesTable(stack.jdbc());
    stack.jdbc().update("DELETE FROM game_instances");

    long[] characterIds = new long[count];
    for (int i = 0; i < count; i++) {
      characterIds[i] = firstAccountId + i + 1;
    }
    stack.clearScreenBuffers(tenantId, gameplayInstanceId, characterIds);

    List<PlayerSeed> players = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      long accountId = characterIds[i];
      long sessionId =
          GameInstanceTestFixtures.insertRunningGameInstance(
              stack.jdbc(), tenantId, accountId, gameTemplateId);
      String email = "player" + (i + 1) + "@example.com";
      stack.accountStub().mapAccountId(email, accountId);
      players.add(new PlayerSeed(sessionId, accountId, email, "player-" + (i + 1)));
    }
    return List.copyOf(players);
  }

  public static GameplayWebSocketDriver openReadyPlayer(
      URI uri, Duration timeout, long tenantId, PlayerSeed player, String world, String readyText)
      throws Exception {
    GameplayWebSocketDriver driver =
        GameplayWebSocketDriver.connectGameplaySession(uri, timeout, tenantId, player.sessionId());
    try {
      driver.enterGameplayAndWaitReady(player.username(), "swordfish", world, readyText);
      return driver;
    } catch (Exception ex) {
      driver.close();
      throw ex;
    }
  }

  public record PlayerSeed(long sessionId, long accountId, String username, String label) {}
}
