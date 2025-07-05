package net.fire_devops.firemud.mapper;

import net.fire_devops.firemud.dto.ItemDto;
import net.fire_devops.firemud.entity.Item;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ItemMapper {
    ItemDto toDto(Item entity);
    Item toEntity(ItemDto dto);
}
