package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.firedevops.firemud.entitymanagement.repository.CharacterEquipmentRepository;
import net.firedevops.firemud.entitymanagement.repository.CharacterFriendRepository;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.repository.ContainerInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.InventoryEntryRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemStackRepository;
import net.firedevops.firemud.entitymanagement.repository.RoomGroundInventoryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EntityUpgradeValidationServiceImplTest {
  private final CharacterRepository characterRepository = Mockito.mock(CharacterRepository.class);
  private final InventoryEntryRepository inventoryEntryRepository =
      Mockito.mock(InventoryEntryRepository.class);
  private final CharacterEquipmentRepository characterEquipmentRepository =
      Mockito.mock(CharacterEquipmentRepository.class);
  private final CharacterFriendRepository characterFriendRepository =
      Mockito.mock(CharacterFriendRepository.class);
  private final ItemInstanceRepository itemInstanceRepository =
      Mockito.mock(ItemInstanceRepository.class);
  private final ItemStackRepository itemStackRepository = Mockito.mock(ItemStackRepository.class);
  private final ContainerInstanceRepository containerInstanceRepository =
      Mockito.mock(ContainerInstanceRepository.class);
  private final RoomGroundInventoryRepository roomGroundInventoryRepository =
      Mockito.mock(RoomGroundInventoryRepository.class);

  private final EntityUpgradeValidationServiceImpl service =
      new EntityUpgradeValidationServiceImpl(
          characterRepository,
          inventoryEntryRepository,
          characterEquipmentRepository,
          characterFriendRepository,
          itemInstanceRepository,
          itemStackRepository,
          containerInstanceRepository,
          roomGroundInventoryRepository);

  @Test
  void returnsCompatibleWhenNoSurvivorRowsExist() {
    var result = service.validateEntityUpgradeMappings(1L, 7L, 9L, null);

    assertEquals("COMPATIBLE", result.result());
    assertEquals(false, result.hasS2Rows());
    assertTrue(result.checkedFamilies().contains("characters"));
    assertTrue(result.checkedFamilies().contains("room_ground_inventory"));
  }

  @Test
  void failsClosedWhenSurvivorRowsExistBeforeS1S2ValidationIsImplemented() {
    Mockito.when(characterRepository.countByTenantId(1L)).thenReturn(2L);
    Mockito.when(inventoryEntryRepository.countByCharacterTenantId(1L)).thenReturn(1L);

    var result = service.validateEntityUpgradeMappings(1L, 7L, 9L, null);

    assertEquals("INCOMPATIBLE", result.result());
    assertEquals(true, result.hasS2Rows());
    assertEquals(true, result.remapSetRequired());
    assertTrue(result.reasons().get(0).contains("ENTITY_SURVIVOR_STATE_UNSUPPORTED"));
  }
}
