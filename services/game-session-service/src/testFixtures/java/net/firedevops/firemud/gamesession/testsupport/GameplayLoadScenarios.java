package net.firedevops.firemud.gamesession.testsupport;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.entitymanagement.v1.Character;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerMutation;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;

/** Shared load-oriented gameplay scenario helpers above the base cross-service stack. */
public final class GameplayLoadScenarios {
  private static final String DEFAULT_REALM_SLUG = "production";

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
    List<Character> characters = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      long accountId = characterIds[i];
      String label = "player-" + (i + 1);
      long bootstrapGameInstanceId =
          stack.insertRunningGameInstance(tenantId, accountId, gameTemplateId, false);
      // Admission pointers are uniquely identified by world/realm and runtime target. Give each
      // isolated player target its own world and retain the persisted pointer version for the
      // matching WebSocket bootstrap headers.
      String bootstrapWorldSlug = GameplayCrossServiceStack.SYNTHETIC_LOAD_WORLD_PREFIX + accountId;
      GameplayAdmissionPointerSnapshot pointer =
          stack
              .gameSessionBean(GameplayAdmissionPointerAuthorityService.class)
              .upsertPointer(
                  new GameplayAdmissionPointerMutation(
                      bootstrapWorldSlug,
                      "Demo Player " + accountId,
                      DEFAULT_REALM_SLUG,
                      "Live Realm",
                      tenantId,
                      bootstrapGameInstanceId,
                      false,
                      false,
                      false,
                      "SHARED",
                      "ALLOW_NEW",
                      GameplayCrossServiceStack.SYNTHETIC_LOAD_ACTOR,
                      "Seed per-player load-test admission pointer",
                      "load-test:" + accountId,
                      0L,
                      0L,
                      null));
      long sessionId = firstAccountId + 10_000L + i + 1;
      String email = "player" + (i + 1) + "@example.com";
      stack.accountStub().mapAccountId(email, accountId);
      characters.add(
          Character.newBuilder()
              .setId(Long.toString(accountId))
              .setTenantId(Long.toString(tenantId))
              .setAccountId(Long.toString(accountId))
              .setName(label)
              .build());
      players.add(
          new PlayerSeed(
              sessionId,
              bootstrapGameInstanceId,
              accountId,
              email,
              label,
              bootstrapWorldSlug,
              pointer.pointerVersion()));
    }
    stack.entityStub().setCharacters(characters);
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
                "X-World-Slug", player.bootstrapWorldSlug(),
                "X-Realm-Slug", DEFAULT_REALM_SLUG,
                "X-Pointer-Version", Long.toString(player.bootstrapPointerVersion())));
    try {
      driver.enterGameplayAndWaitReady(
          player.username(), "swordfish", world, player.label(), readyText);
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
      String label,
      String bootstrapWorldSlug,
      long bootstrapPointerVersion) {}
}
