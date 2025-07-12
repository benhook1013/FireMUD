package net.firedevops.firemud.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.List;
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
  @Transactional
  @Timed(value = "character.create")
  public CharacterDto create(CharacterDto dto) {
    Character entity = characterMapper.toEntity(dto);
    entity.setLevel(dto.level() > 0 ? dto.level() : 1);
    entity = characterRepository.save(entity);
    return characterMapper.toDto(entity);
  }

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

  @Override
  @Transactional
  @Timed(value = "character.update")
  public boolean updateEntity(Long characterId) {
    Character character = characterRepository.findById(characterId).orElseThrow();
    character.setLastLoginAt(java.time.Instant.now());
    characterRepository.save(character);
    return true;
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "character.listForAccount")
  public List<CharacterDto> listForAccount(Long accountId) {
    return characterRepository.findByAccountId(accountId).stream()
        .map(characterMapper::toDto)
        .toList();
  }
}
