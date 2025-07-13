package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.RoomDto;
import net.firedevops.firemud.entity.Room;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface RoomMapper {
  @org.mapstruct.Mapping(target = "regionId", source = "region.id")
  RoomDto toDto(Room entity);

  @org.mapstruct.Mapping(target = "region.id", source = "regionId")
  @org.mapstruct.Mapping(target = "version", ignore = true)
  Room toEntity(RoomDto dto);
}
