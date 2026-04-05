package net.firedevops.firemud.entitymanagement.service.impl;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.firedevops.firemud.entitymanagement.config.LookProperties;
import net.firedevops.firemud.entitymanagement.dto.RoomEntityDto;
import net.firedevops.firemud.entitymanagement.entity.RoomGroundInventoryEntry;
import net.firedevops.firemud.entitymanagement.repository.RoomGroundInventoryRepository;
import net.firedevops.firemud.entitymanagement.service.RoomEntityService;
import net.firedevops.firemud.entitymanagement.v1.EntityType;
import net.firedevops.firemud.entitymanagement.v1.ReloadHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class RoomEntityServiceImpl implements RoomEntityService {
  private final LookProperties lookProperties;
  private final RoomGroundInventoryRepository roomGroundInventoryRepository;

  public RoomEntityServiceImpl(
      LookProperties lookProperties, RoomGroundInventoryRepository roomGroundInventoryRepository) {
    this.lookProperties = lookProperties;
    this.roomGroundInventoryRepository = roomGroundInventoryRepository;
  }

  @Override
  public List<RoomEntityDto> listEntities(String tenantId, String gameInstanceId, String roomId) {
    Map<String, LookProperties.LookRoom> rooms = lookProperties.getRooms();
    LookProperties.LookRoom room = rooms.getOrDefault(regionKey(tenantId, roomId), null);
    List<RoomEntityDto> configuredEntities =
        room == null
            ? Collections.emptyList()
            : room.getEntities().stream()
                .map(
                    entity ->
                        new RoomEntityDto(
                            entity.getEntityId(),
                            entity.getDisplayName(),
                            entity.getEntityType(),
                            entity.getRole(),
                            entity.getStateFlags(),
                            entity.getVisionPriority(),
                            entity.getReloadHint(),
                            entity.isVisible()))
                .toList();
    Page<RoomGroundInventoryEntry> roomGroundPage =
        roomGroundInventoryRepository.findByIdTenantIdAndIdGameInstanceIdAndIdRoomInstanceId(
            Long.parseLong(tenantId), gameInstanceId, roomId, Pageable.unpaged());
    List<RoomEntityDto> roomGroundEntities =
        roomGroundPage == null
            ? Collections.emptyList()
            : roomGroundPage.map(this::toRoomGroundEntity).getContent();
    if (configuredEntities.isEmpty()) {
      return roomGroundEntities;
    }
    if (roomGroundEntities.isEmpty()) {
      return configuredEntities;
    }
    return Stream.concat(configuredEntities.stream(), roomGroundEntities.stream()).toList();
  }

  private String regionKey(String tenantId, String roomId) {
    return tenantId + ":" + roomId;
  }

  private RoomEntityDto toRoomGroundEntity(RoomGroundInventoryEntry entry) {
    String displayName = entry.getItem().getName();
    if (entry.getQuantity() > 1) {
      displayName = displayName + " x" + entry.getQuantity();
    }
    return new RoomEntityDto(
        roomGroundEntityId(entry),
        displayName,
        EntityType.ITEM,
        "",
        List.of("room-ground"),
        0,
        ReloadHint.STABLE,
        true);
  }

  private String roomGroundEntityId(RoomGroundInventoryEntry entry) {
    return String.join(
        ":",
        String.valueOf(entry.getId().getTenantId()),
        entry.getId().getGameInstanceId(),
        entry.getId().getRoomInstanceId(),
        String.valueOf(entry.getId().getItemId()));
  }
}
