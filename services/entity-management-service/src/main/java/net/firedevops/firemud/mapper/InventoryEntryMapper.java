package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.InventoryEntryDto;
import net.firedevops.firemud.entity.InventoryEntry;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface InventoryEntryMapper {
  @Mapping(target = "characterId", source = "character.id")
  @Mapping(target = "itemId", source = "item.id")
  InventoryEntryDto toDto(InventoryEntry entity);
}
