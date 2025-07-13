package net.firedevops.firemud.mapper;

import net.firedevops.firemud.dto.RegionDto;
import net.firedevops.firemud.entity.Region;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface RegionMapper {
  RegionDto toDto(Region entity);

  @Mapping(target = "version", ignore = true)
  Region toEntity(RegionDto dto);
}
