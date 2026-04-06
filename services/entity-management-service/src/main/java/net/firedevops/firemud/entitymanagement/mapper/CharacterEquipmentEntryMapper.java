package net.firedevops.firemud.entitymanagement.mapper;

import net.firedevops.firemud.entitymanagement.dto.CharacterEquipmentEntryDto;
import net.firedevops.firemud.entitymanagement.entity.CharacterEquipmentEntry;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface CharacterEquipmentEntryMapper {
  @Mapping(target = "tenantId", source = "character.tenantId")
  @Mapping(target = "characterId", source = "character.id")
  @Mapping(target = "slot", source = "id.slot")
  @Mapping(target = "itemId", source = "item.id")
  @Mapping(target = "itemName", source = "item.name")
  @Mapping(target = "itemDescription", source = "item.description")
  CharacterEquipmentEntryDto toDto(CharacterEquipmentEntry entity);
}
