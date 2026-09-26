package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.repository.CraftingRecipeRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemRepository;
import net.firedevops.firemud.entitymanagement.repository.NpcRepository;
import net.firedevops.firemud.entitymanagement.service.EntityDraftDesignDigestService.EntityDraftDesignDigest;
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

  @Test
  void normalizesMissingAndBlankEquipmentSlotGroupKeysToTheSameDigest() {
    var missingDigest = digestForEquipmentSlotGroupKey(null);
    var blankDigest = digestForEquipmentSlotGroupKey(" \t ");

    assertEquals(2, missingDigest.digestSchemaVersion());
    assertEquals(missingDigest.contentDigest(), blankDigest.contentDigest());
  }

  @Test
  void equipmentSlotGroupConstraintChangesTheDigest() {
    var wingsDigest = digestForEquipmentSlotGroupKey(" wings ");
    var normalizedWingsDigest = digestForEquipmentSlotGroupKey("WINGS");
    var torsoDigest = digestForEquipmentSlotGroupKey("TORSO");

    assertEquals(2, wingsDigest.digestSchemaVersion());
    assertEquals(wingsDigest.contentDigest(), normalizedWingsDigest.contentDigest());
    assertNotEquals(wingsDigest.contentDigest(), torsoDigest.contentDigest());
  }

  @Test
  void readsItemsWithinTheRequestedTenantAndVersion() {
    ItemRepository itemRepository = Mockito.mock(ItemRepository.class);
    NpcRepository npcRepository = Mockito.mock(NpcRepository.class);
    CraftingRecipeRepository craftingRecipeRepository =
        Mockito.mock(CraftingRecipeRepository.class);
    Mockito.when(itemRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of(item(11L, "WINGS")));
    Mockito.when(itemRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 8L))
        .thenReturn(List.of(item(11L, "TORSO")));
    Mockito.when(itemRepository.findByTenantIdAndVersionIdOrderByIdAsc(2L, 7L))
        .thenReturn(List.of(item(11L, null)));
    for (long tenantId : List.of(1L, 2L)) {
      for (long versionId : List.of(7L, 8L)) {
        Mockito.when(npcRepository.findByTenantIdAndVersionIdOrderByIdAsc(tenantId, versionId))
            .thenReturn(List.of());
        Mockito.when(
                craftingRecipeRepository.findByTenantIdAndVersionIdOrderByIdAsc(
                    tenantId, versionId))
            .thenReturn(List.of());
      }
    }
    EntityDraftDesignDigestServiceImpl service =
        new EntityDraftDesignDigestServiceImpl(
            itemRepository, npcRepository, craftingRecipeRepository, new ObjectMapper());

    var tenantOneVersionSeven = service.getDraftDesignDigest("1", "7");
    var tenantOneVersionEight = service.getDraftDesignDigest("1", "8");
    var tenantTwoVersionSeven = service.getDraftDesignDigest("2", "7");

    assertNotEquals(tenantOneVersionSeven.contentDigest(), tenantOneVersionEight.contentDigest());
    assertNotEquals(tenantOneVersionSeven.contentDigest(), tenantTwoVersionSeven.contentDigest());
    Mockito.verify(itemRepository).findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L);
    Mockito.verify(itemRepository).findByTenantIdAndVersionIdOrderByIdAsc(1L, 8L);
    Mockito.verify(itemRepository).findByTenantIdAndVersionIdOrderByIdAsc(2L, 7L);
    Mockito.verify(itemRepository, Mockito.never()).findByTenantIdOrderByIdAsc(Mockito.anyLong());
  }

  private static EntityDraftDesignDigest digestForEquipmentSlotGroupKey(String groupKey) {
    ItemRepository itemRepository = Mockito.mock(ItemRepository.class);
    NpcRepository npcRepository = Mockito.mock(NpcRepository.class);
    CraftingRecipeRepository craftingRecipeRepository =
        Mockito.mock(CraftingRecipeRepository.class);
    Mockito.when(itemRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of(item(11L, groupKey)));
    Mockito.when(npcRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of());
    Mockito.when(craftingRecipeRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of());
    EntityDraftDesignDigestServiceImpl service =
        new EntityDraftDesignDigestServiceImpl(
            itemRepository, npcRepository, craftingRecipeRepository, new ObjectMapper());

    return service.getDraftDesignDigest("1", "7");
  }

  private static Item item(Long id, String equipmentSlotGroupKey) {
    Item item = new Item();
    item.setId(id);
    item.setName("Test item");
    item.setEquipmentSlotGroupKey(equipmentSlotGroupKey);
    return item;
  }
}
