package net.firedevops.firemud.service;

import java.util.List;
import net.firedevops.firemud.dto.CharacterDto;

public interface CharacterService {
  /** Returns a character with inventory entries cached in Redis. */
  CharacterDto getWithInventory(Long characterId);

  /** Creates a new character with default stats. */
  CharacterDto create(CharacterDto dto);

  CharacterDto gainExperience(Long characterId, int amount);

  /** Basic update example for testing. */
  boolean updateEntity(Long characterId);

  /** Lists all characters for the given account across all tenants. */
  List<CharacterDto> listForAccount(Long accountId);
}
