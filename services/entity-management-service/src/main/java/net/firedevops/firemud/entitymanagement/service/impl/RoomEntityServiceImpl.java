package net.firedevops.firemud.entitymanagement.service.impl;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.firedevops.firemud.entitymanagement.config.LookProperties;
import net.firedevops.firemud.entitymanagement.dto.RoomEntityDto;
import net.firedevops.firemud.entitymanagement.entity.ContainerInstance;
import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.ItemInstance;
import net.firedevops.firemud.entitymanagement.repository.ContainerInstanceRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemInstanceRepository;
import net.firedevops.firemud.entitymanagement.service.RoomEntityService;
import net.firedevops.firemud.entitymanagement.v1.EntityType;
import net.firedevops.firemud.entitymanagement.v1.ReloadHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class RoomEntityServiceImpl implements RoomEntityService {
  private final LookProperties lookProperties;
  private final ItemInstanceRepository itemInstanceRepository;
  private final ContainerInstanceRepository containerInstanceRepository;

  public RoomEntityServiceImpl(
      LookProperties lookProperties,
      ItemInstanceRepository itemInstanceRepository,
      ContainerInstanceRepository containerInstanceRepository) {
    this.lookProperties = lookProperties;
    this.itemInstanceRepository = itemInstanceRepository;
    this.containerInstanceRepository = containerInstanceRepository;
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
                            entity.isVisible(),
                            null))
                .toList();
    Page<ItemInstance> roomGroundPage =
        itemInstanceRepository
            .findByTenantIdAndGameInstanceIdAndRoomInstanceIdAndCharacterIsNullAndEquipmentSlotIsNullOrderByIdAsc(
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

  private RoomEntityDto toRoomGroundEntity(ItemInstance entry) {
    String displayName = entry.getItem().getName();
    List<String> stateFlags = roomGroundAffordanceFlags(entry);
    return new RoomEntityDto(
        roomGroundEntityId(entry),
        displayName,
        EntityType.ITEM,
        "",
        stateFlags,
        0,
        ReloadHint.STABLE,
        true,
        entry.getVisibleRef());
  }

  private List<String> roomGroundAffordanceFlags(ItemInstance entry) {
    Item item = entry.getItem();
    List<String> flags = new java.util.ArrayList<>();
    flags.add("room-ground");
    if (item.isContainer()) {
      flags.add("container");
      Long containerInstanceId = resolveRoomContainerInstanceId(entry.getId());
      if (containerInstanceId != null) {
        flags.add("container-instance:" + containerInstanceId);
      }
    }
    String equipmentSlot = item.getEquipmentSlot();
    if (equipmentSlot != null && !equipmentSlot.isBlank()) {
      flags.add("wearable:" + equipmentSlot.trim().toUpperCase(java.util.Locale.ROOT));
    }
    return List.copyOf(flags);
  }

  private String roomGroundEntityId(ItemInstance entry) {
    return String.join(
        ":",
        String.valueOf(entry.getTenantId()),
        entry.getGameInstanceId(),
        entry.getRoomInstanceId(),
        String.valueOf(entry.getId()));
  }

  private Long resolveRoomContainerInstanceId(Long itemInstanceId) {
    return containerInstanceRepository
        .findByItemInstance_Id(itemInstanceId)
        .map(ContainerInstance::getId)
        .orElse(null);
  }
}
