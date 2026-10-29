package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.gamesession.config.PresenceProperties;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceDisposition;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceService;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceState;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.GameplayPresence;
import net.firedevops.firemud.gamesession.service.GameplayPresenceActivityResolver;
import net.firedevops.firemud.gamesession.service.GameplayPresenceRole;
import net.firedevops.firemud.gamesession.service.GameplayPresenceService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AccountPresenceQueryServiceImplTest {
  private static final String VIEWER_ACCOUNT_ID = "00000000-0000-4000-8000-000000000002";
  private static final String ONLINE_ACCOUNT_ID = "00000000-0000-4000-8000-000000000003";
  private static final String OFFLINE_ACCOUNT_ID = "00000000-0000-4000-8000-000000000004";

  @Test
  void queryAccountPresenceReturnsOnlineAndOfflineSnapshotsInRequestOrder() {
    GameplayPresenceService presenceService = Mockito.mock(GameplayPresenceService.class);
    AccountRecentPresenceService recentPresenceService =
        Mockito.mock(AccountRecentPresenceService.class);
    GameplayAdmissionPointerAuthorityService pointerAuthorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    PresenceProperties properties = new PresenceProperties();
    GameplayPresenceActivityResolver resolver = new GameplayPresenceActivityResolver(properties);
    AccountPresenceQueryServiceImpl service =
        new AccountPresenceQueryServiceImpl(
            presenceService, resolver, recentPresenceService, pointerAuthorityService);
    when(recentPresenceService.findByAccountIds(
            org.mockito.Mockito.eq(1L),
            org.mockito.ArgumentMatchers.argThat(
                ids ->
                    ids != null
                        && ids.containsAll(List.of(ONLINE_ACCOUNT_ID, OFFLINE_ACCOUNT_ID))
                        && ids.size() == 2)))
        .thenReturn(
            java.util.Map.of(
                OFFLINE_ACCOUNT_ID,
                new AccountRecentPresenceState(
                    1L,
                    OFFLINE_ACCOUNT_ID,
                    2L,
                    "SHARED",
                    "sandbox",
                    "production",
                    17L,
                    Instant.parse("2026-04-11T06:15:30Z").toEpochMilli(),
                    AccountRecentPresenceDisposition.TRANSPORT_LOSS)));
    when(presenceService.listConnectedByAccountIds(
            org.mockito.Mockito.eq(1L),
            org.mockito.ArgumentMatchers.argThat(
                ids ->
                    ids != null
                        && ids.containsAll(List.of(ONLINE_ACCOUNT_ID, OFFLINE_ACCOUNT_ID))
                        && ids.size() == 2)))
        .thenReturn(
            Map.of(
                ONLINE_ACCOUNT_ID,
                List.of(
                    new GameplayPresence(
                        97L,
                        1L,
                        2L,
                        "SHARED",
                        "sandbox",
                        "production",
                        17L,
                        ONLINE_ACCOUNT_ID,
                        99L,
                        "Ben",
                        GameplayPresenceRole.PLAYER,
                        100L,
                        150L,
                        180L,
                        120L))));
    when(pointerAuthorityService.listByRuntimeTarget(1L, 2L))
        .thenReturn(
            List.of(
                pointer("sandbox", "Builder Sandbox", "production", "Live Realm", 1L, 2L, 17L)));

    var result =
        service.queryAccountPresence(
            1L, VIEWER_ACCOUNT_ID, List.of(ONLINE_ACCOUNT_ID, OFFLINE_ACCOUNT_ID));

    assertEquals(2, result.size());
    assertEquals(ONLINE_ACCOUNT_ID, result.get(0).accountId());
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
    assertEquals(OFFLINE_ACCOUNT_ID, result.get(1).accountId());
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
  }

  @Test
  void queryAccountPresenceIgnoresStaleLivePresenceAndKeepsOfflineSnapshot() {
    GameplayPresenceService presenceService = Mockito.mock(GameplayPresenceService.class);
    AccountRecentPresenceService recentPresenceService =
        Mockito.mock(AccountRecentPresenceService.class);
    GameplayAdmissionPointerAuthorityService pointerAuthorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    PresenceProperties properties = new PresenceProperties();
    GameplayPresenceActivityResolver resolver = new GameplayPresenceActivityResolver(properties);
    AccountPresenceQueryServiceImpl service =
        new AccountPresenceQueryServiceImpl(
            presenceService, resolver, recentPresenceService, pointerAuthorityService);
    when(recentPresenceService.findByAccountIds(
            org.mockito.Mockito.eq(1L),
            org.mockito.ArgumentMatchers.argThat(
                ids -> ids != null && ids.contains(ONLINE_ACCOUNT_ID) && ids.size() == 1)))
        .thenReturn(
            Map.of(
                ONLINE_ACCOUNT_ID,
                new AccountRecentPresenceState(
                    1L,
                    ONLINE_ACCOUNT_ID,
                    9L,
                    "SHARED",
                    "sandbox",
                    "production",
                    18L,
                    Instant.parse("2026-04-11T07:15:30Z").toEpochMilli(),
                    AccountRecentPresenceDisposition.TRANSPORT_LOSS)));
    when(presenceService.listConnectedByAccountIds(
            org.mockito.Mockito.eq(1L),
            org.mockito.ArgumentMatchers.argThat(
                ids -> ids != null && ids.contains(ONLINE_ACCOUNT_ID) && ids.size() == 1)))
        .thenReturn(
            Map.of(
                ONLINE_ACCOUNT_ID,
                List.of(
                    new GameplayPresence(
                        97L,
                        1L,
                        9L,
                        "SHARED",
                        "sandbox",
                        "production",
                        18L,
                        ONLINE_ACCOUNT_ID,
                        99L,
                        "Ben",
                        GameplayPresenceRole.PLAYER,
                        100L,
                        null,
                        180L,
                        120L))));
    when(pointerAuthorityService.listByRuntimeTarget(1L, 9L))
        .thenReturn(
            List.of(
                pointer("sandbox", "Builder Sandbox", "production", "Live Realm", 1L, 2L, 17L)));

    var result = service.queryAccountPresence(1L, VIEWER_ACCOUNT_ID, List.of(ONLINE_ACCOUNT_ID));

    assertEquals(1, result.size());
    assertEquals(false, result.get(0).online());
    assertEquals(9L, result.get(0).gameInstanceId());
    assertEquals(18L, result.get(0).pointerVersion());
  }

  @Test
  void queryAccountPresenceIgnoresLivePresenceWithZeroPointerVersionAndKeepsOfflineSnapshot() {
    GameplayPresenceService presenceService = Mockito.mock(GameplayPresenceService.class);
    AccountRecentPresenceService recentPresenceService =
        Mockito.mock(AccountRecentPresenceService.class);
    GameplayAdmissionPointerAuthorityService pointerAuthorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    PresenceProperties properties = new PresenceProperties();
    GameplayPresenceActivityResolver resolver = new GameplayPresenceActivityResolver(properties);
    AccountPresenceQueryServiceImpl service =
        new AccountPresenceQueryServiceImpl(
            presenceService, resolver, recentPresenceService, pointerAuthorityService);
    when(recentPresenceService.findByAccountIds(
            org.mockito.Mockito.eq(1L),
            org.mockito.ArgumentMatchers.argThat(
                ids -> ids != null && ids.contains(ONLINE_ACCOUNT_ID) && ids.size() == 1)))
        .thenReturn(
            Map.of(
                ONLINE_ACCOUNT_ID,
                new AccountRecentPresenceState(
                    1L,
                    ONLINE_ACCOUNT_ID,
                    2L,
                    "SHARED",
                    "sandbox",
                    "production",
                    17L,
                    Instant.parse("2026-04-11T07:15:30Z").toEpochMilli(),
                    AccountRecentPresenceDisposition.TRANSPORT_LOSS)));
    when(presenceService.listConnectedByAccountIds(
            org.mockito.Mockito.eq(1L),
            org.mockito.ArgumentMatchers.argThat(
                ids -> ids != null && ids.contains(ONLINE_ACCOUNT_ID) && ids.size() == 1)))
        .thenReturn(
            Map.of(
                ONLINE_ACCOUNT_ID,
                List.of(
                    new GameplayPresence(
                        97L,
                        1L,
                        2L,
                        "SHARED",
                        "sandbox",
                        "production",
                        0L,
                        ONLINE_ACCOUNT_ID,
                        99L,
                        "Ben",
                        GameplayPresenceRole.PLAYER,
                        100L,
                        150L,
                        180L,
                        120L))));
    when(pointerAuthorityService.listByRuntimeTarget(1L, 2L))
        .thenReturn(
            List.of(
                pointer("sandbox", "Builder Sandbox", "production", "Live Realm", 1L, 2L, 17L)));

    var result = service.queryAccountPresence(1L, VIEWER_ACCOUNT_ID, List.of(ONLINE_ACCOUNT_ID));

    assertEquals(1, result.size());
    assertEquals(false, result.get(0).online());
    assertEquals("sandbox", result.get(0).worldSlug());
    assertEquals("Builder Sandbox", result.get(0).worldDisplayName());
    assertEquals("production", result.get(0).realmSlug());
    assertEquals("Live Realm", result.get(0).realmDisplayName());
    assertEquals(17L, result.get(0).pointerVersion());
  }

  @Test
  void queryAccountPresenceIgnoresLivePresenceWithBlankWorldSlugAndKeepsOfflineSnapshot() {
    GameplayPresenceService presenceService = Mockito.mock(GameplayPresenceService.class);
    AccountRecentPresenceService recentPresenceService =
        Mockito.mock(AccountRecentPresenceService.class);
    GameplayAdmissionPointerAuthorityService pointerAuthorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    PresenceProperties properties = new PresenceProperties();
    GameplayPresenceActivityResolver resolver = new GameplayPresenceActivityResolver(properties);
    AccountPresenceQueryServiceImpl service =
        new AccountPresenceQueryServiceImpl(
            presenceService, resolver, recentPresenceService, pointerAuthorityService);
    when(recentPresenceService.findByAccountIds(
            org.mockito.Mockito.eq(1L),
            org.mockito.ArgumentMatchers.argThat(
                ids -> ids != null && ids.contains(ONLINE_ACCOUNT_ID) && ids.size() == 1)))
        .thenReturn(
            Map.of(
                ONLINE_ACCOUNT_ID,
                new AccountRecentPresenceState(
                    1L,
                    ONLINE_ACCOUNT_ID,
                    2L,
                    "SHARED",
                    "sandbox",
                    "production",
                    17L,
                    Instant.parse("2026-04-11T07:15:30Z").toEpochMilli(),
                    AccountRecentPresenceDisposition.TRANSPORT_LOSS)));
    when(presenceService.listConnectedByAccountIds(
            org.mockito.Mockito.eq(1L),
            org.mockito.ArgumentMatchers.argThat(
                ids -> ids != null && ids.contains(ONLINE_ACCOUNT_ID) && ids.size() == 1)))
        .thenReturn(
            Map.of(
                ONLINE_ACCOUNT_ID,
                List.of(
                    new GameplayPresence(
                        97L,
                        1L,
                        2L,
                        "SHARED",
                        " ",
                        "production",
                        17L,
                        ONLINE_ACCOUNT_ID,
                        99L,
                        "Ben",
                        GameplayPresenceRole.PLAYER,
                        100L,
                        150L,
                        180L,
                        120L))));
    when(pointerAuthorityService.listByRuntimeTarget(1L, 2L))
        .thenReturn(
            List.of(
                pointer("sandbox", "Builder Sandbox", "production", "Live Realm", 1L, 2L, 17L)));

    var result = service.queryAccountPresence(1L, VIEWER_ACCOUNT_ID, List.of(ONLINE_ACCOUNT_ID));

    assertEquals(1, result.size());
    assertEquals(false, result.get(0).online());
    assertEquals("sandbox", result.get(0).worldSlug());
    assertEquals("Builder Sandbox", result.get(0).worldDisplayName());
    assertEquals("production", result.get(0).realmSlug());
    assertEquals("Live Realm", result.get(0).realmDisplayName());
    assertEquals(17L, result.get(0).pointerVersion());
  }

  @Test
  void queryAccountPresencePrefersCurrentPresenceOverMoreRecentStaleSession() {
    GameplayPresenceService presenceService = Mockito.mock(GameplayPresenceService.class);
    AccountRecentPresenceService recentPresenceService =
        Mockito.mock(AccountRecentPresenceService.class);
    GameplayAdmissionPointerAuthorityService pointerAuthorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    PresenceProperties properties = new PresenceProperties();
    GameplayPresenceActivityResolver resolver = new GameplayPresenceActivityResolver(properties);
    AccountPresenceQueryServiceImpl service =
        new AccountPresenceQueryServiceImpl(
            presenceService, resolver, recentPresenceService, pointerAuthorityService);
    when(recentPresenceService.findByAccountIds(
            org.mockito.Mockito.eq(1L),
            org.mockito.ArgumentMatchers.argThat(
                ids -> ids != null && ids.contains(ONLINE_ACCOUNT_ID) && ids.size() == 1)))
        .thenReturn(Map.of());
    when(presenceService.listConnectedByAccountIds(
            org.mockito.Mockito.eq(1L),
            org.mockito.ArgumentMatchers.argThat(
                ids -> ids != null && ids.contains(ONLINE_ACCOUNT_ID) && ids.size() == 1)))
        .thenReturn(
            Map.of(
                ONLINE_ACCOUNT_ID,
                List.of(
                    new GameplayPresence(
                        97L,
                        1L,
                        9L,
                        "SHARED",
                        "sandbox",
                        "production",
                        18L,
                        ONLINE_ACCOUNT_ID,
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
                        ONLINE_ACCOUNT_ID,
                        100L,
                        "Ben",
                        GameplayPresenceRole.PLAYER,
                        90L,
                        null,
                        80L,
                        80L))));
    when(pointerAuthorityService.listByRuntimeTarget(1L, 2L))
        .thenReturn(
            List.of(
                pointer("sandbox", "Builder Sandbox", "production", "Live Realm", 1L, 2L, 17L)));

    var result = service.queryAccountPresence(1L, VIEWER_ACCOUNT_ID, List.of(ONLINE_ACCOUNT_ID));

    assertEquals(1, result.size());
    assertEquals(true, result.get(0).online());
    assertEquals(2L, result.get(0).gameInstanceId());
    assertEquals(17L, result.get(0).pointerVersion());
    assertEquals(100L, result.get(0).characterId());
  }

  @Test
  void queryAccountPresenceFailsClosedWhenRuntimeAuthorityIsAmbiguous() {
    GameplayPresenceService presenceService = Mockito.mock(GameplayPresenceService.class);
    AccountRecentPresenceService recentPresenceService =
        Mockito.mock(AccountRecentPresenceService.class);
    GameplayAdmissionPointerAuthorityService pointerAuthorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    PresenceProperties properties = new PresenceProperties();
    GameplayPresenceActivityResolver resolver = new GameplayPresenceActivityResolver(properties);
    AccountPresenceQueryServiceImpl service =
        new AccountPresenceQueryServiceImpl(
            presenceService, resolver, recentPresenceService, pointerAuthorityService);
    when(recentPresenceService.findByAccountIds(
            org.mockito.Mockito.eq(1L),
            org.mockito.ArgumentMatchers.argThat(
                ids -> ids != null && ids.contains(ONLINE_ACCOUNT_ID) && ids.size() == 1)))
        .thenReturn(
            Map.of(
                ONLINE_ACCOUNT_ID,
                new AccountRecentPresenceState(
                    1L,
                    ONLINE_ACCOUNT_ID,
                    2L,
                    "SHARED",
                    "sandbox",
                    "production",
                    17L,
                    Instant.parse("2026-04-11T07:15:30Z").toEpochMilli(),
                    AccountRecentPresenceDisposition.TRANSPORT_LOSS)));
    when(presenceService.listConnectedByAccountIds(
            org.mockito.Mockito.eq(1L),
            org.mockito.ArgumentMatchers.argThat(
                ids -> ids != null && ids.contains(ONLINE_ACCOUNT_ID) && ids.size() == 1)))
        .thenReturn(
            Map.of(
                ONLINE_ACCOUNT_ID,
                List.of(
                    new GameplayPresence(
                        97L,
                        1L,
                        2L,
                        "SHARED",
                        "sandbox",
                        "production",
                        17L,
                        ONLINE_ACCOUNT_ID,
                        99L,
                        "Ben",
                        GameplayPresenceRole.PLAYER,
                        100L,
                        150L,
                        180L,
                        120L))));
    when(pointerAuthorityService.listByRuntimeTarget(1L, 2L))
        .thenReturn(
            List.of(
                pointer("sandbox", "Builder Sandbox", "production", "Live Realm", 1L, 2L, 17L),
                pointer("sandbox", "Builder Sandbox", "preview", "Preview Realm", 1L, 2L, 18L)));

    var result = service.queryAccountPresence(1L, VIEWER_ACCOUNT_ID, List.of(ONLINE_ACCOUNT_ID));

    assertEquals(1, result.size());
    assertEquals(false, result.get(0).online());
    assertEquals("sandbox", result.get(0).worldSlug());
    assertEquals(null, result.get(0).worldDisplayName());
    assertEquals("production", result.get(0).realmSlug());
    assertEquals(null, result.get(0).realmDisplayName());
    assertEquals(17L, result.get(0).pointerVersion());
  }

  @Test
  void queryAccountPresenceFailsClosedWhenSingularRuntimeAuthorityIsIncomplete() {
    GameplayPresenceService presenceService = Mockito.mock(GameplayPresenceService.class);
    AccountRecentPresenceService recentPresenceService =
        Mockito.mock(AccountRecentPresenceService.class);
    GameplayAdmissionPointerAuthorityService pointerAuthorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    PresenceProperties properties = new PresenceProperties();
    GameplayPresenceActivityResolver resolver = new GameplayPresenceActivityResolver(properties);
    AccountPresenceQueryServiceImpl service =
        new AccountPresenceQueryServiceImpl(
            presenceService, resolver, recentPresenceService, pointerAuthorityService);
    when(recentPresenceService.findByAccountIds(
            org.mockito.Mockito.eq(1L),
            org.mockito.ArgumentMatchers.argThat(
                ids -> ids != null && ids.contains(ONLINE_ACCOUNT_ID) && ids.size() == 1)))
        .thenReturn(
            Map.of(
                ONLINE_ACCOUNT_ID,
                new AccountRecentPresenceState(
                    1L,
                    ONLINE_ACCOUNT_ID,
                    2L,
                    "SHARED",
                    "sandbox",
                    "production",
                    17L,
                    Instant.parse("2026-04-11T07:15:30Z").toEpochMilli(),
                    AccountRecentPresenceDisposition.TRANSPORT_LOSS)));
    when(presenceService.listConnectedByAccountIds(
            org.mockito.Mockito.eq(1L),
            org.mockito.ArgumentMatchers.argThat(
                ids -> ids != null && ids.contains(ONLINE_ACCOUNT_ID) && ids.size() == 1)))
        .thenReturn(
            Map.of(
                ONLINE_ACCOUNT_ID,
                List.of(
                    new GameplayPresence(
                        97L,
                        1L,
                        2L,
                        "SHARED",
                        "sandbox",
                        "production",
                        17L,
                        ONLINE_ACCOUNT_ID,
                        99L,
                        "Ben",
                        GameplayPresenceRole.PLAYER,
                        100L,
                        150L,
                        180L,
                        120L))));
    when(pointerAuthorityService.listByRuntimeTarget(1L, 2L))
        .thenReturn(
            List.of(
                new GameplayAdmissionPointerSnapshot(
                    "sandbox",
                    "Builder Sandbox",
                    "production",
                    "Live Realm",
                    1L,
                    2L,
                    17L,
                    true,
                    true,
                    false,
                    "",
                    "ALLOW_NEW")));

    var result = service.queryAccountPresence(1L, VIEWER_ACCOUNT_ID, List.of(ONLINE_ACCOUNT_ID));

    assertEquals(1, result.size());
    assertEquals(false, result.get(0).online());
    assertEquals("sandbox", result.get(0).worldSlug());
    assertEquals(null, result.get(0).worldDisplayName());
    assertEquals("production", result.get(0).realmSlug());
    assertEquals(null, result.get(0).realmDisplayName());
    assertEquals(17L, result.get(0).pointerVersion());
  }

  @Test
  void queryAccountPresenceTreatsCaseInsensitiveLiveRoutingIdentityAsCurrent() {
    GameplayPresenceService presenceService = Mockito.mock(GameplayPresenceService.class);
    AccountRecentPresenceService recentPresenceService =
        Mockito.mock(AccountRecentPresenceService.class);
    GameplayAdmissionPointerAuthorityService pointerAuthorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    PresenceProperties properties = new PresenceProperties();
    GameplayPresenceActivityResolver resolver = new GameplayPresenceActivityResolver(properties);
    AccountPresenceQueryServiceImpl service =
        new AccountPresenceQueryServiceImpl(
            presenceService, resolver, recentPresenceService, pointerAuthorityService);
    when(recentPresenceService.findByAccountIds(
            org.mockito.Mockito.eq(1L),
            org.mockito.ArgumentMatchers.argThat(
                ids -> ids != null && ids.contains(ONLINE_ACCOUNT_ID) && ids.size() == 1)))
        .thenReturn(Map.of());
    when(presenceService.listConnectedByAccountIds(
            org.mockito.Mockito.eq(1L),
            org.mockito.ArgumentMatchers.argThat(
                ids -> ids != null && ids.contains(ONLINE_ACCOUNT_ID) && ids.size() == 1)))
        .thenReturn(
            Map.of(
                ONLINE_ACCOUNT_ID,
                List.of(
                    new GameplayPresence(
                        97L,
                        1L,
                        2L,
                        "SHARED",
                        "Sandbox",
                        "Production",
                        17L,
                        ONLINE_ACCOUNT_ID,
                        99L,
                        "Ben",
                        GameplayPresenceRole.PLAYER,
                        100L,
                        150L,
                        180L,
                        120L))));
    when(pointerAuthorityService.listByRuntimeTarget(1L, 2L))
        .thenReturn(
            List.of(
                pointer("sandbox", "Builder Sandbox", "production", "Live Realm", 1L, 2L, 17L)));

    var result = service.queryAccountPresence(1L, VIEWER_ACCOUNT_ID, List.of(ONLINE_ACCOUNT_ID));

    assertEquals(1, result.size());
    assertEquals(true, result.get(0).online());
    assertEquals("sandbox", result.get(0).worldSlug());
    assertEquals("production", result.get(0).realmSlug());
    assertEquals("Builder Sandbox", result.get(0).worldDisplayName());
    assertEquals("Live Realm", result.get(0).realmDisplayName());
  }

  @Test
  void queryAccountPresenceTreatsCaseInsensitiveRecentRoutingIdentityAsCurrent() {
    GameplayPresenceService presenceService = Mockito.mock(GameplayPresenceService.class);
    AccountRecentPresenceService recentPresenceService =
        Mockito.mock(AccountRecentPresenceService.class);
    GameplayAdmissionPointerAuthorityService pointerAuthorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    PresenceProperties properties = new PresenceProperties();
    GameplayPresenceActivityResolver resolver = new GameplayPresenceActivityResolver(properties);
    AccountPresenceQueryServiceImpl service =
        new AccountPresenceQueryServiceImpl(
            presenceService, resolver, recentPresenceService, pointerAuthorityService);
    when(recentPresenceService.findByAccountIds(
            org.mockito.Mockito.eq(1L),
            org.mockito.ArgumentMatchers.argThat(
                ids -> ids != null && ids.contains(OFFLINE_ACCOUNT_ID) && ids.size() == 1)))
        .thenReturn(
            Map.of(
                OFFLINE_ACCOUNT_ID,
                new AccountRecentPresenceState(
                    1L,
                    OFFLINE_ACCOUNT_ID,
                    2L,
                    "SHARED",
                    "Sandbox",
                    "Production",
                    17L,
                    Instant.parse("2026-04-11T06:15:30Z").toEpochMilli(),
                    AccountRecentPresenceDisposition.TRANSPORT_LOSS)));
    when(presenceService.listConnectedByAccountIds(
            org.mockito.Mockito.eq(1L),
            org.mockito.ArgumentMatchers.argThat(
                ids -> ids != null && ids.contains(OFFLINE_ACCOUNT_ID) && ids.size() == 1)))
        .thenReturn(Map.of());
    when(pointerAuthorityService.listByRuntimeTarget(1L, 2L))
        .thenReturn(
            List.of(
                pointer("sandbox", "Builder Sandbox", "production", "Live Realm", 1L, 2L, 17L)));

    var result = service.queryAccountPresence(1L, VIEWER_ACCOUNT_ID, List.of(OFFLINE_ACCOUNT_ID));

    assertEquals(1, result.size());
    assertEquals(false, result.get(0).online());
    assertEquals("Sandbox", result.get(0).worldSlug());
    assertEquals("Production", result.get(0).realmSlug());
    assertEquals("Builder Sandbox", result.get(0).worldDisplayName());
    assertEquals("Live Realm", result.get(0).realmDisplayName());
  }

  @Test
  void queryAccountPresenceRejectsNonCanonicalAccountIdsBeforeReadingPresence() {
    GameplayPresenceService presenceService = Mockito.mock(GameplayPresenceService.class);
    AccountRecentPresenceService recentPresenceService =
        Mockito.mock(AccountRecentPresenceService.class);
    GameplayAdmissionPointerAuthorityService pointerAuthorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    AccountPresenceQueryServiceImpl service =
        new AccountPresenceQueryServiceImpl(
            presenceService,
            new GameplayPresenceActivityResolver(new PresenceProperties()),
            recentPresenceService,
            pointerAuthorityService);

    for (String invalidAccountId :
        List.of(
            "3",
            "00000000-0000-0000-0000-000000000000",
            "00000000-0000-4000-8000-00000000000A",
            " " + ONLINE_ACCOUNT_ID,
            ONLINE_ACCOUNT_ID + " ")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> service.queryAccountPresence(1L, invalidAccountId, List.of(ONLINE_ACCOUNT_ID)));
      assertThrows(
          IllegalArgumentException.class,
          () -> service.queryAccountPresence(1L, VIEWER_ACCOUNT_ID, List.of(invalidAccountId)));
    }

    verifyNoInteractions(presenceService, recentPresenceService, pointerAuthorityService);
  }

  private static GameplayAdmissionPointerSnapshot pointer(
      String worldSlug,
      String worldDisplayName,
      String realmSlug,
      String realmDisplayName,
      long tenantId,
      long gameInstanceId,
      long pointerVersion) {
    return new GameplayAdmissionPointerSnapshot(
        worldSlug,
        worldDisplayName,
        realmSlug,
        realmDisplayName,
        tenantId,
        gameInstanceId,
        pointerVersion,
        true,
        true,
        false,
        "SHARED",
        "ALLOW_NEW");
  }
}
