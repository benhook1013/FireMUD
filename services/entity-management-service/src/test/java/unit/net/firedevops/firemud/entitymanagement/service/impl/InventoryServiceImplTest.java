package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.entitymanagement.entity.InventoryEntry;
import net.firedevops.firemud.entitymanagement.entity.InventoryKey;
import net.firedevops.firemud.entitymanagement.mapper.InventoryEntryMapper;
import net.firedevops.firemud.entitymanagement.repository.InventoryEntryRepository;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class InventoryServiceImplTest {
  @Test
  void listInventoryReturnsMappedDtos() {
    InventoryEntryRepository repo = Mockito.mock(InventoryEntryRepository.class);
    InventoryEntryMapper mapper = Mappers.getMapper(InventoryEntryMapper.class);
    var charRepo =
        Mockito.mock(net.firedevops.firemud.entitymanagement.repository.CharacterRepository.class);
    var itemRepo =
        Mockito.mock(net.firedevops.firemud.entitymanagement.repository.ItemRepository.class);
    InventoryServiceImpl service = new InventoryServiceImpl(repo, mapper, charRepo, itemRepo);

    InventoryEntry entry = new InventoryEntry();
    InventoryKey key = new InventoryKey();
    key.setCharacterId(1L);
    key.setItemId(2L);
    entry.setId(key);
    entry.setQuantity(3);

    when(repo.findByIdCharacterId(1L, Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(entry)));

    var result = service.listInventory(1L, Pageable.unpaged());
    assertEquals(1, result.getTotalElements());
    assertEquals(3, result.getContent().get(0).quantity());
  }
}
