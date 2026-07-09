package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import net.firedevops.firemud.entitymanagement.config.LookProperties;
import net.firedevops.firemud.entitymanagement.dto.RoomEntityDto;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import net.firedevops.firemud.entitymanagement.entity.ItemInstance;
import net.firedevops.firemud.entitymanagement.entity.ItemStack;
import net.firedevops.firemud.entitymanagement.repository.ContainerInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemStackRepository;
import net.firedevops.firemud.entitymanagement.v1.EntityType;
import net.firedevops.firemud.entitymanagement.v1.ReloadHint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class RoomEntityServiceImplTest {
  private LookProperties props;
  private ItemInstanceRepository itemInstanceRepository;
  private ItemStackRepository itemStackRepository;
  private ContainerInstanceRepository containerInstanceRepository;
  private RoomEntityServiceImpl service;

  @BeforeEach
  void setup() {
    props = new LookProperties();
    itemInstanceRepository = Mockito.mock(ItemInstanceRepository.class);
    itemStackRepository = Mockito.mock(ItemStackRepository.class);
    containerInstanceRepository = Mockito.mock(ContainerInstanceRepository.class);
    Mockito.when(
            itemInstanceRepository
                .findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullOrderByIdAsc(
                    Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
        .thenReturn(new PageImpl<>(List.of()));
    Mockito.when(
            itemStackRepository
                .findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullAndContainerInstanceIsNullOrderByIdAsc(
                    Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
        .thenReturn(new PageImpl<>(List.of()));
    service =
        new RoomEntityServiceImpl(
            props, itemInstanceRepository, itemStackRepository, containerInstanceRepository);
  }

  @Test
  void returnsEntitiesForConfiguredRoom() {
    LookProperties.LookRoom room = new LookProperties.LookRoom();
    room.addEntity(entity("P-1", "Sora", EntityType.PLAYER, ReloadHint.STABLE));
    room.addEntity(entity("NPC-1", "Kobold Scout", EntityType.NPC, ReloadHint.STABLE));
    props.putRoom("1:R-1021", room);

    List<RoomEntityDto> listed = service.listEntities(1L, "game-1", "R-1021");
    assertEquals(2, listed.size());
    assertTrue(listed.stream().anyMatch(dto -> dto.displayName().equals("Sora")));
  }

  @Test
  void returnsEmptyWhenRoomMissing() {
    List<RoomEntityDto> listed = service.listEntities(1L, "game-1", "missing");
    assertTrue(listed.isEmpty());
  }

  @Test
  void listEntitiesRejectsZeroTenantIdBeforeRepositoryLookup() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> service.listEntities(0L, "game-1", "R-1"));

    assertEquals("tenantId must be positive", ex.getMessage());
    verifyNoInteractions(itemInstanceRepository, itemStackRepository, containerInstanceRepository);
  }

  @Test
  void includesRoomGroundItemsAsVisibleItemEntities() {
    var item = new net.firedevops.firemud.entitymanagement.entity.Item();
    item.setId(9L);
    item.setTenantId(1L);
    item.setName("Backpack");
    item.setDescription("A battered travel pack");
    item.setContainer(true);
    item.setEquipmentSlot("back");

    var entry = new ItemInstance();
    entry.setId(77L);
    entry.setTenantId(1L);
    entry.setGameInstanceId("game-1");
    entry.setRoomInstanceId("R-1021");
    entry.setItem(item);

    ContainerInstance instance = new ContainerInstance();
    instance.setId(42L);
    instance.setTenantId(1L);
    instance.setGameInstanceId("game-1");
    instance.setRoomInstanceId("R-1021");
    instance.setItem(item);
    instance.setItemInstance(entry);

    Mockito.when(
            itemInstanceRepository
                .findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullOrderByIdAsc(
                    1L, "game-1", "R-1021", Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(entry)));
    Mockito.when(
            itemStackRepository
                .findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullAndContainerInstanceIsNullOrderByIdAsc(
                    1L, "game-1", "R-1021", Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of()));
    Mockito.when(containerInstanceRepository.findByItemInstance_Id(77L))
        .thenReturn(java.util.Optional.of(instance));

    List<RoomEntityDto> listed = service.listEntities(1L, "game-1", "R-1021");
    assertEquals(1, listed.size());
    assertEquals(EntityType.ITEM, listed.get(0).entityType());
    assertEquals("Backpack", listed.get(0).displayName());
    assertEquals(
        List.of("room-ground", "container", "container-instance:42", "wearable:BACK"),
        listed.get(0).stateFlags());
  }

  @Test
  void includesRoomGroundStacksAsVisibleItemEntities() {
    var item = new net.firedevops.firemud.entitymanagement.entity.Item();
    item.setId(9L);
    item.setTenantId(1L);
    item.setName("Arrows");
    item.setDescription("A bundle of arrows");
    item.setStackable(true);

    ItemStack stack = new ItemStack();
    stack.setId(88L);
    stack.setTenantId(1L);
    stack.setGameInstanceId("game-1");
    stack.setRoomInstanceId("R-1021");
    stack.setItem(item);
    stack.setCompatibilityFingerprint("item-definition:9");
    stack.setQuantity(5);

    Mockito.when(
            itemInstanceRepository
                .findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullOrderByIdAsc(
                    1L, "game-1", "R-1021", Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of()));
    Mockito.when(
            itemStackRepository
                .findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullAndContainerInstanceIsNullOrderByIdAsc(
                    1L, "game-1", "R-1021", Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(stack)));

    List<RoomEntityDto> listed = service.listEntities(1L, "game-1", "R-1021");

    assertEquals(1, listed.size());
    assertEquals("Arrows", listed.get(0).displayName());
    assertEquals(List.of("room-ground"), listed.get(0).stateFlags());
  }

  private LookProperties.LookEntity entity(
      String id, String displayName, EntityType type, ReloadHint reloadHint) {
    LookProperties.LookEntity entity = new LookProperties.LookEntity();
    entity.setEntityId(id);
    entity.setDisplayName(displayName);
    entity.setEntityType(type);
    entity.setReloadHint(reloadHint);
    entity.setVisionPriority(5);
    entity.setRole("Adventurer");
    entity.setStateFlags(List.of("isVisible"));
    return entity;
  }
}
