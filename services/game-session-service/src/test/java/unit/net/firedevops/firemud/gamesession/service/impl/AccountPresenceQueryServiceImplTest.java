package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog;
import net.firedevops.firemud.gamesession.config.PresenceProperties;
import net.firedevops.firemud.gamesession.service.AccountPresenceVisibilityPolicy;
import net.firedevops.firemud.gamesession.service.AccountPresenceVisibilityPolicyResolver;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceDisposition;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceService;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceState;
import net.firedevops.firemud.gamesession.service.GameplayPresence;
import net.firedevops.firemud.gamesession.service.GameplayPresenceActivityResolver;
import net.firedevops.firemud.gamesession.service.GameplayPresenceRole;
import net.firedevops.firemud.gamesession.service.GameplayPresenceService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AccountPresenceQueryServiceImplTest {
  @Test
  void queryAccountPresenceReturnsOnlineAndOfflineSnapshotsInRequestOrder() {
    GameplayPresenceService presenceService = Mockito.mock(GameplayPresenceService.class);
    AccountRecentPresenceService recentPresenceService =
        Mockito.mock(AccountRecentPresenceService.class);
    AccountPresenceVisibilityPolicyResolver visibilityPolicyResolver =
        Mockito.mock(AccountPresenceVisibilityPolicyResolver.class);
    GameplayCatalogProperties catalogProperties = gameplayCatalogProperties();
    GameplayWorldCatalog gameplayWorldCatalog = new GameplayWorldCatalog(catalogProperties);
    PresenceProperties properties = new PresenceProperties();
    GameplayPresenceActivityResolver resolver = new GameplayPresenceActivityResolver(properties);
    AccountPresenceQueryServiceImpl service =
        new AccountPresenceQueryServiceImpl(
            presenceService,
            resolver,
            recentPresenceService,
            visibilityPolicyResolver,
            gameplayWorldCatalog);
    when(recentPresenceService.findByAccountIds(
            org.mockito.Mockito.eq(1L),
            org.mockito.ArgumentMatchers.argThat(
                ids -> ids != null && ids.containsAll(List.of(3L, 4L)) && ids.size() == 2)))
        .thenReturn(
            java.util.Map.of(
                4L,
                new AccountRecentPresenceState(
                    1L,
                    4L,
                    2L,
                    "SHARED",
                    "sandbox",
                    "production",
                    17L,
                    Instant.parse("2026-04-11T06:15:30Z").toEpochMilli(),
                    AccountRecentPresenceDisposition.TRANSPORT_LOSS,
                    AccountPresenceVisibilityPolicy.PRIVATE)));
    when(presenceService.listConnectedByAccountIds(
            org.mockito.Mockito.eq(1L),
            org.mockito.ArgumentMatchers.argThat(
                ids -> ids != null && ids.containsAll(List.of(3L, 4L)) && ids.size() == 2)))
        .thenReturn(
            Map.of(
                3L,
                List.of(
                    new GameplayPresence(
                        97L,
                        1L,
                        2L,
                        "SHARED",
                        "sandbox",
                        "production",
                        17L,
                        3L,
                        99L,
                        "Ben",
                        GameplayPresenceRole.PLAYER,
                        100L,
                        150L,
                        180L,
                        120L))));
    when(visibilityPolicyResolver.resolve(1L, 3L, GameplayPresenceRole.PLAYER))
        .thenReturn(AccountPresenceVisibilityPolicy.FRIENDS_ONLY);

    var result = service.queryAccountPresence(1L, 2L, List.of(3L, 4L));

    assertEquals(2, result.size());
    assertEquals(3L, result.get(0).accountId());
    assertEquals(true, result.get(0).online());
    assertEquals(2L, result.get(0).gameInstanceId());
    assertEquals("sandbox", result.get(0).worldSlug());
    assertEquals("Builder Sandbox", result.get(0).worldDisplayName());
    assertEquals("production", result.get(0).realmSlug());
    assertEquals("Live Realm", result.get(0).realmDisplayName());
    assertEquals(17L, result.get(0).pointerVersion());
    assertEquals("Ben", result.get(0).characterName());
    assertEquals(
        net.firedevops.firemud.gamesession.service.GameplayPresenceActivityState.EXPLICIT_AFK,
        result.get(0).activityState());
    assertEquals(null, result.get(0).recentDisposition());
    assertEquals(AccountPresenceVisibilityPolicy.FRIENDS_ONLY, result.get(0).visibilityPolicy());
    assertEquals(4L, result.get(1).accountId());
    assertEquals(false, result.get(1).online());
    assertEquals(2L, result.get(1).gameInstanceId());
    assertEquals("sandbox", result.get(1).worldSlug());
    assertEquals("Builder Sandbox", result.get(1).worldDisplayName());
    assertEquals("production", result.get(1).realmSlug());
    assertEquals("Live Realm", result.get(1).realmDisplayName());
    assertEquals(17L, result.get(1).pointerVersion());
    assertEquals(Instant.parse("2026-04-11T06:15:30Z"), result.get(1).lastSeenAt());
    assertEquals(
        AccountRecentPresenceDisposition.TRANSPORT_LOSS, result.get(1).recentDisposition());
    assertEquals(AccountPresenceVisibilityPolicy.PRIVATE, result.get(1).visibilityPolicy());
  }

  @Test
  void queryAccountPresenceIgnoresStaleLivePresenceAndKeepsOfflineSnapshot() {
    GameplayPresenceService presenceService = Mockito.mock(GameplayPresenceService.class);
    AccountRecentPresenceService recentPresenceService =
        Mockito.mock(AccountRecentPresenceService.class);
    AccountPresenceVisibilityPolicyResolver visibilityPolicyResolver =
        Mockito.mock(AccountPresenceVisibilityPolicyResolver.class);
    GameplayCatalogProperties catalogProperties = gameplayCatalogProperties();
    GameplayWorldCatalog gameplayWorldCatalog = new GameplayWorldCatalog(catalogProperties);
    PresenceProperties properties = new PresenceProperties();
    GameplayPresenceActivityResolver resolver = new GameplayPresenceActivityResolver(properties);
    AccountPresenceQueryServiceImpl service =
        new AccountPresenceQueryServiceImpl(
            presenceService,
            resolver,
            recentPresenceService,
            visibilityPolicyResolver,
            gameplayWorldCatalog);
    when(recentPresenceService.findByAccountIds(
            org.mockito.Mockito.eq(1L),
            org.mockito.ArgumentMatchers.argThat(
                ids -> ids != null && ids.contains(3L) && ids.size() == 1)))
        .thenReturn(
            Map.of(
                3L,
                new AccountRecentPresenceState(
                    1L,
                    3L,
                    9L,
                    "SHARED",
                    "sandbox",
                    "production",
                    18L,
                    Instant.parse("2026-04-11T07:15:30Z").toEpochMilli(),
                    AccountRecentPresenceDisposition.TRANSPORT_LOSS,
                    AccountPresenceVisibilityPolicy.FRIENDS_ONLY)));
    when(presenceService.listConnectedByAccountIds(
            org.mockito.Mockito.eq(1L),
            org.mockito.ArgumentMatchers.argThat(
                ids -> ids != null && ids.contains(3L) && ids.size() == 1)))
        .thenReturn(
            Map.of(
                3L,
                List.of(
                    new GameplayPresence(
                        97L,
                        1L,
                        9L,
                        "SHARED",
                        "sandbox",
                        "production",
                        18L,
                        3L,
                        99L,
                        "Ben",
                        GameplayPresenceRole.PLAYER,
                        100L,
                        null,
                        180L,
                        120L))));

    var result = service.queryAccountPresence(1L, 2L, List.of(3L));

    assertEquals(1, result.size());
    assertEquals(false, result.get(0).online());
    assertEquals(9L, result.get(0).gameInstanceId());
    assertEquals(18L, result.get(0).pointerVersion());
    assertEquals(AccountPresenceVisibilityPolicy.FRIENDS_ONLY, result.get(0).visibilityPolicy());
  }

  @Test
  void queryAccountPresencePrefersCurrentPresenceOverMoreRecentStaleSession() {
    GameplayPresenceService presenceService = Mockito.mock(GameplayPresenceService.class);
    AccountRecentPresenceService recentPresenceService =
        Mockito.mock(AccountRecentPresenceService.class);
    AccountPresenceVisibilityPolicyResolver visibilityPolicyResolver =
        Mockito.mock(AccountPresenceVisibilityPolicyResolver.class);
    GameplayCatalogProperties catalogProperties = gameplayCatalogProperties();
    GameplayWorldCatalog gameplayWorldCatalog = new GameplayWorldCatalog(catalogProperties);
    PresenceProperties properties = new PresenceProperties();
    GameplayPresenceActivityResolver resolver = new GameplayPresenceActivityResolver(properties);
    AccountPresenceQueryServiceImpl service =
        new AccountPresenceQueryServiceImpl(
            presenceService,
            resolver,
            recentPresenceService,
            visibilityPolicyResolver,
            gameplayWorldCatalog);
    when(recentPresenceService.findByAccountIds(
            org.mockito.Mockito.eq(1L),
            org.mockito.ArgumentMatchers.argThat(
                ids -> ids != null && ids.contains(3L) && ids.size() == 1)))
        .thenReturn(Map.of());
    when(presenceService.listConnectedByAccountIds(
            org.mockito.Mockito.eq(1L),
            org.mockito.ArgumentMatchers.argThat(
                ids -> ids != null && ids.contains(3L) && ids.size() == 1)))
        .thenReturn(
            Map.of(
                3L,
                List.of(
                    new GameplayPresence(
                        97L,
                        1L,
                        9L,
                        "SHARED",
                        "sandbox",
                        "production",
                        18L,
                        3L,
                        99L,
                        "Ben",
                        GameplayPresenceRole.PLAYER,
                        100L,
                        null,
                        120L,
                        150L),
                    new GameplayPresence(
                        98L,
                        1L,
                        2L,
                        "SHARED",
                        "sandbox",
                        "production",
                        17L,
                        3L,
                        100L,
                        "Ben",
                        GameplayPresenceRole.PLAYER,
                        90L,
                        null,
                        80L,
                        80L))));
    when(visibilityPolicyResolver.resolve(1L, 3L, GameplayPresenceRole.PLAYER))
        .thenReturn(AccountPresenceVisibilityPolicy.PUBLIC);

    var result = service.queryAccountPresence(1L, 2L, List.of(3L));

    assertEquals(1, result.size());
    assertEquals(true, result.get(0).online());
    assertEquals(2L, result.get(0).gameInstanceId());
    assertEquals(17L, result.get(0).pointerVersion());
    assertEquals(100L, result.get(0).characterId());
  }

  private static GameplayCatalogProperties gameplayCatalogProperties() {
    GameplayCatalogProperties.Realm production = new GameplayCatalogProperties.Realm();
    production.setSlug("production");
    production.setDisplayName("Live Realm");
    production.setTenantId(1L);
    production.setGameInstanceId(2L);
    production.setPointerVersion(17L);
    production.setVisible(true);
    production.setPublicProductionRealm(true);
    production.setRequiresCharacterSelection(false);
    production.setStateScope(GameplayCatalogProperties.RealmStateScope.SHARED);
    production.setCharacterCreationPolicy(
        GameplayCatalogProperties.CharacterCreationPolicy.ALLOW_NEW);

    GameplayCatalogProperties.World sandbox = new GameplayCatalogProperties.World();
    sandbox.setSlug("sandbox");
    sandbox.setDisplayName("Builder Sandbox");
    sandbox.setRealms(List.of(production));

    GameplayCatalogProperties properties = new GameplayCatalogProperties();
    properties.setWorlds(List.of(sandbox));
    return properties;
  }
}
