package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.firedevops.firemud.entitymanagement.config.LookProperties;
import net.firedevops.firemud.entitymanagement.dto.RoomEntityDto;
import net.firedevops.firemud.entitymanagement.entity.RoomGroundInventoryEntry;
import net.firedevops.firemud.entitymanagement.entity.RoomGroundInventoryKey;
import net.firedevops.firemud.entitymanagement.repository.RoomGroundInventoryRepository;
import net.firedevops.firemud.entitymanagement.v1.EntityType;
import net.firedevops.firemud.entitymanagement.v1.ReloadHint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class RoomEntityServiceImplTest {
  private LookProperties props;
  private RoomGroundInventoryRepository roomGroundInventoryRepository;
  private RoomEntityServiceImpl service;

  @BeforeEach
  void setup() {
    props = new LookProperties();
    roomGroundInventoryRepository = Mockito.mock(RoomGroundInventoryRepository.class);
    service = new RoomEntityServiceImpl(props, roomGroundInventoryRepository);
  }

  @Test
  void returnsEntitiesForConfiguredRoom() {
    LookProperties.LookRoom room = new LookProperties.LookRoom();
    room.getEntities().add(entity("P-1", "Sora", EntityType.PLAYER, ReloadHint.STABLE));
    room.getEntities().add(entity("NPC-1", "Kobold Scout", EntityType.NPC, ReloadHint.STABLE));
    props.getRooms().put("1:R-1021", room);

    List<RoomEntityDto> listed = service.listEntities("1", "game-1", "R-1021");
    assertEquals(2, listed.size());
    assertTrue(listed.stream().anyMatch(dto -> dto.displayName().equals("Sora")));
  }

  @Test
  void returnsEmptyWhenRoomMissing() {
    List<RoomEntityDto> listed = service.listEntities("1", "game-1", "missing");
    assertTrue(listed.isEmpty());
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

    var key = new RoomGroundInventoryKey();
    key.setTenantId(1L);
    key.setGameInstanceId("game-1");
    key.setRoomInstanceId("R-1021");
    key.setItemId(9L);

    var entry = new RoomGroundInventoryEntry();
    entry.setId(key);
    entry.setItem(item);
    entry.setQuantity(2);

    Mockito.when(
            roomGroundInventoryRepository.findByIdTenantIdAndIdGameInstanceIdAndIdRoomInstanceId(
                1L, "game-1", "R-1021", Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(entry)));

    List<RoomEntityDto> listed = service.listEntities("1", "game-1", "R-1021");
    assertEquals(1, listed.size());
    assertEquals(EntityType.ITEM, listed.get(0).entityType());
    assertEquals("Backpack x2", listed.get(0).displayName());
    assertEquals(List.of("room-ground", "container", "wearable:BACK"), listed.get(0).stateFlags());
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
