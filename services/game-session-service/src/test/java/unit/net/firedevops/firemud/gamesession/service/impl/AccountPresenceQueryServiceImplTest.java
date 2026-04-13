package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog;
import net.firedevops.firemud.gamesession.config.PresenceProperties;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.AccountPresenceVisibilityPolicy;
import net.firedevops.firemud.gamesession.service.AccountPresenceVisibilityPolicyResolver;
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
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    GameplayPresenceService presenceService = Mockito.mock(GameplayPresenceService.class);
    AccountRecentPresenceService recentPresenceService =
        Mockito.mock(AccountRecentPresenceService.class);
    AccountPresenceVisibilityPolicyResolver visibilityPolicyResolver =
        Mockito.mock(AccountPresenceVisibilityPolicyResolver.class);
    GameplayCatalogProperties catalogProperties = new GameplayCatalogProperties();
    GameplayWorldCatalog gameplayWorldCatalog = new GameplayWorldCatalog(catalogProperties);
    PresenceProperties properties = new PresenceProperties();
    GameplayPresenceActivityResolver resolver = new GameplayPresenceActivityResolver(properties);
    AccountPresenceQueryServiceImpl service =
        new AccountPresenceQueryServiceImpl(
            repository,
            presenceService,
            resolver,
            recentPresenceService,
            visibilityPolicyResolver,
            gameplayWorldCatalog);

    GameInstance running = new GameInstance();
    running.setId(11L);
    running.setTenantId(1L);
    running.setOwnerAccountId(3L);
    running.setStatus("RUNNING");
    when(repository.findByTenantIdAndOwnerAccountIdInAndStatus(
            org.mockito.Mockito.eq(1L),
            argThat(ids -> ids != null && ids.containsAll(List.of(3L, 4L)) && ids.size() == 2),
            org.mockito.Mockito.eq("RUNNING")))
        .thenReturn(List.of(running));
    when(recentPresenceService.findByAccountIds(
            org.mockito.Mockito.eq(1L),
            argThat(ids -> ids != null && ids.containsAll(List.of(3L, 4L)) && ids.size() == 2)))
        .thenReturn(
            java.util.Map.of(
                4L,
                new AccountRecentPresenceState(
                    1L,
                    4L,
                    Instant.parse("2026-04-11T06:15:30Z").toEpochMilli(),
                    AccountPresenceVisibilityPolicy.PRIVATE)));
    when(presenceService.findConnectedBySessionId(11L))
        .thenReturn(
            java.util.Optional.of(
                new GameplayPresence(
                    11L,
                    1L,
                    2L,
                    3L,
                    99L,
                    "Ben",
                    GameplayPresenceRole.PLAYER,
                    100L,
                    150L,
                    180L,
                    120L)));
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
    assertEquals("Ben", result.get(0).characterName());
    assertEquals(
        net.firedevops.firemud.gamesession.service.GameplayPresenceActivityState.EXPLICIT_AFK,
        result.get(0).activityState());
    assertEquals(AccountPresenceVisibilityPolicy.FRIENDS_ONLY, result.get(0).visibilityPolicy());
    assertEquals(4L, result.get(1).accountId());
    assertEquals(false, result.get(1).online());
    assertEquals(Instant.parse("2026-04-11T06:15:30Z"), result.get(1).lastSeenAt());
    assertEquals(AccountPresenceVisibilityPolicy.PRIVATE, result.get(1).visibilityPolicy());
  }
}
