package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.RoomDto;
import net.firedevops.firemud.entity.Room;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface RoomMapper {
    RoomDto toDto(Room entity);
    Room toEntity(RoomDto dto);
}
