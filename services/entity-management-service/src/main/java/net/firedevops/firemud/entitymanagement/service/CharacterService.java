package net.firedevops.firemud.entitymanagement.service;

import net.firedevops.firemud.entitymanagement.dto.CharacterDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CharacterService {
  /** Returns a character with inventory entries cached in Redis. */
  CharacterDto getWithInventory(Long characterId);

  /** Creates a new character with default stats. */
  CharacterDto create(CharacterDto dto);

  CharacterDto gainExperience(Long characterId, int amount);

  /** Basic update example for testing. */
  boolean updateEntity(Long characterId);

  /** Lists all characters for the given account across all tenants. */
  Page<CharacterDto> listForAccount(Long accountId, Pageable pageable);

  java.util.Optional<CharacterDto> findByTenantAndName(Long tenantId, String name);
}
