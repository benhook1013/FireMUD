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
import net.firedevops.firemud.entitymanagement.service.PlayableStateKeyResolver;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
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
  private final PlayableStateKeyResolver playableStateKeyResolver;

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "friend.list")
  public Page<CharacterFriendDto> listFriends(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      Pageable pageable) {
    requireScopedCharacter(tenantId, characterId, gameInstanceId, playableStateScope);
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
        requireScopedCharacter(tenantId, characterId, gameInstanceId, playableStateScope);
    Character friend =
        requireScopedCharacter(tenantId, friendId, gameInstanceId, playableStateScope);
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
    requireScopedCharacter(tenantId, characterId, gameInstanceId, playableStateScope);
    requireScopedCharacter(tenantId, friendId, gameInstanceId, playableStateScope);
    CharacterFriendKey key = new CharacterFriendKey();
    key.setCharacterId(characterId);
    key.setFriendId(friendId);
    repository.deleteById(key);
  }

  private Character requireScopedCharacter(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope) {
    String playableStateKey = playableStateKeyResolver.resolve(gameInstanceId, playableStateScope);
    return characterRepository
        .findByIdAndTenantIdAndPlayableStateKey(characterId, tenantId, playableStateKey)
        .orElseThrow(
            () -> new IllegalArgumentException("Character does not belong to gameplay scope"));
  }
}
