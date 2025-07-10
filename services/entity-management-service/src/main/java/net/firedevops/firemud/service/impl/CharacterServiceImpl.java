package net.firedevops.firemud.service.impl;

import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.dto.CharacterDto;
import net.firedevops.firemud.entity.Character;
import net.firedevops.firemud.mapper.CharacterMapper;
import net.firedevops.firemud.repository.CharacterRepository;
import net.firedevops.firemud.service.CharacterService;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CharacterServiceImpl implements CharacterService {

  private final CharacterRepository characterRepository;
  private final CharacterMapper characterMapper;

  private static final int EXP_PER_LEVEL = 1000;

  @Override
  @Transactional(readOnly = true)
  @Cacheable(value = "characterGraph", key = "#characterId")
  @Timed(value = "character.get")
  public CharacterDto getWithInventory(Long characterId) {
    Character character = characterRepository.findWithInventoryById(characterId).orElseThrow();
    return characterMapper.toDto(character);
  }

  @Override
  @Transactional
  @Timed(value = "character.gainExperience")
  public CharacterDto gainExperience(Long characterId, int amount) {
    Character character = characterRepository.findById(characterId).orElseThrow();
    character.setExperience(character.getExperience() + amount);
    while (character.getExperience() >= character.getLevel() * EXP_PER_LEVEL) {
      character.setExperience(character.getExperience() - character.getLevel() * EXP_PER_LEVEL);
      character.setLevel(character.getLevel() + 1);
    }
    characterRepository.save(character);
    return characterMapper.toDto(character);
  }
}
