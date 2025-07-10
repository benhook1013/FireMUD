package net.firedevops.firemud.service.impl;

import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.dto.CharacterDto;
import net.firedevops.firemud.entity.Character;
import net.firedevops.firemud.mapper.CharacterMapper;
import net.firedevops.firemud.repository.CharacterRepository;
import net.firedevops.firemud.service.CharacterService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CharacterServiceImpl implements CharacterService {

  private final CharacterRepository characterRepository;
  private final CharacterMapper characterMapper;

  private static final int EXP_PER_LEVEL = 1000;

  @Override
  @Transactional
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
