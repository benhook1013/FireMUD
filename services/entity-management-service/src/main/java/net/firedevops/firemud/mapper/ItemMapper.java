package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.ItemDto;
import net.firedevops.firemud.entity.Item;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ItemMapper {
    ItemDto toDto(Item entity);
    Item toEntity(ItemDto dto);
}
