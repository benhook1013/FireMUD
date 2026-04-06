package net.firedevops.firemud.entitymanagement.mapper;

import net.firedevops.firemud.entitymanagement.dto.ContainerContentEntryDto;
import net.firedevops.firemud.entitymanagement.entity.ContainerContentEntry;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ContainerContentEntryMapper {
  @Mapping(target = "tenantId", source = "id.tenantId")
  @Mapping(target = "characterId", source = "id.characterId")
  @Mapping(target = "containerItemId", source = "id.containerItemId")
  @Mapping(target = "itemId", source = "item.id")
  @Mapping(target = "itemName", source = "item.name")
  @Mapping(target = "itemDescription", source = "item.description")
  ContainerContentEntryDto toDto(ContainerContentEntry entity);
}
