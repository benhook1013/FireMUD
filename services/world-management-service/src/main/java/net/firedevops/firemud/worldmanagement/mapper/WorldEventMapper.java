package net.firedevops.firemud.worldmanagement.mapper;

import net.firedevops.firemud.worldmanagement.dto.WorldEventDto;
import net.firedevops.firemud.worldmanagement.entity.WorldEvent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface WorldEventMapper {
  @Mapping(target = "regionId", source = "regionInstance.id")
  WorldEventDto toDto(WorldEvent entity);

  @Mapping(target = "regionInstance.id", source = "regionId")
  @Mapping(target = "version", ignore = true)
  WorldEvent toEntity(WorldEventDto dto);
}
