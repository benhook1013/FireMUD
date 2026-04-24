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
import net.firedevops.firemud.entitymanagement.service.PlayableStateKeyResolver;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
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
    service =
        new FriendServiceImpl(
            repository, characterRepository, mapper, new PlayableStateKeyResolver());
  }

  @Test
  void addFriendReturnsDto() {
    Character character = character(2L, 1L, "tenant:1:shared");
    Character friend = character(3L, 1L, "tenant:1:shared");
    when(characterRepository.findByIdAndTenantIdAndPlayableStateKey(2L, 1L, "shared-live"))
        .thenReturn(Optional.of(character));
    when(characterRepository.findByIdAndTenantIdAndPlayableStateKey(3L, 1L, "shared-live"))
        .thenReturn(Optional.of(friend));

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

    CharacterFriendDto result =
        service.addFriend(1L, 2L, "live", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, 3L);
    assertEquals(2L, result.characterId());
    assertEquals(3L, result.friendId());
  }

  @Test
  void addFriendRejectsCrossTenantOwnership() {
    Character character = character(2L, 1L, "tenant:1:shared");
    when(characterRepository.findByIdAndTenantIdAndPlayableStateKey(2L, 1L, "shared-live"))
        .thenReturn(Optional.of(character));
    when(characterRepository.findByIdAndTenantIdAndPlayableStateKey(3L, 1L, "shared-live"))
        .thenReturn(Optional.empty());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.addFriend(1L, 2L, "live", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, 3L));
  }

  @Test
  void removeFriendRejectsCrossTenantOwnership() {
    Character character = character(2L, 1L, "tenant:1:shared");
    when(characterRepository.findByIdAndTenantIdAndPlayableStateKey(2L, 1L, "shared-live"))
        .thenReturn(Optional.of(character));
    when(characterRepository.findByIdAndTenantIdAndPlayableStateKey(3L, 1L, "shared-live"))
        .thenReturn(Optional.empty());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.removeFriend(
                1L, 2L, "live", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, 3L));
  }

  @Test
  void addFriendRejectsCrossPlayableStateOwnership() {
    Character character = character(2L, 1L, "tenant:1:shared");
    when(characterRepository.findByIdAndTenantIdAndPlayableStateKey(2L, 1L, "shared-live"))
        .thenReturn(Optional.of(character));
    when(characterRepository.findByIdAndTenantIdAndPlayableStateKey(3L, 1L, "shared-live"))
        .thenReturn(Optional.empty());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.addFriend(1L, 2L, "live", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, 3L));
  }

  @Test
  void removeFriendRejectsCrossPlayableStateOwnership() {
    Character character = character(2L, 1L, "tenant:1:shared");
    when(characterRepository.findByIdAndTenantIdAndPlayableStateKey(2L, 1L, "shared-live"))
        .thenReturn(Optional.of(character));
    when(characterRepository.findByIdAndTenantIdAndPlayableStateKey(3L, 1L, "shared-live"))
        .thenReturn(Optional.empty());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.removeFriend(
                1L, 2L, "live", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, 3L));
  }

  private Character character(Long id, Long tenantId, String playableStateKey) {
    Character character = new Character();
    character.setId(id);
    character.setTenantId(tenantId);
    character.setPlayableStateKey(playableStateKey);
    return character;
  }
}
