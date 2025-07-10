package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.CharacterDto;

public interface CharacterService {
  CharacterDto gainExperience(Long characterId, int amount);
}
