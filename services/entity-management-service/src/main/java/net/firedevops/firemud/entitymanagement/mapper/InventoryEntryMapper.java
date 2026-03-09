package net.firedevops.firemud.entitymanagement.mapper;

import net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto;
import net.firedevops.firemud.entitymanagement.entity.InventoryEntry;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface InventoryEntryMapper {
  @Mapping(target = "characterId", source = "character.id")
  @Mapping(target = "itemId", source = "item.id")
  InventoryEntryDto toDto(InventoryEntry entity);
}
