package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.dto.CharacterDto;
import net.firedevops.firemud.entity.Character;
import net.firedevops.firemud.mapper.CharacterMapper;
import net.firedevops.firemud.repository.CharacterRepository;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;

class CharacterServiceImplTest {

  @Test
  void gainExperienceLevelsUp() {
    CharacterRepository repo = Mockito.mock(CharacterRepository.class);
    CharacterMapper mapper = Mappers.getMapper(CharacterMapper.class);
    CharacterServiceImpl service = new CharacterServiceImpl(repo, mapper);

    Character character = new Character();
    character.setId(1L);
    character.setTenantId(1L);
    character.setAccountId(1L);
    character.setName("Test");
    character.setLevel(1);
    character.setExperience(0);
    character.setStrength(1);
    character.setAgility(1);
    character.setIntelligence(1);
    character.setStamina(1);
    character.setHealth(10);
    character.setMana(5);

    when(repo.findById(1L)).thenReturn(Optional.of(character));
    when(repo.save(any(Character.class))).thenAnswer(a -> a.getArgument(0));

    CharacterDto dto = service.gainExperience(1L, 1000);
    assertEquals(2, dto.level());
    assertEquals(0, dto.experience());
  }

  @Test
  void getWithInventoryReturnsDto() {
    CharacterRepository repo = Mockito.mock(CharacterRepository.class);
    CharacterMapper mapper = Mappers.getMapper(CharacterMapper.class);
    CharacterServiceImpl service = new CharacterServiceImpl(repo, mapper);

    Character character = new Character();
    character.setId(1L);
    character.setTenantId(1L);
    character.setAccountId(1L);
    character.setName("Test");

    when(repo.findWithInventoryById(1L)).thenReturn(Optional.of(character));

    CharacterDto dto = service.getWithInventory(1L);
    assertEquals(1L, dto.id());
  }
}
