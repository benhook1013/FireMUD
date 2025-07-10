package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.WorldEventDto;
import net.firedevops.firemud.entity.WorldEvent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface WorldEventMapper {
  @Mapping(target = "regionId", source = "region.id")
  WorldEventDto toDto(WorldEvent entity);

  @Mapping(target = "region.id", source = "regionId")
  WorldEvent toEntity(WorldEventDto dto);
}
