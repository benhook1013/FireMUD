package net.firedevops.firemud.gamesession.testsupport;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared load-oriented gameplay scenario helpers above the base cross-service stack. */
public final class GameplayLoadScenarios {
  private static final String DEFAULT_REALM_SLUG = "production";
  private static final String DEFAULT_POINTER_VERSION = "1";

  private GameplayLoadScenarios() {}

  public static List<PlayerSeed> seedPlayers(
      GameplayCrossServiceStack stack,
      long tenantId,
      long gameplayInstanceId,
      long firstAccountId,
      int count,
      long gameTemplateId) {
    long[] characterIds = new long[count];
    for (int i = 0; i < count; i++) {
      characterIds[i] = firstAccountId + i + 1;
    }
    stack.freshGameplayBaseline(
        tenantId, gameplayInstanceId, firstAccountId, gameTemplateId, characterIds);
    // Cross-service test bootstraps reserve runtime target 2 for the built-in sandbox pointer.
    // Skip that id so demo-player bootstrap instances do not inherit unrelated sandbox authority.
    stack.insertRunningGameInstance(tenantId, firstAccountId, gameTemplateId, false);

    List<PlayerSeed> players = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      long accountId = characterIds[i];
      long bootstrapGameInstanceId =
          stack.insertRunningGameInstance(tenantId, accountId, gameTemplateId, false);
      long sessionId = firstAccountId + 10_000L + i + 1;
      String email = "player" + (i + 1) + "@example.com";
      stack.accountStub().mapAccountId(email, accountId);
      players.add(
          new PlayerSeed(
              sessionId, bootstrapGameInstanceId, accountId, email, "player-" + (i + 1)));
    }
    return List.copyOf(players);
  }

  public static GameplayWebSocketDriver openReadyPlayer(
      URI uri, Duration timeout, long tenantId, PlayerSeed player, String world, String readyText)
      throws Exception {
    GameplayWebSocketDriver driver =
        GameplayWebSocketDriver.connectGameplaySession(
            uri,
            timeout,
            tenantId,
            player.bootstrapGameInstanceId(),
            Map.of(
                "X-Firemud-Transport-Session-Id", Long.toString(player.sessionId()),
                "X-World-Slug", world,
                "X-Realm-Slug", DEFAULT_REALM_SLUG,
                "X-Pointer-Version", DEFAULT_POINTER_VERSION));
    try {
      driver.enterGameplayAndWaitReady(player.username(), "swordfish", world, readyText);
      return driver;
    } catch (Exception ex) {
      driver.close();
      throw ex;
    }
  }

  public record PlayerSeed(
      long sessionId,
      long bootstrapGameInstanceId,
      long accountId,
      String username,
      String label) {}
}
