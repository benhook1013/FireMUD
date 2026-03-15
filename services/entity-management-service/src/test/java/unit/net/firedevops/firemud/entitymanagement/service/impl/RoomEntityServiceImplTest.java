package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.firedevops.firemud.entitymanagement.config.LookProperties;
import net.firedevops.firemud.entitymanagement.dto.RoomEntityDto;
import net.firedevops.firemud.entitymanagement.v1.EntityType;
import net.firedevops.firemud.entitymanagement.v1.ReloadHint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RoomEntityServiceImplTest {
  private LookProperties props;
  private RoomEntityServiceImpl service;

  @BeforeEach
  void setup() {
    props = new LookProperties();
    service = new RoomEntityServiceImpl(props);
  }

  @Test
  void returnsEntitiesForConfiguredRoom() {
    LookProperties.LookRoom room = new LookProperties.LookRoom();
    room.getEntities().add(entity("P-1", "Sora", EntityType.PLAYER, ReloadHint.STABLE));
    room.getEntities().add(entity("NPC-1", "Kobold Scout", EntityType.NPC, ReloadHint.STABLE));
    props.getRooms().put("1:R-1021", room);

    List<RoomEntityDto> listed = service.listEntities("1", "R-1021");
    assertEquals(2, listed.size());
    assertTrue(listed.stream().anyMatch(dto -> dto.displayName().equals("Sora")));
  }

  @Test
  void returnsEmptyWhenRoomMissing() {
    List<RoomEntityDto> listed = service.listEntities("1", "missing");
    assertTrue(listed.isEmpty());
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
