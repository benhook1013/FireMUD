package net.firedevops.firemud.socialgroups.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.gamesession.v1.AccountPresenceActivityState;
import net.firedevops.firemud.gamesession.v1.AccountPresenceEntry;
import net.firedevops.firemud.gamesession.v1.AccountPresenceVisibilityPolicy;
import net.firedevops.firemud.gamesession.v1.QueryAccountPresenceResponse;
import net.firedevops.firemud.socialgroups.client.GameSessionClient;
import net.firedevops.firemud.socialgroups.dto.AddFriendRequest;
import net.firedevops.firemud.socialgroups.dto.FriendLinkDto;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceActivityState;
import net.firedevops.firemud.socialgroups.dto.FriendRecentPresenceDisposition;
import net.firedevops.firemud.socialgroups.entity.AccountFriendLink;
import net.firedevops.firemud.socialgroups.entity.FriendLink;
import net.firedevops.firemud.socialgroups.mapper.FriendLinkMapper;
import net.firedevops.firemud.socialgroups.repository.AccountFriendLinkRepository;
import net.firedevops.firemud.socialgroups.repository.FriendLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;

class FriendServiceImplTest {
  private FriendLinkRepository repository;
  private AccountFriendLinkRepository accountRepository;
  private GameSessionClient gameSessionClient;
  private FriendServiceImpl service;

  @BeforeEach
  void setUp() {
    repository = Mockito.mock(FriendLinkRepository.class);
    accountRepository = Mockito.mock(AccountFriendLinkRepository.class);
    gameSessionClient = Mockito.mock(GameSessionClient.class);
    FriendLinkMapper mapper = Mappers.getMapper(FriendLinkMapper.class);
    service = new FriendServiceImpl(repository, accountRepository, mapper, gameSessionClient);
  }

  @Test
  void addFriendReturnsDto() {
    AddFriendRequest request = new AddFriendRequest(1L, 2L, 3L, false);
    FriendLink saved = new FriendLink();
    saved.setId(1L);
    saved.setTenantId(1L);
    saved.setAccountId(2L);
    saved.setFriendAccountId(3L);
    saved.setStatus("active");
    saved.setCreatedAt(Instant.now());
    when(repository.save(any(FriendLink.class))).thenReturn(saved);

    FriendLinkDto result = service.addFriend(request);
    assertEquals(2L, result.accountId());
    assertEquals(3L, result.friendAccountId());
  }

  @Test
  void addFriendStoresAccountLevelLinksWithTenantScope() {
    AddFriendRequest request = new AddFriendRequest(11L, 2L, 3L, true);
    when(accountRepository.save(any(AccountFriendLink.class)))
        .thenAnswer(
            invocation -> {
              AccountFriendLink link = invocation.getArgument(0);
              link.setId(7L);
              return link;
            });

    FriendLinkDto result = service.addFriend(request);

    assertEquals(11L, result.tenantId());
    assertEquals(2L, result.accountId());
    assertEquals(3L, result.friendAccountId());
    assertNotNull(result.createdAt());
  }

  @Test
  void listFriendPresenceReturnsOrderedSnapshotsFromGameSession() {
    AccountFriendLink link = new AccountFriendLink();
    link.setTenantId(11L);
    link.setAccountId(2L);
    link.setFriendAccountId(3L);
    link.setStatus("active");
    when(accountRepository.findByTenantIdAndAccountIdAndStatus(11L, 2L, "active"))
        .thenReturn(List.of(link));
    when(gameSessionClient.queryAccountPresence(11L, 2L, List.of(3L)))
        .thenReturn(
            QueryAccountPresenceResponse.newBuilder()
                .addPresences(
                    AccountPresenceEntry.newBuilder()
                        .setAccountId("3")
                        .setOnline(true)
                        .setGameInstanceId("9")
                        .setWorldSlug("demo")
                        .setWorldDisplayName("Demo World")
                        .setRealmSlug("production")
                        .setRealmDisplayName("Live Realm")
                        .setPointerVersion(17)
                        .setCharacterId("99")
                        .setCharacterName("Ben")
                        .setLastSeenAtMs(Instant.parse("2026-04-11T06:15:30Z").toEpochMilli())
                        .setVisibilityPolicy(
                            AccountPresenceVisibilityPolicy
                                .ACCOUNT_PRESENCE_VISIBILITY_POLICY_FRIENDS_ONLY)
                        .setRecentDisposition(
                            net.firedevops.firemud.gamesession.v1.AccountRecentPresenceDisposition
                                .ACCOUNT_RECENT_PRESENCE_DISPOSITION_TRANSPORT_LOSS)
                        .setActivityState(
                            AccountPresenceActivityState.ACCOUNT_PRESENCE_ACTIVITY_STATE_AUTO_AFK)
                        .build())
                .build());

    var result = service.listFriendPresence(11L, 2L);

    assertEquals(1, result.size());
    assertEquals(3L, result.get(0).friendAccountId());
    assertEquals(true, result.get(0).online());
    assertEquals("demo", result.get(0).worldSlug());
    assertEquals("Demo World", result.get(0).worldDisplayName());
    assertEquals("production", result.get(0).realmSlug());
    assertEquals("Live Realm", result.get(0).realmDisplayName());
    assertEquals(17L, result.get(0).pointerVersion());
    assertEquals(FriendPresenceActivityState.AUTO_AFK, result.get(0).activityState());
    assertEquals(Instant.parse("2026-04-11T06:15:30Z"), result.get(0).lastSeenAt());
    assertEquals(FriendRecentPresenceDisposition.TRANSPORT_LOSS, result.get(0).recentDisposition());
  }

  @Test
  void listFriendPresenceSuppressesPrivateAndHiddenStaffDetails() {
    AccountFriendLink privateLink = new AccountFriendLink();
    privateLink.setTenantId(11L);
    privateLink.setAccountId(2L);
    privateLink.setFriendAccountId(3L);
    privateLink.setStatus("active");
    AccountFriendLink hiddenLink = new AccountFriendLink();
    hiddenLink.setTenantId(11L);
    hiddenLink.setAccountId(2L);
    hiddenLink.setFriendAccountId(4L);
    hiddenLink.setStatus("active");
    when(accountRepository.findByTenantIdAndAccountIdAndStatus(11L, 2L, "active"))
        .thenReturn(List.of(privateLink, hiddenLink));
    when(gameSessionClient.queryAccountPresence(11L, 2L, List.of(3L, 4L)))
        .thenReturn(
            QueryAccountPresenceResponse.newBuilder()
                .addPresences(
                    AccountPresenceEntry.newBuilder()
                        .setAccountId("3")
                        .setOnline(true)
                        .setGameInstanceId("9")
                        .setWorldSlug("demo")
                        .setWorldDisplayName("Demo World")
                        .setRealmSlug("production")
                        .setRealmDisplayName("Live Realm")
                        .setCharacterId("99")
                        .setCharacterName("Ben")
                        .setVisibilityPolicy(
                            AccountPresenceVisibilityPolicy
                                .ACCOUNT_PRESENCE_VISIBILITY_POLICY_PRIVATE)
                        .setLastSeenAtMs(Instant.parse("2026-04-11T06:15:30Z").toEpochMilli())
                        .setRecentDisposition(
                            net.firedevops.firemud.gamesession.v1.AccountRecentPresenceDisposition
                                .ACCOUNT_RECENT_PRESENCE_DISPOSITION_LOGOUT)
                        .build())
                .addPresences(
                    AccountPresenceEntry.newBuilder()
                        .setAccountId("4")
                        .setOnline(true)
                        .setGameInstanceId("10")
                        .setCharacterId("100")
                        .setCharacterName("Admin")
                        .setVisibilityPolicy(
                            AccountPresenceVisibilityPolicy
                                .ACCOUNT_PRESENCE_VISIBILITY_POLICY_HIDDEN_STAFF)
                        .build())
                .build());

    var result = service.listFriendPresence(11L, 2L);

    assertEquals(true, result.get(0).online());
    assertEquals(null, result.get(0).characterName());
    assertEquals(null, result.get(0).gameInstanceId());
    assertEquals(null, result.get(0).worldSlug());
    assertEquals(null, result.get(0).worldDisplayName());
    assertEquals(null, result.get(0).realmSlug());
    assertEquals(null, result.get(0).realmDisplayName());
    assertEquals(Instant.parse("2026-04-11T06:15:30Z"), result.get(0).lastSeenAt());
    assertEquals(FriendRecentPresenceDisposition.LOGOUT, result.get(0).recentDisposition());
    assertEquals(false, result.get(1).online());
    assertEquals(null, result.get(1).characterName());
    assertEquals(null, result.get(1).lastSeenAt());
  }
}
