package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import net.firedevops.firemud.dto.AddFriendRequest;
import net.firedevops.firemud.dto.FriendLinkDto;
import net.firedevops.firemud.entity.FriendLink;
import net.firedevops.firemud.mapper.FriendLinkMapper;
import net.firedevops.firemud.repository.FriendLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;

class FriendServiceImplTest {
  private FriendLinkRepository repository;
  private FriendServiceImpl service;

  @BeforeEach
  void setUp() {
    repository = Mockito.mock(FriendLinkRepository.class);
    FriendLinkMapper mapper = Mappers.getMapper(FriendLinkMapper.class);
    service = new FriendServiceImpl(repository, mapper);
  }

  @Test
  void addFriendReturnsDto() {
    AddFriendRequest request = new AddFriendRequest(1L, 2L, 3L);
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
}
