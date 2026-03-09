package net.firedevops.firemud.worldmanagement.mapper;

import net.firedevops.firemud.worldmanagement.dto.RegionDto;
import net.firedevops.firemud.worldmanagement.entity.Region;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface RegionMapper {
  RegionDto toDto(Region entity);

  @Mapping(target = "version", ignore = true)
  Region toEntity(RegionDto dto);
}
