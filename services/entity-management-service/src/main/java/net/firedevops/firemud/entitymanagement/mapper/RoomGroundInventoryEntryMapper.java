package net.firedevops.firemud.entitymanagement.mapper;

import net.firedevops.firemud.entitymanagement.dto.RoomGroundInventoryEntryDto;
import net.firedevops.firemud.entitymanagement.entity.RoomGroundInventoryEntry;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface RoomGroundInventoryEntryMapper {
  @Mapping(target = "tenantId", source = "id.tenantId")
  @Mapping(target = "gameInstanceId", source = "id.gameInstanceId")
  @Mapping(target = "roomInstanceId", source = "id.roomInstanceId")
  @Mapping(target = "itemId", source = "item.id")
  @Mapping(target = "itemName", source = "item.name")
  @Mapping(target = "itemDescription", source = "item.description")
  @Mapping(target = "itemInstanceId", source = "itemInstanceId")
  @Mapping(target = "containerInstanceId", source = "containerInstanceId")
  @Mapping(target = "visibleRef", source = "visibleRef")
  RoomGroundInventoryEntryDto toDto(RoomGroundInventoryEntry entity);
}
