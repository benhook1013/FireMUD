package net.firedevops.firemud.socialgroups.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.gamesession.v1.AccountPresenceActivityState;
import net.firedevops.firemud.gamesession.v1.AccountPresenceEntry;
import net.firedevops.firemud.gamesession.v1.QueryAccountPresenceResponse;
import net.firedevops.firemud.socialgroups.client.GameSessionClient;
import net.firedevops.firemud.socialgroups.dto.AddFriendRequest;
import net.firedevops.firemud.socialgroups.dto.FriendLinkDto;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceActivityState;
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
                        .setCharacterId("99")
                        .setCharacterName("Ben")
                        .setActivityState(
                            AccountPresenceActivityState.ACCOUNT_PRESENCE_ACTIVITY_STATE_AUTO_AFK)
                        .build())
                .build());

    var result = service.listFriendPresence(11L, 2L);

    assertEquals(1, result.size());
    assertEquals(3L, result.get(0).friendAccountId());
    assertEquals(true, result.get(0).online());
    assertEquals(FriendPresenceActivityState.AUTO_AFK, result.get(0).activityState());
  }
}
