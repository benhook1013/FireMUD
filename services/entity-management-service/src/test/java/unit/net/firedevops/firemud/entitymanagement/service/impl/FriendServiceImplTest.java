package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.dto.CharacterFriendDto;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.CharacterFriend;
import net.firedevops.firemud.entitymanagement.mapper.CharacterFriendMapper;
import net.firedevops.firemud.entitymanagement.repository.CharacterFriendRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;

class FriendServiceImplTest {
  private CharacterFriendRepository repository;
  private FriendServiceImpl service;
  private net.firedevops.firemud.entitymanagement.repository.CharacterRepository
      characterRepository;

  @BeforeEach
  void setUp() {
    repository = Mockito.mock(CharacterFriendRepository.class);
    characterRepository =
        Mockito.mock(net.firedevops.firemud.entitymanagement.repository.CharacterRepository.class);
    CharacterFriendMapper mapper = Mappers.getMapper(CharacterFriendMapper.class);
    service = new FriendServiceImpl(repository, characterRepository, mapper);
  }

  @Test
  void addFriendReturnsDto() {
    Character character = new Character();
    character.setId(2L);
    character.setTenantId(1L);
    Character friend = new Character();
    friend.setId(3L);
    friend.setTenantId(1L);
    when(characterRepository.findById(2L)).thenReturn(Optional.of(character));
    when(characterRepository.findById(3L)).thenReturn(Optional.of(friend));

    CharacterFriend saved = new CharacterFriend();
    net.firedevops.firemud.entitymanagement.entity.CharacterFriendKey key =
        new net.firedevops.firemud.entitymanagement.entity.CharacterFriendKey();
    key.setCharacterId(2L);
    key.setFriendId(3L);
    saved.setId(key);
    saved.setTenantId(1L);
    saved.setCreatedAt(Instant.now());
    when(repository.findById(Mockito.any())).thenReturn(Optional.empty());
    when(repository.save(Mockito.any(CharacterFriend.class))).thenReturn(saved);

    CharacterFriendDto result = service.addFriend(1L, 2L, 3L);
    assertEquals(2L, result.characterId());
    assertEquals(3L, result.friendId());
  }

  @Test
  void addFriendRejectsCrossTenantOwnership() {
    Character character = new Character();
    character.setId(2L);
    character.setTenantId(1L);
    Character friend = new Character();
    friend.setId(3L);
    friend.setTenantId(2L);
    when(characterRepository.findById(2L)).thenReturn(Optional.of(character));
    when(characterRepository.findById(3L)).thenReturn(Optional.of(friend));

    assertThrows(IllegalArgumentException.class, () -> service.addFriend(1L, 2L, 3L));
  }

  @Test
  void removeFriendRejectsCrossTenantOwnership() {
    Character character = new Character();
    character.setId(2L);
    character.setTenantId(1L);
    Character friend = new Character();
    friend.setId(3L);
    friend.setTenantId(2L);
    when(characterRepository.findById(2L)).thenReturn(Optional.of(character));
    when(characterRepository.findById(3L)).thenReturn(Optional.of(friend));

    assertThrows(IllegalArgumentException.class, () -> service.removeFriend(1L, 2L, 3L));
  }
}
