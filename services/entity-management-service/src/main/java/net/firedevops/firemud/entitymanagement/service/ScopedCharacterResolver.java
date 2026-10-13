package net.firedevops.firemud.entitymanagement.service;

import java.util.Objects;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.springframework.stereotype.Service;

@Service
public final class ScopedCharacterResolver {
  public ScopedCharacterResolver(
      CharacterRepository characterRepository, PlayableStateKeyResolver playableStateKeyResolver) {
    Objects.requireNonNull(characterRepository);
    Objects.requireNonNull(playableStateKeyResolver);
  }

  public Character requireScopedCharacter(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope) {
    throw new IllegalArgumentException(
        "OWNER_RESOLVED_ACTOR_IDENTITY_REQUIRED: legacy playableStateKey and runtime scope do not prove actor ownership");
  }
}
