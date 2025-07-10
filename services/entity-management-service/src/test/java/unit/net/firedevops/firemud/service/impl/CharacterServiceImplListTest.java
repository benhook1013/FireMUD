package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.dto.CharacterDto;
import net.firedevops.firemud.entity.Character;
import net.firedevops.firemud.mapper.CharacterMapper;
import net.firedevops.firemud.repository.CharacterRepository;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;

class CharacterServiceImplListTest {
  @Test
  void listForAccountReturnsDtos() {
    CharacterRepository repo = Mockito.mock(CharacterRepository.class);
    CharacterMapper mapper = Mappers.getMapper(CharacterMapper.class);
    CharacterServiceImpl service = new CharacterServiceImpl(repo, mapper);

    Character c = new Character();
    c.setId(1L);
    c.setTenantId(1L);
    c.setAccountId(1L);
    c.setName("Hero");

    when(repo.findByAccountId(1L)).thenReturn(List.of(c));

    List<CharacterDto> result = service.listForAccount(1L);
    assertEquals(1, result.size());
    assertEquals("Hero", result.get(0).name());
  }
}
