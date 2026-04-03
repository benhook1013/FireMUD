package net.firedevops.firemud.worldmanagement.mapper;

import net.firedevops.firemud.worldmanagement.dto.RoomDto;
import net.firedevops.firemud.worldmanagement.entity.Room;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface RoomMapper {
  @org.mapstruct.Mapping(target = "regionId", source = "region.id")
  RoomDto toDto(Room entity);

  @org.mapstruct.Mapping(target = "region.id", source = "regionId")
  @org.mapstruct.Mapping(target = "nameLocalizedVariantsJson", ignore = true)
  @org.mapstruct.Mapping(target = "descriptionLocalizedVariantsJson", ignore = true)
  @org.mapstruct.Mapping(target = "version", ignore = true)
  Room toEntity(RoomDto dto);
}
