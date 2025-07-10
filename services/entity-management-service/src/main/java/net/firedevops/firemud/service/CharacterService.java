package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.CharacterDto;

public interface CharacterService {
  /** Returns a character with inventory entries cached in Redis. */
  CharacterDto getWithInventory(Long characterId);

  CharacterDto gainExperience(Long characterId, int amount);
}
