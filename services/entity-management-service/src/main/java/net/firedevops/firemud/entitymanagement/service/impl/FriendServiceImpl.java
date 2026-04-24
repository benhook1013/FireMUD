package net.firedevops.firemud.entitymanagement.service.impl;

import io.micrometer.core.annotation.Timed;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entitymanagement.dto.CharacterFriendDto;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.entity.CharacterFriend;
import net.firedevops.firemud.entitymanagement.entity.CharacterFriendKey;
import net.firedevops.firemud.entitymanagement.mapper.CharacterFriendMapper;
import net.firedevops.firemud.entitymanagement.repository.CharacterFriendRepository;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.service.FriendService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FriendServiceImpl implements FriendService {
  private final CharacterFriendRepository repository;
  private final CharacterRepository characterRepository;
  private final CharacterFriendMapper mapper;

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "friend.list")
  public Page<CharacterFriendDto> listFriends(Long tenantId, Long characterId, Pageable pageable) {
    requireCharacterInTenant(tenantId, characterId);
    return repository.findByIdCharacterId(characterId, pageable).map(mapper::toDto);
  }

  @Override
  @Transactional
  @Timed(value = "friend.add")
  public CharacterFriendDto addFriend(Long tenantId, Long characterId, Long friendId) {
    Character character = requireCharacterInTenant(tenantId, characterId);
    Character friend = characterRepository.findById(friendId).orElseThrow();
    requireSamePlayableState(character, friend);
    CharacterFriendKey key = new CharacterFriendKey();
    key.setCharacterId(characterId);
    key.setFriendId(friendId);
    CharacterFriend entity = repository.findById(key).orElse(null);
    if (entity == null) {
      entity = new CharacterFriend();
      entity.setId(key);
      entity.setCharacter(character);
      entity.setFriend(friend);
      entity.setTenantId(character.getTenantId());
      entity.setCreatedAt(Instant.now());
    }
    entity = repository.save(entity);
    return mapper.toDto(entity);
  }

  @Override
  @Transactional
  @Timed(value = "friend.remove")
  public void removeFriend(Long tenantId, Long characterId, Long friendId) {
    Character character = requireCharacterInTenant(tenantId, characterId);
    Character friend = characterRepository.findById(friendId).orElseThrow();
    requireSamePlayableState(character, friend);
    CharacterFriendKey key = new CharacterFriendKey();
    key.setCharacterId(characterId);
    key.setFriendId(friendId);
    repository.deleteById(key);
  }

  private void requireSamePlayableState(Character character, Character friend) {
    if (!character.getTenantId().equals(friend.getTenantId())) {
      throw new IllegalArgumentException("Characters must belong to the same tenant");
    }
    if (!character.getPlayableStateKey().equals(friend.getPlayableStateKey())) {
      throw new IllegalArgumentException("Characters must belong to the same playable state");
    }
  }

  private Character requireCharacterInTenant(Long tenantId, Long characterId) {
    Character character = characterRepository.findById(characterId).orElseThrow();
    if (!character.getTenantId().equals(tenantId)) {
      throw new IllegalArgumentException("Character does not belong to tenant");
    }
    return character;
  }
}
