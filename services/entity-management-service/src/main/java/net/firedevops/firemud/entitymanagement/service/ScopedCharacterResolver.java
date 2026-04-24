package net.firedevops.firemud.entitymanagement.service;

import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ScopedCharacterResolver {
  private final CharacterRepository characterRepository;
  private final PlayableStateKeyResolver playableStateKeyResolver;

  public Character requireScopedCharacter(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope) {
    String playableStateKey = playableStateKeyResolver.resolve(gameInstanceId, playableStateScope);
    return characterRepository
        .findByIdAndTenantIdAndPlayableStateKey(characterId, tenantId, playableStateKey)
        .or(
            () ->
                characterRepository
                    .findByIdAndTenantId(characterId, tenantId)
                    .filter(character -> playableStateKey.equals(character.getPlayableStateKey())))
        .orElseThrow(
            () -> new IllegalArgumentException("Character does not belong to gameplay scope"));
  }
}
