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
import net.firedevops.firemud.entitymanagement.service.FriendService;
import net.firedevops.firemud.entitymanagement.service.ScopedCharacterResolver;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FriendServiceImpl implements FriendService {
  private final CharacterFriendRepository repository;
  private final CharacterFriendMapper mapper;
  private final ScopedCharacterResolver scopedCharacterResolver;

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "friend.list")
  public Page<CharacterFriendDto> listFriends(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      Pageable pageable) {
    scopedCharacterResolver.requireScopedCharacter(
        tenantId, characterId, gameInstanceId, playableStateScope);
    return repository.findByIdCharacterId(characterId, pageable).map(mapper::toDto);
  }

  @Override
  @Transactional
  @Timed(value = "friend.add")
  public CharacterFriendDto addFriend(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      Long friendId) {
    Character character =
        scopedCharacterResolver.requireScopedCharacter(
            tenantId, characterId, gameInstanceId, playableStateScope);
    Character friend =
        scopedCharacterResolver.requireScopedCharacter(
            tenantId, friendId, gameInstanceId, playableStateScope);
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
  public void removeFriend(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      Long friendId) {
    scopedCharacterResolver.requireScopedCharacter(
        tenantId, characterId, gameInstanceId, playableStateScope);
    scopedCharacterResolver.requireScopedCharacter(
        tenantId, friendId, gameInstanceId, playableStateScope);
    CharacterFriendKey key = new CharacterFriendKey();
    key.setCharacterId(characterId);
    key.setFriendId(friendId);
    repository.deleteById(key);
  }
}
