package net.firedevops.firemud.entitymanagement.mapper;

import net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto;
import net.firedevops.firemud.entitymanagement.entity.InventoryEntry;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface InventoryEntryMapper {
  @Mapping(target = "tenantId", source = "character.tenantId")
  @Mapping(target = "characterId", source = "character.id")
  @Mapping(target = "itemId", source = "item.id")
  @Mapping(target = "itemName", source = "item.name")
  @Mapping(target = "itemDescription", source = "item.description")
  @Mapping(target = "itemInstanceId", source = "itemInstanceId")
  @Mapping(target = "containerInstanceId", source = "containerInstanceId")
  InventoryEntryDto toDto(InventoryEntry entity);
}
