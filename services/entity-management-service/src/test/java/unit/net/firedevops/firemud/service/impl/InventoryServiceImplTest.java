package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.dto.InventoryEntryDto;
import net.firedevops.firemud.entity.InventoryEntry;
import net.firedevops.firemud.entity.InventoryKey;
import net.firedevops.firemud.mapper.InventoryEntryMapper;
import net.firedevops.firemud.repository.InventoryEntryRepository;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;

class InventoryServiceImplTest {
  @Test
  void listInventoryReturnsMappedDtos() {
    InventoryEntryRepository repo = Mockito.mock(InventoryEntryRepository.class);
    InventoryEntryMapper mapper = Mappers.getMapper(InventoryEntryMapper.class);
    var charRepo = Mockito.mock(net.firedevops.firemud.repository.CharacterRepository.class);
    var itemRepo = Mockito.mock(net.firedevops.firemud.repository.ItemRepository.class);
    InventoryServiceImpl service = new InventoryServiceImpl(repo, mapper, charRepo, itemRepo);

    InventoryEntry entry = new InventoryEntry();
    InventoryKey key = new InventoryKey();
    key.setCharacterId(1L);
    key.setItemId(2L);
    entry.setId(key);
    entry.setQuantity(3);

    when(repo.findAll()).thenReturn(List.of(entry));

    List<InventoryEntryDto> result = service.listInventory(1L);
    assertEquals(1, result.size());
    assertEquals(3, result.get(0).quantity());
  }
}
