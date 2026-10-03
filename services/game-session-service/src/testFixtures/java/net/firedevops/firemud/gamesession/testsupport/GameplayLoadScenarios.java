package net.firedevops.firemud.gamesession.testsupport;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
      long firstCharacterId,
      int count,
      long gameTemplateId) {
    long[] characterIds = new long[count];
    for (int i = 0; i < count; i++) {
      characterIds[i] = firstCharacterId + i + 1;
    }
    stack.freshGameplayBaseline(
        tenantId, gameplayInstanceId, firstCharacterId, gameTemplateId, characterIds);
    // Cross-service test bootstraps reserve runtime target 2 for the built-in sandbox pointer.
    // Skip that id so demo-player bootstrap instances do not inherit unrelated sandbox authority.
    stack.insertRunningGameInstance(tenantId, firstCharacterId, gameTemplateId, false);

    List<PlayerSeed> players = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      long characterId = characterIds[i];
      String accountUuid = UUID.randomUUID().toString();
      long bootstrapGameInstanceId =
          stack.insertRunningGameInstance(tenantId, characterId, gameTemplateId, false);
      // Admission pointers are uniquely identified by world/realm and runtime target. Give each
      // isolated player target its own world and retain the persisted pointer version for the
      // matching WebSocket bootstrap headers.
      String bootstrapWorldSlug =
          GameplayCrossServiceStack.SYNTHETIC_LOAD_WORLD_PREFIX + characterId;
      GameplayAdmissionPointerSnapshot pointer =
          stack
              .gameSessionBean(GameplayAdmissionPointerAuthorityService.class)
              .upsertPointer(
                  new GameplayAdmissionPointerMutation(
                      bootstrapWorldSlug,
                      "Demo Player " + characterId,
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
                      "load-test:" + characterId,
                      null,
                      null));
      long sessionId = firstCharacterId + 10_000L + i + 1;
      String email = "player" + (i + 1) + "@example.com";
      stack.accountStub().mapAccountUuid(email, accountUuid);
      stack
          .entityStub()
          .registerCharacterForAccountUuid(
              accountUuid,
              net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                  .setId(Long.toString(characterId))
                  .setTenantId(Long.toString(tenantId))
                  .setAccountId(accountUuid)
                  .setName("player-" + (i + 1))
                  .build());
      players.add(
          new PlayerSeed(
              sessionId,
              bootstrapGameInstanceId,
              characterId,
              accountUuid,
              email,
              "player-" + (i + 1),
              bootstrapWorldSlug,
              pointer.pointerVersion()));
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
                "X-World-Slug", player.bootstrapWorldSlug(),
                "X-Realm-Slug", DEFAULT_REALM_SLUG,
                "X-Pointer-Version", Long.toString(player.bootstrapPointerVersion())));
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
      long characterId,
      String accountUuid,
      String username,
      String label,
      String bootstrapWorldSlug,
      long bootstrapPointerVersion) {}
}
