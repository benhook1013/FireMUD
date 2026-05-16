package net.firedevops.firemud.socialgroups.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.v1.AccountPresenceActivityState;
import net.firedevops.firemud.gamesession.v1.AccountPresenceEntry;
import net.firedevops.firemud.gamesession.v1.AccountPresenceVisibilityPolicy;
import net.firedevops.firemud.gamesession.v1.QueryAccountPresenceResponse;
import net.firedevops.firemud.socialgroups.client.AccountClient;
import net.firedevops.firemud.socialgroups.client.GameSessionClient;
import net.firedevops.firemud.socialgroups.dto.AddFriendRequest;
import net.firedevops.firemud.socialgroups.dto.FriendLinkDto;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceActivityState;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceVisibilityPolicyValue;
import net.firedevops.firemud.socialgroups.dto.FriendRecentPresenceDisposition;
import net.firedevops.firemud.socialgroups.dto.FriendRosterFilter;
import net.firedevops.firemud.socialgroups.dto.FriendRosterSummaryDto;
import net.firedevops.firemud.socialgroups.entity.AccountFriendLink;
import net.firedevops.firemud.socialgroups.repository.AccountFriendLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class FriendServiceImplTest {
  private AccountFriendLinkRepository accountRepository;
  private GameSessionClient gameSessionClient;
  private AccountClient accountClient;
  private FriendServiceImpl service;

  @BeforeEach
  void setUp() {
    accountRepository = Mockito.mock(AccountFriendLinkRepository.class);
    gameSessionClient = Mockito.mock(GameSessionClient.class);
    accountClient = Mockito.mock(AccountClient.class);
    service = new FriendServiceImpl(accountRepository, gameSessionClient, accountClient);
  }

  @Test
  void addFriendCreatesAccountScopedLink() {
    AddFriendRequest request = new AddFriendRequest(11L, 2L, 3L);
    when(accountRepository.findFirstByTenantIdAndAccountIdAndFriendAccountIdAndStatus(
            11L, 2L, 3L, "active"))
        .thenReturn(java.util.Optional.empty());
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
  void addFriendReusesExistingActiveAccountScopedLink() {
    AddFriendRequest request = new AddFriendRequest(11L, 2L, 3L);
    AccountFriendLink existing = new AccountFriendLink();
    existing.setId(7L);
    existing.setTenantId(11L);
    existing.setAccountId(2L);
    existing.setFriendAccountId(3L);
    existing.setStatus("active");
    existing.setCreatedAt(Instant.parse("2026-04-10T01:02:03Z"));
    when(accountRepository.findFirstByTenantIdAndAccountIdAndFriendAccountIdAndStatus(
            11L, 2L, 3L, "active"))
        .thenReturn(java.util.Optional.of(existing));

    FriendLinkDto result = service.addFriend(request);

    assertEquals(7L, result.id());
    assertEquals(Instant.parse("2026-04-10T01:02:03Z"), result.createdAt());
    Mockito.verify(accountRepository, Mockito.never()).save(any(AccountFriendLink.class));
  }

  @Test
  void addFriendRejectsSelfLink() {
    AddFriendRequest request = new AddFriendRequest(11L, 2L, 2L);

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> service.addFriend(request));

    assertEquals("Cannot add or remove your own account as a friend", error.getMessage());
    Mockito.verify(accountRepository, Mockito.never())
        .findFirstByTenantIdAndAccountIdAndFriendAccountIdAndStatus(
            Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString());
  }

  @Test
  void removeFriendDeletesExistingActiveAccountScopedLink() {
    AccountFriendLink existing = new AccountFriendLink();
    existing.setId(7L);
    existing.setTenantId(11L);
    existing.setAccountId(2L);
    existing.setFriendAccountId(3L);
    existing.setStatus("active");
    when(accountRepository.findFirstByTenantIdAndAccountIdAndFriendAccountIdAndStatus(
            11L, 2L, 3L, "active"))
        .thenReturn(java.util.Optional.of(existing));

    service.removeFriend(11L, 2L, 3L);

    Mockito.verify(accountRepository).delete(existing);
  }

  @Test
  void removeFriendIsIdempotentWhenActiveLinkDoesNotExist() {
    when(accountRepository.findFirstByTenantIdAndAccountIdAndFriendAccountIdAndStatus(
            11L, 2L, 3L, "active"))
        .thenReturn(java.util.Optional.empty());

    service.removeFriend(11L, 2L, 3L);

    Mockito.verify(accountRepository, Mockito.never()).delete(any(AccountFriendLink.class));
  }

  @Test
  void getFriendRosterSummaryReturnsCanonicalCounts() {
    AccountFriendLink sora = new AccountFriendLink();
    sora.setId(7L);
    sora.setTenantId(11L);
    sora.setAccountId(2L);
    sora.setFriendAccountId(3L);
    sora.setStatus("active");
    sora.setCreatedAt(Instant.parse("2026-04-10T01:02:03Z"));
    AccountFriendLink nyx = new AccountFriendLink();
    nyx.setId(8L);
    nyx.setTenantId(11L);
    nyx.setAccountId(2L);
    nyx.setFriendAccountId(4L);
    nyx.setStatus("active");
    nyx.setCreatedAt(Instant.parse("2026-04-10T01:02:04Z"));
    when(accountRepository.findByTenantIdAndAccountIdAndStatus(11L, 2L, "active"))
        .thenReturn(List.of(sora, nyx));
    when(gameSessionClient.queryAccountPresence(11L, 2L, List.of(3L, 4L)))
        .thenReturn(
            QueryAccountPresenceResponse.newBuilder()
                .addPresences(
                    AccountPresenceEntry.newBuilder()
                        .setAccountId("3")
                        .setOnline(true)
                        .setVisibilityPolicy(
                            AccountPresenceVisibilityPolicy
                                .ACCOUNT_PRESENCE_VISIBILITY_POLICY_FRIENDS_ONLY)
                        .build())
                .addPresences(
                    AccountPresenceEntry.newBuilder()
                        .setAccountId("4")
                        .setOnline(false)
                        .setLastSeenAtMs(Instant.parse("2026-04-11T06:15:30Z").toEpochMilli())
                        .setVisibilityPolicy(
                            AccountPresenceVisibilityPolicy
                                .ACCOUNT_PRESENCE_VISIBILITY_POLICY_FRIENDS_ONLY)
                        .build())
                .build());

    FriendRosterSummaryDto result = service.getFriendRosterSummary(11L, 2L);

    assertEquals(2, result.totalCount());
    assertEquals(1, result.onlineCount());
    assertEquals(1, result.offlineCount());
    assertEquals(1, result.recentCount());
    assertEquals(0, result.publicCount());
    assertEquals(2, result.friendsOnlyCount());
    assertEquals(0, result.privateCount());
    assertEquals(0, result.hiddenStaffCount());
    assertEquals(0, result.unspecifiedVisibilityCount());
    assertEquals(0, result.sharedCount());
    assertEquals(0, result.isolatedCount());
    assertEquals(2, result.unspecifiedScopeCount());
  }

  @Test
  void getFriendByOrdinalReturnsCanonicalRosterEntry() {
    AccountFriendLink sora = new AccountFriendLink();
    sora.setId(7L);
    sora.setTenantId(11L);
    sora.setAccountId(2L);
    sora.setFriendAccountId(3L);
    sora.setStatus("active");
    sora.setCreatedAt(Instant.parse("2026-04-10T01:02:03Z"));
    when(accountRepository.findByTenantIdAndAccountIdAndStatus(11L, 2L, "active"))
        .thenReturn(List.of(sora));
    when(gameSessionClient.queryAccountPresence(11L, 2L, List.of(3L)))
        .thenReturn(
            QueryAccountPresenceResponse.newBuilder()
                .addPresences(
                    AccountPresenceEntry.newBuilder()
                        .setAccountId("3")
                        .setOnline(true)
                        .setCharacterName("Ben")
                        .setVisibilityPolicy(
                            AccountPresenceVisibilityPolicy
                                .ACCOUNT_PRESENCE_VISIBILITY_POLICY_FRIENDS_ONLY)
                        .build())
                .build());

    var result = service.getFriendByOrdinal(11L, 2L, 1);

    assertTrue(result.isPresent());
    assertEquals(1, result.orElseThrow().ordinal());
    assertEquals(3L, result.orElseThrow().friendAccountId());
    assertEquals("Ben", result.orElseThrow().presence().characterName());
  }

  @Test
  void removeFriendByOrdinalRemovesCanonicalRosterEntry() {
    AccountFriendLink sora = new AccountFriendLink();
    sora.setId(7L);
    sora.setTenantId(11L);
    sora.setAccountId(2L);
    sora.setFriendAccountId(3L);
    sora.setStatus("active");
    sora.setCreatedAt(Instant.parse("2026-04-10T01:02:03Z"));
    when(accountRepository.findByTenantIdAndAccountIdAndStatus(11L, 2L, "active"))
        .thenReturn(List.of(sora));
    when(accountRepository.findFirstByTenantIdAndAccountIdAndFriendAccountIdAndStatus(
            11L, 2L, 3L, "active"))
        .thenReturn(java.util.Optional.of(sora));
    when(gameSessionClient.queryAccountPresence(11L, 2L, List.of(3L)))
        .thenReturn(QueryAccountPresenceResponse.newBuilder().build());

    var result = service.removeFriendByOrdinal(11L, 2L, 1);

    assertTrue(result.isPresent());
    assertEquals(3L, result.orElseThrow().friendAccountId());
    Mockito.verify(accountRepository).delete(sora);
  }

  @Test
  void listFriendsFallsBackToOfflineDefaultsWhenPresenceClientReturnsNull() {
    AccountFriendLink sora = new AccountFriendLink();
    sora.setId(7L);
    sora.setTenantId(11L);
    sora.setAccountId(2L);
    sora.setFriendAccountId(3L);
    sora.setStatus("active");
    sora.setCreatedAt(Instant.parse("2026-04-10T01:02:03Z"));
    when(accountRepository.findByTenantIdAndAccountIdAndStatus(11L, 2L, "active"))
        .thenReturn(List.of(sora));
    when(gameSessionClient.queryAccountPresence(11L, 2L, List.of(3L))).thenReturn(null);

    var result = service.listFriends(11L, 2L, FriendRosterFilter.ALL);

    assertEquals(1, result.totalCount());
    assertEquals(false, result.friends().getFirst().presence().online());
    assertEquals(null, result.friends().getFirst().presence().characterName());
  }

  @Test
  void getFriendPresencePolicyReturnsCanonicalAccountPolicy() {
    when(accountClient.getPresenceVisibilityPolicy(11L, 2L))
        .thenReturn(Optional.of(FriendPresenceVisibilityPolicyValue.PRIVATE));

    var result = service.getFriendPresencePolicy(11L, 2L);

    assertEquals(FriendPresenceVisibilityPolicyValue.PRIVATE, result.currentPolicy());
  }

  @Test
  void updateFriendPresencePolicyRejectsHiddenStaff() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.updateFriendPresencePolicy(
                    11L, 2L, FriendPresenceVisibilityPolicyValue.HIDDEN_STAFF));

    assertEquals("Friend presence visibility policy HIDDEN_STAFF is reserved", error.getMessage());
  }

  @Test
  void updateFriendPresencePolicyWritesCanonicalAccountPolicy() {
    when(accountClient.updatePresenceVisibilityPolicy(
            11L, 2L, FriendPresenceVisibilityPolicyValue.PUBLIC))
        .thenReturn(true);

    var result =
        service.updateFriendPresencePolicy(11L, 2L, FriendPresenceVisibilityPolicyValue.PUBLIC);

    assertEquals(FriendPresenceVisibilityPolicyValue.PUBLIC, result.currentPolicy());
  }

  @Test
  void removeFriendRejectsSelfLink() {
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> service.removeFriend(11L, 2L, 2L));

    assertEquals("Cannot add or remove your own account as a friend", error.getMessage());
    Mockito.verify(accountRepository, Mockito.never())
        .findFirstByTenantIdAndAccountIdAndFriendAccountIdAndStatus(
            Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString());
  }

  @Test
  void getFriendReturnsCanonicalRosterEntryWithEmbeddedPresence() {
    AccountFriendLink link = new AccountFriendLink();
    link.setId(7L);
    link.setTenantId(11L);
    link.setAccountId(2L);
    link.setFriendAccountId(3L);
    link.setStatus("active");
    link.setCreatedAt(Instant.parse("2026-04-10T01:02:03Z"));
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
                        .setPlayableStateScope(
                            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                                .PLAYABLE_STATE_SCOPE_SHARED)
                        .setWorldSlug("demo")
                        .setWorldDisplayName("Demo World")
                        .setRealmSlug("production")
                        .setRealmDisplayName("Live Realm")
                        .setPointerVersion(17)
                        .setCharacterId("99")
                        .setCharacterName("Ben")
                        .setVisibilityPolicy(
                            AccountPresenceVisibilityPolicy
                                .ACCOUNT_PRESENCE_VISIBILITY_POLICY_FRIENDS_ONLY)
                        .build())
                .build());

    var result = service.getFriend(11L, 2L, 3L);

    assertTrue(result.isPresent());
    assertEquals(7L, result.orElseThrow().friendLinkId());
    assertEquals(3L, result.orElseThrow().friendAccountId());
    assertEquals(true, result.orElseThrow().presence().online());
    assertEquals("Ben", result.orElseThrow().presence().characterName());
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

    assertEquals(FriendRosterFilter.ALL, result.filter());
    assertEquals(1, result.totalCount());
    assertEquals(1, result.matchCount());
    assertEquals(1, result.presences().size());
    assertEquals(3L, result.presences().get(0).friendAccountId());
    assertEquals(true, result.presences().get(0).online());
    assertEquals("demo", result.presences().get(0).worldSlug());
    assertEquals("Demo World", result.presences().get(0).worldDisplayName());
    assertEquals("production", result.presences().get(0).realmSlug());
    assertEquals("Live Realm", result.presences().get(0).realmDisplayName());
    assertEquals(17L, result.presences().get(0).pointerVersion());
    assertEquals(FriendPresenceActivityState.AUTO_AFK, result.presences().get(0).activityState());
    assertEquals(Instant.parse("2026-04-11T06:15:30Z"), result.presences().get(0).lastSeenAt());
    assertEquals(
        FriendRecentPresenceDisposition.TRANSPORT_LOSS,
        result.presences().get(0).recentDisposition());
  }

  @Test
  void listFriendsReturnsRosterEntriesWithEmbeddedPresence() {
    AccountFriendLink link = new AccountFriendLink();
    link.setId(7L);
    link.setTenantId(11L);
    link.setAccountId(2L);
    link.setFriendAccountId(3L);
    link.setStatus("active");
    link.setCreatedAt(Instant.parse("2026-04-10T01:02:03Z"));
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
                        .setPlayableStateScope(
                            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                                .PLAYABLE_STATE_SCOPE_SHARED)
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

    var result = service.listFriends(11L, 2L, FriendRosterFilter.ALL);

    assertEquals(FriendRosterFilter.ALL, result.filter());
    assertEquals(1, result.totalCount());
    assertEquals(1, result.matchCount());
    assertEquals(7L, result.friends().get(0).friendLinkId());
    assertEquals(11L, result.friends().get(0).tenantId());
    assertEquals(2L, result.friends().get(0).accountId());
    assertEquals(3L, result.friends().get(0).friendAccountId());
    assertEquals("active", result.friends().get(0).status());
    assertEquals(Instant.parse("2026-04-10T01:02:03Z"), result.friends().get(0).createdAt());
    assertEquals(true, result.friends().get(0).presence().online());
    assertEquals("SHARED", result.friends().get(0).presence().playableStateScope());
    assertEquals("Ben", result.friends().get(0).presence().characterName());
  }

  @Test
  void listFriendsFiltersOnlineWithoutRenumberingCanonicalOrdinals() {
    AccountFriendLink onlineLink = new AccountFriendLink();
    onlineLink.setId(7L);
    onlineLink.setTenantId(11L);
    onlineLink.setAccountId(2L);
    onlineLink.setFriendAccountId(3L);
    onlineLink.setStatus("active");
    AccountFriendLink offlineLink = new AccountFriendLink();
    offlineLink.setId(8L);
    offlineLink.setTenantId(11L);
    offlineLink.setAccountId(2L);
    offlineLink.setFriendAccountId(4L);
    offlineLink.setStatus("active");
    when(accountRepository.findByTenantIdAndAccountIdAndStatus(11L, 2L, "active"))
        .thenReturn(List.of(onlineLink, offlineLink));
    when(gameSessionClient.queryAccountPresence(11L, 2L, List.of(3L, 4L)))
        .thenReturn(
            QueryAccountPresenceResponse.newBuilder()
                .addPresences(
                    AccountPresenceEntry.newBuilder()
                        .setAccountId("3")
                        .setOnline(true)
                        .setVisibilityPolicy(
                            AccountPresenceVisibilityPolicy
                                .ACCOUNT_PRESENCE_VISIBILITY_POLICY_FRIENDS_ONLY)
                        .build())
                .addPresences(
                    AccountPresenceEntry.newBuilder()
                        .setAccountId("4")
                        .setOnline(false)
                        .setVisibilityPolicy(
                            AccountPresenceVisibilityPolicy
                                .ACCOUNT_PRESENCE_VISIBILITY_POLICY_FRIENDS_ONLY)
                        .build())
                .build());

    var result = service.listFriends(11L, 2L, FriendRosterFilter.ONLINE);

    assertEquals(FriendRosterFilter.ONLINE, result.filter());
    assertEquals(2, result.totalCount());
    assertEquals(1, result.matchCount());
    assertEquals(1, result.friends().size());
    assertEquals(1, result.friends().get(0).ordinal());
    assertEquals(3L, result.friends().get(0).friendAccountId());
  }

  @Test
  void listFriendsFiltersVisibilityPolicyWithoutRenumberingCanonicalOrdinals() {
    AccountFriendLink friendsOnlyLink = new AccountFriendLink();
    friendsOnlyLink.setId(7L);
    friendsOnlyLink.setTenantId(11L);
    friendsOnlyLink.setAccountId(2L);
    friendsOnlyLink.setFriendAccountId(3L);
    friendsOnlyLink.setStatus("active");
    AccountFriendLink privateLink = new AccountFriendLink();
    privateLink.setId(8L);
    privateLink.setTenantId(11L);
    privateLink.setAccountId(2L);
    privateLink.setFriendAccountId(4L);
    privateLink.setStatus("active");
    when(accountRepository.findByTenantIdAndAccountIdAndStatus(11L, 2L, "active"))
        .thenReturn(List.of(friendsOnlyLink, privateLink));
    when(gameSessionClient.queryAccountPresence(11L, 2L, List.of(3L, 4L)))
        .thenReturn(
            QueryAccountPresenceResponse.newBuilder()
                .addPresences(
                    AccountPresenceEntry.newBuilder()
                        .setAccountId("3")
                        .setOnline(true)
                        .setVisibilityPolicy(
                            AccountPresenceVisibilityPolicy
                                .ACCOUNT_PRESENCE_VISIBILITY_POLICY_FRIENDS_ONLY)
                        .build())
                .addPresences(
                    AccountPresenceEntry.newBuilder()
                        .setAccountId("4")
                        .setOnline(false)
                        .setVisibilityPolicy(
                            AccountPresenceVisibilityPolicy
                                .ACCOUNT_PRESENCE_VISIBILITY_POLICY_PRIVATE)
                        .build())
                .build());

    var result = service.listFriends(11L, 2L, FriendRosterFilter.FRIENDS_ONLY);

    assertEquals(FriendRosterFilter.FRIENDS_ONLY, result.filter());
    assertEquals(2, result.totalCount());
    assertEquals(1, result.matchCount());
    assertEquals(1, result.friends().size());
    assertEquals(1, result.friends().get(0).ordinal());
    assertEquals(3L, result.friends().get(0).friendAccountId());
    assertEquals("FRIENDS_ONLY", result.friends().get(0).presence().visibilityPolicy());
  }

  @Test
  void listFriendsFiltersPlayableStateScopeWithoutRenumberingCanonicalOrdinals() {
    AccountFriendLink sharedLink = new AccountFriendLink();
    sharedLink.setId(7L);
    sharedLink.setTenantId(11L);
    sharedLink.setAccountId(2L);
    sharedLink.setFriendAccountId(3L);
    sharedLink.setStatus("active");
    AccountFriendLink isolatedLink = new AccountFriendLink();
    isolatedLink.setId(8L);
    isolatedLink.setTenantId(11L);
    isolatedLink.setAccountId(2L);
    isolatedLink.setFriendAccountId(4L);
    isolatedLink.setStatus("active");
    when(accountRepository.findByTenantIdAndAccountIdAndStatus(11L, 2L, "active"))
        .thenReturn(List.of(sharedLink, isolatedLink));
    when(gameSessionClient.queryAccountPresence(11L, 2L, List.of(3L, 4L)))
        .thenReturn(
            QueryAccountPresenceResponse.newBuilder()
                .addPresences(
                    AccountPresenceEntry.newBuilder()
                        .setAccountId("3")
                        .setOnline(true)
                        .setPlayableStateScope(
                            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                                .PLAYABLE_STATE_SCOPE_SHARED)
                        .setVisibilityPolicy(
                            AccountPresenceVisibilityPolicy
                                .ACCOUNT_PRESENCE_VISIBILITY_POLICY_FRIENDS_ONLY)
                        .build())
                .addPresences(
                    AccountPresenceEntry.newBuilder()
                        .setAccountId("4")
                        .setOnline(true)
                        .setPlayableStateScope(
                            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                                .PLAYABLE_STATE_SCOPE_ISOLATED)
                        .setVisibilityPolicy(
                            AccountPresenceVisibilityPolicy
                                .ACCOUNT_PRESENCE_VISIBILITY_POLICY_FRIENDS_ONLY)
                        .build())
                .build());

    var result = service.listFriends(11L, 2L, FriendRosterFilter.SHARED);

    assertEquals(FriendRosterFilter.SHARED, result.filter());
    assertEquals(2, result.totalCount());
    assertEquals(1, result.matchCount());
    assertEquals(1, result.friends().size());
    assertEquals(1, result.friends().get(0).ordinal());
    assertEquals(3L, result.friends().get(0).friendAccountId());
    assertEquals("SHARED", result.friends().get(0).presence().playableStateScope());
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

    assertEquals(2, result.totalCount());
    assertEquals(2, result.matchCount());
    assertEquals(true, result.presences().get(0).online());
    assertEquals(null, result.presences().get(0).characterName());
    assertEquals(null, result.presences().get(0).gameInstanceId());
    assertEquals(null, result.presences().get(0).worldSlug());
    assertEquals(null, result.presences().get(0).worldDisplayName());
    assertEquals(null, result.presences().get(0).realmSlug());
    assertEquals(null, result.presences().get(0).realmDisplayName());
    assertEquals(Instant.parse("2026-04-11T06:15:30Z"), result.presences().get(0).lastSeenAt());
    assertEquals(
        FriendRecentPresenceDisposition.LOGOUT, result.presences().get(0).recentDisposition());
    assertEquals(false, result.presences().get(1).online());
    assertEquals(null, result.presences().get(1).characterName());
    assertEquals(null, result.presences().get(1).lastSeenAt());
  }
}
