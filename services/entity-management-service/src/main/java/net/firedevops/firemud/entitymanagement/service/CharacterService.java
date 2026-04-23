package net.firedevops.firemud.entitymanagement.service;

import net.firedevops.firemud.entitymanagement.dto.CharacterDto;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CharacterService {
  /** Returns a character with inventory entries cached in Redis. */
  CharacterDto getWithInventory(Long characterId);

  /** Creates a new character with default stats. */
  CharacterDto create(
      Long tenantId,
      Long accountId,
      String name,
      String gameInstanceId,
      PlayableStateScope playableStateScope);

  CharacterDto gainExperience(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      int amount);

  /** Basic update example for testing. */
  boolean updateEntity(
      Long tenantId,
      Long characterId,
      String gameInstanceId,
      PlayableStateScope playableStateScope);

  /** Lists all characters visible for one resolved gameplay target. */
  Page<CharacterDto> listForGameplayScope(
      Long tenantId,
      Long accountId,
      String gameInstanceId,
      PlayableStateScope playableStateScope,
      Pageable pageable);

  java.util.Optional<CharacterDto> findByGameplayScopeAndName(
      Long tenantId, String gameInstanceId, PlayableStateScope playableStateScope, String name);
}
