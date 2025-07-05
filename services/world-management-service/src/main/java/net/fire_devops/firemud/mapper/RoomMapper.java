package net.fire_devops.firemud.mapper;

import net.fire_devops.firemud.dto.RoomDto;
import net.fire_devops.firemud.entity.Room;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface RoomMapper {
    RoomDto toDto(Room entity);
    Room toEntity(RoomDto dto);
}
