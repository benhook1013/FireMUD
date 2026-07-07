package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import net.firedevops.firemud.entitymanagement.repository.CraftingRecipeRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemRepository;
import net.firedevops.firemud.entitymanagement.repository.NpcRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class EntityDraftDesignDigestServiceImplTest {
  @Test
  void rejectsZeroTenantIdBeforeRepositoryReads() {
    ItemRepository itemRepository = Mockito.mock(ItemRepository.class);
    NpcRepository npcRepository = Mockito.mock(NpcRepository.class);
    CraftingRecipeRepository craftingRecipeRepository =
        Mockito.mock(CraftingRecipeRepository.class);
    EntityDraftDesignDigestServiceImpl service =
        new EntityDraftDesignDigestServiceImpl(
            itemRepository, npcRepository, craftingRecipeRepository, new ObjectMapper());

    assertThrows(IllegalArgumentException.class, () -> service.getDraftDesignDigest("0", "7"));

    Mockito.verifyNoInteractions(itemRepository, npcRepository, craftingRecipeRepository);
  }

  @Test
  void rejectsZeroVersionIdBeforeRepositoryReads() {
    ItemRepository itemRepository = Mockito.mock(ItemRepository.class);
    NpcRepository npcRepository = Mockito.mock(NpcRepository.class);
    CraftingRecipeRepository craftingRecipeRepository =
        Mockito.mock(CraftingRecipeRepository.class);
    EntityDraftDesignDigestServiceImpl service =
        new EntityDraftDesignDigestServiceImpl(
            itemRepository, npcRepository, craftingRecipeRepository, new ObjectMapper());

    assertThrows(IllegalArgumentException.class, () -> service.getDraftDesignDigest("1", "0"));

    Mockito.verifyNoInteractions(itemRepository, npcRepository, craftingRecipeRepository);
  }

  @Test
  void computesDigestFromVersionScopedTemplateRows() {
    ItemRepository itemRepository = Mockito.mock(ItemRepository.class);
    NpcRepository npcRepository = Mockito.mock(NpcRepository.class);
    CraftingRecipeRepository craftingRecipeRepository =
        Mockito.mock(CraftingRecipeRepository.class);
    Mockito.when(itemRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of());
    Mockito.when(npcRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of());
    Mockito.when(craftingRecipeRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of());
    EntityDraftDesignDigestServiceImpl service =
        new EntityDraftDesignDigestServiceImpl(
            itemRepository, npcRepository, craftingRecipeRepository, new ObjectMapper());

    var digest = service.getDraftDesignDigest("1", "7");

    assertEquals("1", digest.tenantId());
    assertEquals("7", digest.scopeValue());
    assertEquals("version:7", digest.appliedCommitId());
    Mockito.verify(itemRepository).findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L);
    Mockito.verify(itemRepository, Mockito.never()).findByTenantIdOrderByIdAsc(Mockito.anyLong());
  }
}
