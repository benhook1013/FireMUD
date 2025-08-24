package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.ItemDto;
import net.firedevops.firemud.entity.Item;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ItemMapper {
  ItemDto toDto(Item entity);

  @Mapping(target = "version", ignore = true)
  Item toEntity(ItemDto dto);
}
