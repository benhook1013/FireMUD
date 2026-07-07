package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.entitymanagement.repository.ItemRepository;
import net.firedevops.firemud.entitymanagement.repository.NpcRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EntityTemplateReferenceServiceImplTest {
  private ItemRepository itemRepository;
  private NpcRepository npcRepository;
  private EntityTemplateReferenceServiceImpl service;

  @BeforeEach
  void setup() {
    itemRepository = mock(ItemRepository.class);
    npcRepository = mock(NpcRepository.class);
    service = new EntityTemplateReferenceServiceImpl(itemRepository, npcRepository);
  }

  @Test
  void rejectsZeroTenantIdBeforeRepositoryReads() {
    assertThrows(IllegalArgumentException.class, () -> service.exists("0", "7", "ITEM", "11"));

    verifyNoInteractions(itemRepository, npcRepository);
  }

  @Test
  void rejectsZeroVersionIdBeforeRepositoryReads() {
    assertThrows(IllegalArgumentException.class, () -> service.exists("1", "0", "ITEM", "11"));

    verifyNoInteractions(itemRepository, npcRepository);
  }

  @Test
  void rejectsZeroTemplateIdBeforeRepositoryReads() {
    assertThrows(IllegalArgumentException.class, () -> service.exists("1", "7", "ITEM", "0"));

    verifyNoInteractions(itemRepository, npcRepository);
  }

  @Test
  void delegatesItemTemplateChecksToItemRepository() {
    when(itemRepository.findByTenantIdAndVersionIdAndId(1L, 7L, 11L)).thenReturn(Optional.empty());

    assertFalse(service.exists("1", "7", "ITEM", "11"));

    verify(itemRepository).findByTenantIdAndVersionIdAndId(1L, 7L, 11L);
    verifyNoInteractions(npcRepository);
  }
}
